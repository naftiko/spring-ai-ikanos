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
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * U3 — approval gate semantics: which tools are gated, the three modes, and that a denial is
 * returned to the model rather than thrown.
 */
class ToolApprovalGateTests {

	private static McpSchema.ToolAnnotations hints(@Nullable Boolean readOnly, @Nullable Boolean destructive) {
		return new McpSchema.ToolAnnotations(null, readOnly, destructive, null, null, null);
	}

	@Test
	void mcpDefaultsDecideWhichToolsNeedApproval() {
		assertThat(ToolApprovalGate.requiresApproval((McpSchema.ToolAnnotations) null)).as("no annotations").isTrue();
		assertThat(ToolApprovalGate.requiresApproval(hints(null, null))).as("no hints").isTrue();
		assertThat(ToolApprovalGate.requiresApproval(hints(true, null))).as("read-only").isFalse();
		assertThat(ToolApprovalGate.requiresApproval(hints(true, true))).as("read-only wins").isFalse();
		assertThat(ToolApprovalGate.requiresApproval(hints(false, false))).as("additive").isFalse();
		assertThat(ToolApprovalGate.requiresApproval(hints(false, true))).as("destructive").isTrue();
		assertThat(ToolApprovalGate.requiresApproval(hints(false, null))).as("write, default destructive").isTrue();
	}

	@Test
	void onlyAnnotationsAwareCallbacksAreConsidered() {
		ToolCallback plain = new PlainTool("plain");
		ToolCallback destructive = new AwareTool("drop", hints(false, true));

		List<ToolCallback> result = ToolApprovalGate.apply(List.of(plain, destructive), ToolApprovalMode.REQUIRE_HANDLER,
				request -> ToolApprovalDecision.approve());

		assertThat(result.get(0)).isSameAs(plain);
		assertThat(result.get(1)).isInstanceOf(ApprovalGatedToolCallback.class);
	}

	@Test
	void requireHandlerWithoutHandlerFailsNamingTheTools() {
		List<ToolCallback> callbacks = List.of(new AwareTool("read", hints(true, null)),
				new AwareTool("drop", hints(false, true)), new AwareTool("unknown", null));

		assertThatExceptionOfType(ToolApprovalRequiredException.class)
			.isThrownBy(() -> ToolApprovalGate.apply(callbacks, ToolApprovalMode.REQUIRE_HANDLER, null))
			.satisfies(ex -> assertThat(ex.getToolNames()).containsExactly("drop", "unknown"))
			.withMessageContaining("[drop, unknown]");
	}

	@Test
	void requireHandlerWithoutHandlerIsFineWhenNothingNeedsApproval() {
		List<ToolCallback> callbacks = List.of(new AwareTool("read", hints(true, null)), new PlainTool("plain"));

		assertThat(ToolApprovalGate.apply(callbacks, ToolApprovalMode.REQUIRE_HANDLER, null))
			.containsExactlyElementsOf(callbacks);
	}

	@Test
	void approvedCallRunsTheDelegateWithInputAndContext() {
		AwareTool drop = new AwareTool("drop", hints(false, true));
		List<ToolApprovalRequest> reviewed = new ArrayList<>();
		ToolCallback gated = ToolApprovalGate.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, request -> {
			reviewed.add(request);
			return ToolApprovalDecision.approve();
		}).get(0);
		ToolContext context = new ToolContext(Map.of("tenant", "acme"));

		assertThat(gated.call("{\"id\":1}", context)).isEqualTo("ran drop");

