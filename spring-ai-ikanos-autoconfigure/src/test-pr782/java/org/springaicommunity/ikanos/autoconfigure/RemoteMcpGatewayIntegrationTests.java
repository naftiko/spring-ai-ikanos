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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.ikanos.Capability;
import io.ikanos.engine.consumes.mcp.McpClientAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.ikanos.FleetFixture;
import org.springaicommunity.ikanos.IkanosCapabilityLifecycle;
import org.springaicommunity.ikanos.IkanosToolCallback;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike (naftiko/ikanos#782): a Spring AI 2.0 application consumes a stateless MCP
 * {@code 2026-07-28} server through an embedded Ikanos capability.
 *
 * <p>
 * Topology, all in one JVM on free ports:
 * <ol>
 * <li><b>upstream API</b> — {@link FleetFixture}'s HTTP stub;</li>
 * <li><b>remote MCP server</b> — the fleet capability started as a real Ikanos MCP server
 * ({@code 2026-07-28} only);</li>
 * <li><b>gateway capability</b> — {@code consumes: type: mcp} against (2), re-exposing a curated
 * subset of its tools, loaded by {@link IkanosAutoConfiguration} and never served;</li>
 * <li><b>Spring AI</b> — a {@link ChatClient} with a scripted model calls the bridged tool.</li>
 * </ol>
 * Spring AI's own MCP client (MCP Java SDK 2.0.1, {@code 2025-11-25}) cannot connect to (2): see
 * {@code PostureParityIntegrationTests#sdk2ClientHandshakeIsRefusedByIkanosServer}.
 */
class RemoteMcpGatewayIntegrationTests {

	@TempDir
	Path dir;

	private FleetFixture fixture;

	private Capability remote;

	private ApplicationContextRunner runner;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();
		this.remote = this.fixture.capability("fleet");
		this.remote.start();

		Files.writeString(this.dir.resolve("gateway.yml"), gatewayYaml(this.fixture.mcpPort, "verify", ""));
		this.runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(IkanosAutoConfiguration.class))
			.withPropertyValues("spring.ai.ikanos.capabilities=" + location(this.dir));
	}

	@AfterEach
	void tearDown() throws Exception {
		this.remote.stop();
		this.fixture.close();
	}

	private static String location(Path dir) {
		return "file:" + dir.toAbsolutePath().toString().replace('\\', '/') + "/*.yml";
	}

	/**
	 * Gateway capability: consumes the remote fleet MCP server, re-exposes only the read-only
	 * tool, and keeps the destructive one out of the model's reach.
	 */
	static String gatewayYaml(int remoteMcpPort, String discovery, String extraConsumedTool) {
		return """
				ikanos: "1.0.0-beta7"
				info:
				  display: Fleet gateway
				  description: Governed access to a remote fleet MCP server
				capability:
				  consumes:
				    - type: mcp
				      namespace: remote-fleet
				      description: Remote fleet MCP server
				      endpoint: http://localhost:%d/
				      discovery: %s
				      tools:
				        list-ships:
				          description: List ships by status
				          hints: { readOnly: true }
				          inputParameters:
				            status: { type: string, description: Operational status }
				%s
				  exposes:
				    - type: mcp
				      address: localhost
				      port: 0
				      namespace: gateway
				      tools:
				        fleet-ships:
				          description: List the ships of the fleet by status
				          hints: { readOnly: true }
				          inputParameters:
				            status: { type: string, description: Operational status }
				          call: remote-fleet.list-ships
				          with:
				            status: gateway.status
				""".formatted(remoteMcpPort, discovery, extraConsumedTool);
	}

	@Test
	void bridgedToolReachesRemoteMcpServerInJvm() {
		this.runner.run(context -> {
			assertThat(context).hasNotFailed();

			IkanosCapabilityLifecycle lifecycle = context.getBean(IkanosCapabilityLifecycle.class);
			assertThat(lifecycle.getCapabilities().get(0).getClientAdapters().get(0))
				.isInstanceOf(McpClientAdapter.class);

			IkanosToolCallbackProvider provider = context.getBean(IkanosToolCallbackProvider.class);
			assertThat(Arrays.stream(provider.getToolCallbacks()).map(tc -> tc.getToolDefinition().name()))
				.as("only the curated tool is visible; decommission-ship is not")
				.containsExactly("fleet-ships");
			assertThat(((IkanosToolCallback) provider.getToolCallbacks()[0]).getToolAnnotations().readOnlyHint())
				.isTrue();

			String result = provider.getToolCallbacks()[0].call("{\"status\":\"active\"}");

			assertThat(result).contains("Sea Eagle");
			// hop: Spring AI -> gateway (in-JVM) -> remote Ikanos MCP server -> upstream API
			assertThat(this.fixture.requests).containsExactly("GET /ships?status=active");
		});
	}

	@Test
	void chatClientToolLoopReachesRemoteMcpServer() {
		this.runner.run(context -> {
			IkanosToolCallbackProvider tools = context.getBean(IkanosToolCallbackProvider.class);
			ScriptedChatModel model = new ScriptedChatModel("fleet-ships", "{\"status\":\"active\"}");

			String answer = ChatClient.create(model).prompt().user("Which ships are active?").tools(tools).call().content();

			assertThat(model.advertisedTools).containsExactly("fleet-ships");
			assertThat(answer).startsWith("Final:").contains("Sea Eagle");
			assertThat(this.fixture.requests).containsExactly("GET /ships?status=active");
		});
	}

	@Test
	void discoveryFailsStartupWhenDeclaredRemoteToolIsMissing() throws Exception {
		Files.writeString(this.dir.resolve("gateway.yml"), gatewayYaml(this.fixture.mcpPort, "verify", """
				        no-such-tool:
				          description: Not on the remote server
				          hints: { readOnly: true }
				"""));

		this.runner.run(context -> assertThat(context).hasFailed()
			.getFailure()
			.hasStackTraceContaining("tool 'no-such-tool' not found upstream"));
	}

	/** First call: request one tool. Second call (after a tool response): answer with it. */
	static final class ScriptedChatModel implements ChatModel {

		final List<String> advertisedTools = new ArrayList<>();

		private final String toolName;

		private final String toolArguments;

		ScriptedChatModel(String toolName, String toolArguments) {
			this.toolName = toolName;
			this.toolArguments = toolArguments;
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			List<Message> messages = prompt.getInstructions();
			if (messages.get(messages.size() - 1) instanceof ToolResponseMessage toolResponse) {
				String data = toolResponse.getResponses().get(0).responseData();
				return new ChatResponse(
						List.of(new Generation(AssistantMessage.builder().content("Final: " + data).build())));
			}
			if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
				options.getToolCallbacks().forEach(tc -> this.advertisedTools.add(tc.getToolDefinition().name()));
			}
			AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", this.toolName,
					this.toolArguments);
			return new ChatResponse(List.of(
					new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
		}

	}

}
