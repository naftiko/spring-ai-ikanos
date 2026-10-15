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

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

/**
 * Applies a {@link ToolApprovalMode} to a set of tool callbacks.
 *
 * <p>
 * Which tools need approval follows the MCP {@code ToolAnnotations} defaults:
 * {@code readOnlyHint} defaults to {@code false} and {@code destructiveHint} to {@code true}.
 * A tool is therefore gated unless it is annotated {@code readOnlyHint: true} or
 * {@code destructiveHint: false}; a tool with no annotations at all is gated. Only callbacks
 * implementing {@link ToolAnnotationsAware} are considered: others carry no MCP classification
 * and are passed through untouched.
 *
 * <p>
 * Annotations are hints authored by whoever wrote the tool. The gate enforces the declared
 * classification; it does not infer one. The MCP specification also warns against trusting
 * annotations from untrusted servers.
 */
public final class ToolApprovalGate {

	/** Reason returned to the model in {@link ToolApprovalMode#DENY_DESTRUCTIVE} mode. */
	public static final String DENY_DESTRUCTIVE_REASON = "destructive tools are disabled in this deployment";

	private static final Log logger = LogFactory.getLog(ToolApprovalGate.class);

	private ToolApprovalGate() {
	}

	/**
	 * Whether a tool with these annotations needs approval, using MCP defaults for absent
	 * hints.
	 * @param annotations the tool's annotations, possibly {@code null}
	 * @return {@code false} only for tools declared read-only or non-destructive
	 */
	public static boolean requiresApproval(McpSchema.@Nullable ToolAnnotations annotations) {
		if (annotations == null) {
			return true;
		}
		if (Boolean.TRUE.equals(annotations.readOnlyHint())) {
			return false;
		}
		return !Boolean.FALSE.equals(annotations.destructiveHint());
	}

	/**
	 * Whether this callback needs approval. Already-gated callbacks and callbacks that are not
	 * {@link ToolAnnotationsAware} do not.
	 * @param callback the callback
	 * @return whether {@link #apply} would gate it
	 */
	public static boolean requiresApproval(ToolCallback callback) {
		if (callback instanceof ApprovalGatedToolCallback) {
			return false;
		}
		return callback instanceof ToolAnnotationsAware aware && requiresApproval(aware.getToolAnnotations());
	}

	/**
	 * Gate the callbacks that need approval, according to {@code mode}. Order is preserved.
	 * @param callbacks the callbacks
	 * @param mode the approval mode
	 * @param handler the handler for {@link ToolApprovalMode#REQUIRE_HANDLER}; ignored otherwise
	 * @return the callbacks, with those needing approval wrapped in
	 * {@link ApprovalGatedToolCallback} (unless {@code mode} is {@code NONE})
	 * @throws ToolApprovalRequiredException in {@code REQUIRE_HANDLER} mode with no handler and at
	 * least one tool needing approval
	 */
	public static List<ToolCallback> apply(List<? extends ToolCallback> callbacks, ToolApprovalMode mode,
			@Nullable ToolApprovalHandler handler) {
		Assert.notNull(callbacks, "callbacks must not be null");
		Assert.notNull(mode, "mode must not be null");

		List<String> gated = callbacks.stream()
			.filter(ToolApprovalGate::requiresApproval)
			.map(callback -> callback.getToolDefinition().name())
			.toList();
		if (gated.isEmpty()) {
			return List.copyOf(callbacks);
		}

		ToolApprovalHandler effective;
		switch (mode) {
			case NONE -> {
				logger.warn("Tool approval is disabled (mode NONE): tools that may be destructive run without "
						+ "review: " + gated);
				return List.copyOf(callbacks);
			}
			case DENY_DESTRUCTIVE -> effective = request -> ToolApprovalDecision.deny(DENY_DESTRUCTIVE_REASON);
			case REQUIRE_HANDLER -> {
				if (handler == null) {
					throw new ToolApprovalRequiredException(gated);
				}
				effective = handler;
			}
			default -> throw new IllegalArgumentException("Unknown mode " + mode);
		}

		List<ToolCallback> result = new ArrayList<>(callbacks.size());
		for (ToolCallback callback : callbacks) {
			if (requiresApproval(callback)) {
				McpSchema.ToolAnnotations annotations = ((ToolAnnotationsAware) callback).getToolAnnotations();
				result.add(new ApprovalGatedToolCallback(callback, annotations, effective));
			}
			else {
				result.add(callback);
			}
		}
		return List.copyOf(result);
	}

}