		assertThat(drop.calls).containsExactly("{\"id\":1}");
		assertThat(reviewed).singleElement().satisfies(request -> {
			assertThat(request.toolName()).isEqualTo("drop");
			assertThat(request.input()).isEqualTo("{\"id\":1}");
			assertThat(request.annotations().destructiveHint()).isTrue();
			assertThat(request.toolContext()).isSameAs(context);
		});
	}

	@Test
	void deniedCallIsReturnedToTheModelAndDelegateNeverRuns() {
		AwareTool drop = new AwareTool("drop", hints(false, true));
		ToolCallback gated = ToolApprovalGate
			.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, request -> ToolApprovalDecision.deny("not today"))
			.get(0);

		assertThat(gated.call("{}")).isEqualTo("Denied: not today");
		assertThat(drop.calls).isEmpty();
	}

	@Test
	void nullDecisionOrBlankReasonIsADenial() {
		AwareTool drop = new AwareTool("drop", hints(false, true));

		assertThat(ToolApprovalGate.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, request -> null)
			.get(0)
			.call("{}")).startsWith(ApprovalGatedToolCallback.DENIED_PREFIX);
		assertThat(ToolApprovalGate
			.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, request -> new ToolApprovalDecision(false, " "))
			.get(0)
			.call("{}")).isEqualTo("Denied: the tool call was not approved");
		assertThat(drop.calls).isEmpty();
	}

	@Test
	void handlerFailureBecomesToolExecutionException() {
		AwareTool drop = new AwareTool("drop", hints(false, true));
		ToolCallback gated = ToolApprovalGate.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, request -> {
			throw new IllegalStateException("reviewer unavailable");
		}).get(0);

		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> gated.call("{}"))
			.satisfies(ex -> assertThat(ex.getToolDefinition().name()).isEqualTo("drop"))
			.withCauseInstanceOf(IllegalStateException.class);
		assertThat(drop.calls).isEmpty();
	}

	@Test
	void denyDestructiveDeniesWithoutAHandlerAndLeavesReadOnlyToolsAlone() {
		AwareTool read = new AwareTool("read", hints(true, null));
		AwareTool drop = new AwareTool("drop", hints(false, true));

		List<ToolCallback> result = ToolApprovalGate.apply(List.of(read, drop), ToolApprovalMode.DENY_DESTRUCTIVE,
				request -> ToolApprovalDecision.approve());

		assertThat(result.get(0)).isSameAs(read);
		assertThat(result.get(1).call("{}")).isEqualTo("Denied: " + ToolApprovalGate.DENY_DESTRUCTIVE_REASON);
		assertThat(drop.calls).isEmpty();
	}

	@Test
	void noneLeavesEveryToolUngated() {
		AwareTool drop = new AwareTool("drop", hints(false, true));

		List<ToolCallback> result = ToolApprovalGate.apply(List.of(drop), ToolApprovalMode.NONE, null);

		assertThat(result).containsExactly(drop);
	}

	@Test
	void gatedCallbackKeepsTheDelegateDefinitionAndIsNotGatedTwice() {
		AwareTool drop = new AwareTool("drop", hints(false, true));
		ToolApprovalHandler approve = request -> ToolApprovalDecision.approve();
		ToolCallback gated = ToolApprovalGate.apply(List.of(drop), ToolApprovalMode.REQUIRE_HANDLER, approve).get(0);

		assertThat(gated.getToolDefinition()).isSameAs(drop.getToolDefinition());
		assertThat(gated.getToolMetadata().returnDirect()).isEqualTo(drop.getToolMetadata().returnDirect());
		assertThat(((ToolAnnotationsAware) gated).getToolAnnotations()).isSameAs(drop.getToolAnnotations());
		assertThat(ToolApprovalGate.apply(List.of(gated), ToolApprovalMode.REQUIRE_HANDLER, approve).get(0))
			.isSameAs(gated);
	}

	@Test
	void providerGatesEagerly() {
		AwareTool drop = new AwareTool("drop", hints(false, true));

		assertThatExceptionOfType(ToolApprovalRequiredException.class)
			.isThrownBy(() -> new ApprovalGatedToolCallbackProvider(() -> new ToolCallback[] { drop },
					ToolApprovalMode.REQUIRE_HANDLER, null));
		assertThat(new ApprovalGatedToolCallbackProvider(() -> new ToolCallback[] { drop },
				ToolApprovalMode.DENY_DESTRUCTIVE, null)
			.getToolCallbacks()[0]
			.call("{}")).startsWith("Denied: ");
	}

	static class PlainTool implements ToolCallback {

		final List<String> calls = new ArrayList<>();

		private final ToolDefinition definition;

		PlainTool(String name) {
			this.definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.definition;
		}

		@Override
		public String call(String toolInput) {
			this.calls.add(toolInput);
			return "ran " + this.definition.name();
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			return call(toolInput);
		}

	}

	static final class AwareTool extends PlainTool implements ToolAnnotationsAware {

		private final McpSchema.@Nullable ToolAnnotations annotations;

		AwareTool(String name, McpSchema.@Nullable ToolAnnotations annotations) {
			super(name);
			this.annotations = annotations;
		}

		@Override
		public McpSchema.@Nullable ToolAnnotations getToolAnnotations() {
			return this.annotations;
		}

	}

}
