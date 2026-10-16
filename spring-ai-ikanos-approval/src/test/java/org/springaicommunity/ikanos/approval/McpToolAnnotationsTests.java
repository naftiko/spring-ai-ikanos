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

package org.springaicommunity.ikanos.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track B — remote MCP tool callbacks that expose {@code getTool()} (as proposed for
 * Spring AI's {@code SyncMcpToolCallback}/{@code AsyncMcpToolCallback}) can be gated.
 */
class McpToolAnnotationsTests {

	private static McpSchema.Tool tool(String name, McpSchema.@Nullable ToolAnnotations annotations) {
		return McpSchema.Tool.builder(name, Map.of()).annotations(annotations).build();
	}

	@Test
	void recognizesACallbackExposingItsMcpTool() {
		McpSchema.Tool tool = tool("deleteFile", McpSchema.ToolAnnotations.builder().destructiveHint(true).build());
		RemoteMcpTool remote = new RemoteMcpTool(tool);

		assertThat(McpToolAnnotations.toolOf(remote)).containsSame(tool);
		assertThat(McpToolAnnotations.adapt(remote)).get()
			.isInstanceOf(ToolAnnotationsAware.class)
			.satisfies(cb -> assertThat(((ToolAnnotationsAware) cb).getToolAnnotations().destructiveHint()).isTrue());
	}

	@Test
	void ignoresCallbacksWithoutAnMcpTool() {
		ToolCallback plain = new LegacyRemoteMcpTool("deleteFile");

		assertThat(McpToolAnnotations.toolOf(plain)).isEmpty();
		assertThat(McpToolAnnotations.adapt(plain)).isEmpty();
		assertThat(McpToolAnnotations.adaptAll(List.of(plain))).containsExactly(plain);
	}

	@Test
	void ignoresAGetToolMethodOfAnotherType() {
		ToolCallback other = new OtherGetTool();

		assertThat(McpToolAnnotations.toolOf(other)).isEmpty();
	}

	@Test
	void adaptedCallbackDelegatesDefinitionAndCalls() {
		RemoteMcpTool remote = new RemoteMcpTool(tool("deleteFile", null));
		ToolCallback adapted = McpToolAnnotations.adapt(remote).orElseThrow();
		ToolContext context = new ToolContext(Map.of("tenant", "acme"));

		assertThat(adapted.getToolDefinition()).isSameAs(remote.getToolDefinition());
		assertThat(adapted.call("{\"path\":\"/tmp\"}", context)).isEqualTo("deleted");
		assertThat(remote.calls).containsExactly("{\"path\":\"/tmp\"}");
		assertThat(remote.contexts).containsExactly(context);
	}

	@Test
	void gateReviewsADestructiveRemoteToolAndLeavesReadOnlyOnesAlone() {
		RemoteMcpTool read = new RemoteMcpTool(
				tool("readFile", McpSchema.ToolAnnotations.builder().readOnlyHint(true).build()));
		RemoteMcpTool delete = new RemoteMcpTool(
				tool("deleteFile", McpSchema.ToolAnnotations.builder().destructiveHint(true).build()));
		RemoteMcpTool unannotated = new RemoteMcpTool(tool("moveFile", null));
		List<String> reviewed = new ArrayList<>();

		List<ToolCallback> gated = ToolApprovalGate.apply(
				McpToolAnnotations.adaptAll(List.of(read, delete, unannotated)), ToolApprovalMode.REQUIRE_HANDLER,
				request -> {
					reviewed.add(request.toolName());
					return ToolApprovalDecision.deny("ask first");
				});

		assertThat(gated.get(0).call("{}")).isEqualTo("deleted");
		assertThat(gated.get(1).call("{}")).isEqualTo("Denied: ask first");
		assertThat(gated.get(2).call("{}")).isEqualTo("Denied: ask first");
		assertThat(reviewed).containsExactly("deleteFile", "moveFile");
		assertThat(delete.calls).isEmpty();
		assertThat(unannotated.calls).isEmpty();
	}

	@Test
	void alreadyAwareCallbacksAreReturnedAsIs() {
		ToolApprovalGateTests.AwareTool aware = new ToolApprovalGateTests.AwareTool("x", null);

		assertThat(McpToolAnnotations.adapt(aware)).containsSame(aware);
	}

	/** Stand-in for Spring AI's SyncMcpToolCallback with the proposed getTool(). */
	public static final class RemoteMcpTool implements ToolCallback {

		final List<String> calls = new ArrayList<>();

		final List<ToolContext> contexts = new ArrayList<>();

		private final McpSchema.Tool tool;

		private final ToolDefinition definition;

		RemoteMcpTool(McpSchema.Tool tool) {
			this.tool = tool;
			this.definition = ToolDefinition.builder().name(tool.name()).description(tool.name()).inputSchema("{}").build();
		}

		public McpSchema.Tool getTool() {
			return this.tool;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.definition;
		}

		@Override
		public String call(String toolInput) {
			this.calls.add(toolInput);
			return "deleted";
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			if (toolContext != null) {
				this.contexts.add(toolContext);
			}
			return call(toolInput);
		}

	}

	/** Stand-in for Spring AI 2.0's SyncMcpToolCallback, which has no getTool(). */
	public static final class LegacyRemoteMcpTool implements ToolCallback {

		private final ToolDefinition definition;

		LegacyRemoteMcpTool(String name) {
			this.definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
		}

		public String getOriginalToolName() {
			return this.definition.name();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.definition;
		}

		@Override
		public String call(String toolInput) {
			return "ran";
		}

	}

	public static final class OtherGetTool implements ToolCallback {

		public String getTool() {
			return "not an MCP tool";
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder().name("other").description("other").inputSchema("{}").build();
		}

		@Override
		public String call(String toolInput) {
			return "";
		}

	}

}
