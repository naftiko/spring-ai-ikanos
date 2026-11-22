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

package org.springaicommunity.ikanos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.ikanos.approval.ToolApprovalMode;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * A tool's {@code authorization} requirement is checked in-JVM against the caller's claims, and
 * the claims reach {@code {{auth.claims...}}} templates, as a validated token's do on a remote
 * Ikanos MCP server (naftiko/ikanos#999, #998, #1126).
 */
class ToolAuthorizationIntegrationTests {

	private static final Map<String, Object> ADMIN = Map.of("sub", "alice", "scope", "fleet.read fleet.admin");

	private static final Map<String, Object> READER = Map.of("sub", "bob", "scope", "fleet.read");

	/** Values of the {@code X-User-Id} header the upstream received, one per request. */
	private final List<String> userIds = new CopyOnWriteArrayList<>();

	private HttpServer upstream;

	private Capability capability;

	@BeforeEach
	void setUp() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.upstream.createContext("/", exchange -> {
			this.userIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-User-Id")));
			byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		this.upstream.start();
		this.capability = load(this.upstream.getAddress().getPort());
	}

	@AfterEach
	void tearDown() {
		this.upstream.stop(0);
	}

	@Test
	void callerHoldingTheScopeReachesTheUpstreamAsItself() {
		tool(provider(ToolAuthorizationMode.ENFORCE, context -> ADMIN), "decommission-ship").call("{}");

		assertThat(this.userIds).containsExactly("alice");
	}

	@Test
	void callerWithoutTheScopeIsDeniedWithoutReachingTheUpstream() {
		String result = tool(provider(ToolAuthorizationMode.ENFORCE, context -> READER), "decommission-ship")
			.call("{}");

		assertThat(result).startsWith("Denied: ").contains("fleet.admin");
		assertThat(this.userIds).isEmpty();
	}

	@Test
	void callWithNoCallerIsDenied() {
		String result = tool(provider(ToolAuthorizationMode.ENFORCE, context -> null), "decommission-ship")
			.call("{}");

		assertThat(result).isEqualTo("Denied: No validated access token");
		assertThat(this.userIds).isEmpty();
	}

	@Test
	void toolWithoutARequirementRunsForAnyCaller() {
		tool(provider(ToolAuthorizationMode.ENFORCE, context -> READER), "who-am-i").call("{}");

		assertThat(this.userIds).containsExactly("bob");
	}

	@Test
	void claimsCanComeFromTheToolContext() {
		tool(provider(ToolAuthorizationMode.ENFORCE, IkanosCallerClaimsResolver.fromToolContext()), "decommission-ship")
			.call("{}", new ToolContext(Map.of(IkanosCallerClaimsResolver.TOOL_CONTEXT_KEY, ADMIN)));

		assertThat(this.userIds).containsExactly("alice");
	}

	@Test
	void buildFailsWhenARequirementCannotBeChecked() {
		assertThatIllegalStateException().isThrownBy(() -> provider(ToolAuthorizationMode.ENFORCE, null))
			.withMessageContaining("[decommission-ship]")
			.withMessageContaining("IkanosCallerClaimsResolver");
	}

	@Test
	void noneModeRunsTheToolWithoutChecking() {
		tool(provider(ToolAuthorizationMode.NONE, context -> READER), "decommission-ship").call("{}");

		assertThat(this.userIds).containsExactly("bob");
	}

	private IkanosToolCallbackProvider provider(ToolAuthorizationMode mode, IkanosCallerClaimsResolver resolver) {
		return IkanosToolCallbackProvider.builder()
			.approvalMode(ToolApprovalMode.NONE)
			.authorizationMode(mode)
			.callerClaimsResolver(resolver)
			.capabilities(this.capability)
			.build();
	}

	private static ToolCallback tool(IkanosToolCallbackProvider provider, String name) {
		for (ToolCallback callback : provider.getToolCallbacks()) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new IllegalArgumentException("No tool " + name);
	}

	private static Capability load(int upstreamPort) throws IOException {
		String yaml = """
				ikanos: "1.0.0-beta10"
				info:
				  display: Authorization
				  description: spring-ai-ikanos test capability
				capability:
				  consumes:
				  - namespace: registry
				    type: http
				    baseUri: "http://localhost:%d"
				    inputParameters:
				      X-User-Id:
				        in: header
				        value: "{{auth.claims.sub}}"
				    resources:
				      ship:
				        path: /ship
				        operations:
				          decommission:
				            method: DELETE
				          get-ship:
				            method: GET
				  exposes:
				  - type: mcp
				    address: localhost
				    port: 0
				    namespace: fleet
				    description: Fleet tools
				    authentication:
				      type: oauth2
				      authorizationServerUri: "http://localhost:1"
				      resource: "http://localhost/mcp"
				      scopes: ["fleet.read", "fleet.admin"]
				    tools:
				      decommission-ship:
				        description: Decommission the ship, for fleet admins
				        authorization:
				          scopes: ["fleet.admin"]
				        call: registry.decommission
				      who-am-i:
				        description: Read the ship as the caller
				        hints:
				          readOnly: true
				        call: registry.get-ship
				        outputParameters:
				        - type: boolean
				          mapping: "$.ok"
				""".formatted(upstreamPort);
		Path file = Files.createTempFile("authorization-", ".yml");
		try {
			Files.writeString(file, yaml);
			return Capability.builder().loadFromFile(file).telemetry(false).build();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

}
