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

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.Assert;

/**
 * Gates the callbacks of any {@link ToolCallbackProvider} with {@link ToolApprovalGate}.
 *
 * <p>
 * The delegate's callbacks are read once, at construction, so configuration errors
 * ({@link ToolApprovalRequiredException}) surface immediately. Only callbacks implementing
 * {@link ToolAnnotationsAware} can be gated.
 */
public final class ApprovalGatedToolCallbackProvider implements ToolCallbackProvider {

	private final ToolCallback[] toolCallbacks;

	public ApprovalGatedToolCallbackProvider(ToolCallbackProvider delegate, ToolApprovalMode mode,
			@Nullable ToolApprovalHandler handler) {
		Assert.notNull(delegate, "delegate must not be null");
		this.toolCallbacks = ToolApprovalGate.apply(List.of(delegate.getToolCallbacks()), mode, handler)
			.toArray(ToolCallback[]::new);
	}

	@Override
	public ToolCallback[] getToolCallbacks() {
		return this.toolCallbacks.clone();
	}

}
