/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.ikanos.autoconfigure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.ikanos.FleetFixture;
import org.springaicommunity.ikanos.IkanosCapabilityLifecycle;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;
import org.springaicommunity.ikanos.approval.ToolApprovalMode;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3 — "same YAML, two postures". The same capability is (a) served as a real Ikanos MCP server
 * over Streamable HTTP and (b) bridged in-JVM with {@link IkanosToolCallbackProvider}. The
 * model-facing {@link ToolDefinition}s and tool outputs must be identical.
 *
 * <p>
 * <strong>Why not {@code SyncMcpToolCallback}:</strong> the Ikanos server speaks only MCP
 * {@code 2026-07-28} (stateless, {@code Mcp-Method} header, protocol version in {@code _meta}),
 * while MCP Java SDK {@code 2.0.1} — used by Spring AI {@code 2.0.1} — speaks at most
 * {@code 2025-11-25} and starts with an {@code initialize} handshake, which the server rejects.
 * The remote side is therefore read over raw {@code 2026-07-28} HTTP, then converted exactly as
 * {@code SyncMcpToolCallback} does: {@code McpToolUtils.createToolDefinition(name, tool)} for the
 * definition and {@code new JsonHelper().toJson(result.content())} for a successful call.
 */
class PostureParityIntegrationTests {

	private static final String PROTOCOL_VERSION = "2026-07-28";

	private static final JsonHelper json = new JsonHelper();

	private static final McpJsonMapper mcpJson = McpJsonDefaults.getMapper();

	private final HttpClient http = HttpClient.newHttpClient();

	private FleetFixture fixture;

	private Capability served;

	private IkanosCapabilityLifecycle inJvm;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();

		// (a) remote posture: the capability started as a real MCP server
		this.served = this.fixture.capability("fleet");
		this.served.start();

		// (b) in-JVM posture: a second, never-served instance of the same YAML
		this.inJvm = new IkanosCapabilityLifecycle(List.of(this.fixture.capability("fleet")));
		this.inJvm.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.inJvm != null) {
			this.inJvm.stop();
		}
		if (this.served != null) {
			this.served.stop();
		}
		this.fixture.close();
	}

	private HttpResponse<String> post(Map<String, Object> body, Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest
			.newBuilder(URI.create("http://localhost:" + this.fixture.mcpPort + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(json.toJson(body)));
		headers.forEach(request::header);
		return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** One {@code 2026-07-28} JSON-RPC call, returning the {@code result} member. */
	private Object rpc(String method, Map<String, Object> params, String name) throws Exception {
		Map<String, Object> p = new LinkedHashMap<>(params);
		p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION));
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("MCP-Protocol-Version", PROTOCOL_VERSION);
		headers.put("Mcp-Method", method);
		if (name != null) {
			headers.put("Mcp-Name", name);
		}
		HttpResponse<String> response = post(Map.of("jsonrpc", "2.0", "id", "1", "method", method, "params", p),
				headers);
		assertThat(response.statusCode()).as(method + " -> " + response.body()).isEqualTo(200);
		Map<String, Object> envelope = json.fromJsonToMap(response.body());
		assertThat(envelope).as(method + " error").doesNotContainKey("error");
		return envelope.get("result");
	}

	private List<McpSchema.Tool> remoteTools() throws Exception {
		return mcpJson.convertValue(rpc("tools/list", Map.of(), null), McpSchema.ListToolsResult.class).tools();
	}

	private Map<String, ToolCallback> inJvmCallbacks() {
		return Arrays
			.stream(IkanosToolCallbackProvider.builder().approvalMode(ToolApprovalMode.NONE)
				.capabilities(this.inJvm.getCapabilities())
				.build()
				.getToolCallbacks())
			.collect(Collectors.toMap(tc -> tc.getToolDefinition().name(), Function.identity()));
	}

	@Test
	void toolDefinitionsAreIdentical() throws Exception {
		// what SyncMcpToolCallback would expose for each remote tool
		Map<String, ToolDefinition> remote = remoteTools().stream()
			.collect(Collectors.toMap(t -> t.name(), t -> McpToolUtils.createToolDefinition(t.name(), t)));
		Map<String, ToolCallback> local = inJvmCallbacks();

		assertThat(local.keySet()).containsExactlyInAnyOrderElementsOf(remote.keySet());
		for (String name : remote.keySet()) {
			ToolDefinition r = remote.get(name);
			ToolDefinition l = local.get(name).getToolDefinition();
			assertThat(l.name()).as(name + " name").isEqualTo(r.name());
			assertThat(l.description()).as(name + " description").isEqualTo(r.description());
			// compare as parsed JSON: key order is not part of the contract
			assertThat(json.fromJsonToMap(l.inputSchema())).as(name + " inputSchema")
				.isEqualTo(json.fromJsonToMap(r.inputSchema()));
		}
	}

	@Test
	void callOutputsAreIdentical() throws Exception {
		McpSchema.CallToolResult result = mcpJson.convertValue(
				rpc("tools/call", Map.of("name", "list-ships", "arguments", Map.of("status", "active")), "list-ships"),
				McpSchema.CallToolResult.class);
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		// what SyncMcpToolCallback.call returns on success
		String remote = json.toJson(result.content());

		String local = inJvmCallbacks().get("list-ships").call("{\"status\":\"active\"}");

		assertThat(local).isEqualTo(remote);
		assertThat(local).contains("Sea Eagle");
		assertThat(this.fixture.requests).containsExactly("GET /ships?status=active", "GET /ships?status=active");
	}

	@Test
	void annotationsAreIdenticalOnBothSides() throws Exception {
		Map<String, McpSchema.ToolAnnotations> remote = remoteTools().stream()
			.collect(Collectors.toMap(t -> t.name(), t -> t.annotations()));
		McpServerAdapter adapter = (McpServerAdapter) this.inJvm.getCapabilities().get(0).getServerAdapters().get(0);
		Map<String, McpSchema.ToolAnnotations> local = adapter.getTools()
			.stream()
			.collect(Collectors.toMap(t -> t.name(), t -> t.annotations()));

		assertThat(local).isEqualTo(remote);
		assertThat(local.get("decommission-ship").destructiveHint()).isTrue();
	}

	/**
	 * Records the interop gap: the MCP Java SDK 2.0 client handshake ({@code initialize},
	 * {@code 2025-11-25}) is refused by the Ikanos {@code 2026-07-28} server. When this starts
	 * failing, switch E3 to {@code SyncMcpToolCallback}.
	 */
	@Test
	void sdk2ClientHandshakeIsRefusedByIkanosServer() throws Exception {
		HttpResponse<String> response = post(Map.of("jsonrpc", "2.0", "id", "1", "method", "initialize", "params",
				Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo",
						Map.of("name", "probe", "version", "1"))),
				Map.of());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("Unsupported protocol version").contains(PROTOCOL_VERSION);
	}

}
