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
import org.springaicommunity.ikanos.approval.ToolApprovalDecision;
import org.springaicommunity.ikanos.approval.ToolApprovalHandler;
import org.springaicommunity.ikanos.approval.ToolApprovalRequest;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4 — the approval gate composes with Spring AI's {@link ToolSearchToolCallingAdvisor}.
 *
 * <p>
 * {@code DefaultChatClient} allows exactly one {@code ToolAdvisor}. Because the gate is a
 * {@code ToolCallback} decorator, not an advisor, the tool search advisor drives the loop, hides
 * the bridged tools until the model searches for them, and the gate still reviews the destructive
 * tool when it is finally called.
 */
class ApprovalWithToolSearchIntegrationTests {

	private static final String SEARCH_TOOL = "toolSearchTool";

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

	private IkanosToolCallbackProvider tools(ToolApprovalHandler handler) throws Exception {
		Capability capability = this.fixture.capability("fleet");
		this.lifecycle = new IkanosCapabilityLifecycle(List.of(capability));
		this.lifecycle.start();
		return IkanosToolCallbackProvider.builder().capabilities(capability).approvalHandler(handler).build();
	}

	private static String ask(ChatModel model, IkanosToolCallbackProvider tools) {
		ToolSearchToolCallingAdvisor toolSearch = ToolSearchToolCallingAdvisor.builder()
			.toolIndex(new RegexToolIndex())
			.build();
		return ChatClient.builder(model)
			.defaultAdvisors(toolSearch)
			.build()
			.prompt()
			.user("Decommission the current ship")
			.tools(tools)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "e4-session"))
			.call()
			.content();
	}

	@Test
	void approvedDestructiveToolRunsThroughToolSearch() throws Exception {
		List<ToolApprovalRequest> reviewed = new ArrayList<>();
		IkanosToolCallbackProvider tools = tools(request -> {
			reviewed.add(request);
			return ToolApprovalDecision.approve();
		});
		SearchThenCallModel model = new SearchThenCallModel("decommission", "decommission-ship");

		String answer = ask(model, tools);

		// tool search hid the bridged tools until the model searched for them
		assertThat(model.advertised.get(0)).containsExactly(SEARCH_TOOL);
		assertThat(model.advertised.get(1)).contains("decommission-ship");
		// the gate reviewed the call, then the engine reached the upstream
		assertThat(reviewed).singleElement().satisfies(request -> {
			assertThat(request.toolName()).isEqualTo("decommission-ship");
			assertThat(request.annotations().destructiveHint()).isTrue();
		});
		assertThat(this.fixture.requests).containsExactly("DELETE /ships");
		assertThat(answer).startsWith("Final:");
	}

	@Test
	void deniedDestructiveToolNeverReachesUpstreamThroughToolSearch() throws Exception {
		IkanosToolCallbackProvider tools = tools(request -> ToolApprovalDecision.deny("change freeze"));
		SearchThenCallModel model = new SearchThenCallModel("decommission", "decommission-ship");

		String answer = ask(model, tools);

		assertThat(this.fixture.requests).isEmpty();
		assertThat(model.lastToolResponse).isEqualTo("Denied: change freeze");
		assertThat(answer).isEqualTo("Final: Denied: change freeze");
	}

	/**
	 * Scripted model: (1) call the tool search tool, (2) call the discovered tool, (3) answer
	 * with the last tool response. Records the tool names advertised on each call.
	 */
	static final class SearchThenCallModel implements ChatModel {

		final List<List<String>> advertised = new ArrayList<>();

		String lastToolResponse;

		private final String query;

		private final String toolName;

		SearchThenCallModel(String query, String toolName) {
			this.query = query;
			this.toolName = toolName;
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			List<String> names = new ArrayList<>();
			if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
				options.getToolCallbacks().forEach(tc -> names.add(tc.getToolDefinition().name()));
			}
			this.advertised.add(names);

			List<Message> messages = prompt.getInstructions();
			Message last = messages.get(messages.size() - 1);
			if (last instanceof ToolResponseMessage response) {
				ToolResponseMessage.ToolResponse tr = response.getResponses().get(0);
				this.lastToolResponse = tr.responseData();
				if (SEARCH_TOOL.equals(tr.name())) {
					return toolCall("call-2", this.toolName, "{}");
				}
				return answer("Final: " + this.lastToolResponse);
			}
			return toolCall("call-1", SEARCH_TOOL, "{\"query\":\"" + this.query + "\"}");
		}

		private static ChatResponse toolCall(String id, String name, String arguments) {
			AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(id, "function", name, arguments);
			return new ChatResponse(
					List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
		}

		private static ChatResponse answer(String text) {
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
		}

	}

}
