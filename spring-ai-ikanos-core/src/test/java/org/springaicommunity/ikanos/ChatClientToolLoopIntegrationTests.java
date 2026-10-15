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

import java.util.ArrayList;
import java.util.List;

import io.ikanos.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 — the real Spring AI 2.0 {@link ChatClient} tool loop drives a bridged Ikanos tool. A
 * scripted {@link ChatModel} replaces the LLM, so the test is deterministic and needs no key.
 */
class ChatClientToolLoopIntegrationTests {

	private FleetFixture fixture;

	private IkanosCapabilityLifecycle lifecycle;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();
	}

	@AfterEach
	void tearDown() {
		if (this.lifecycle != null) {
			this.lifecycle.stop();
		}
		this.fixture.close();
	}

	@Test
	void chatClientCallsBridgedToolAndReturnsFinalAnswer() throws Exception {
		Capability capability = this.fixture.capability("fleet");
		this.lifecycle = new IkanosCapabilityLifecycle(List.of(capability));
		this.lifecycle.start();
		IkanosToolCallbackProvider tools = IkanosToolCallbackProvider.builder().capabilities(capability).build();

		ScriptedChatModel model = new ScriptedChatModel("list-ships", "{\"status\":\"active\"}");

		String answer = ChatClient.create(model).prompt().user("Which ships are active?").tools(tools).call().content();

		// the model saw both bridged tools, with the MCP-derived definition
		assertThat(model.advertisedTools).containsExactlyInAnyOrder("list-ships", "decommission-ship");
		// the upstream received the mapped request
		assertThat(this.fixture.requests).containsExactly("GET /ships?status=active");
		// the tool result went back to the model, and the final answer carries it
		assertThat(model.toolResponses).hasSize(1);
		assertThat(model.toolResponses.get(0)).contains("IMO-1").contains("Sea Eagle");
		assertThat(answer).startsWith("Final:").contains("Sea Eagle");
	}

	/**
	 * First call: request one tool. Second call (after a tool response): answer with it.
	 */
	static final class ScriptedChatModel implements ChatModel {

		final List<String> advertisedTools = new ArrayList<>();

		final List<String> toolResponses = new ArrayList<>();

		private final String toolName;

		private final String toolArguments;

		ScriptedChatModel(String toolName, String toolArguments) {
			this.toolName = toolName;
			this.toolArguments = toolArguments;
		}

		@Override
		public ChatOptions getOptions() {
			// ChatClient copies these options and only merges tool callbacks into a
			// ToolCallingChatOptions builder; a plain ChatOptions silently drops the tools.
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			List<Message> messages = prompt.getInstructions();
			Message last = messages.get(messages.size() - 1);

			if (last instanceof ToolResponseMessage toolResponse) {
				String data = toolResponse.getResponses().get(0).responseData();
				this.toolResponses.add(data);
				return response(AssistantMessage.builder().content("Final: " + data).build());
			}

			if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
				options.getToolCallbacks().forEach(tc -> this.advertisedTools.add(tc.getToolDefinition().name()));
			}
			AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", this.toolName,
					this.toolArguments);
			return response(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());
		}

		private static ChatResponse response(AssistantMessage message) {
			return new ChatResponse(List.of(new Generation(message)));
		}

	}

}
