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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.Assert;

/**
 * Makes tool callbacks that wrap an MCP tool {@link ToolAnnotationsAware}, so that
 * {@link ToolApprovalGate} can gate them too, for example the {@code SyncMcpToolCallback}
 * and {@code AsyncMcpToolCallback} that Spring AI's MCP client creates for remote tools.
 *
 * <p>
 * A callback is recognized when it has a public no-argument {@code getToolAnnotations()}
 * method returning {@link McpSchema.ToolAnnotations}, or else a {@code getTool()} method
 * returning {@link McpSchema.Tool}. Spring AI's MCP callbacks gain
 * {@code getToolAnnotations()} in the upstream change proposed by this project (Track B);
 * on Spring AI versions without it, {@link #adapt(ToolCallback)} returns empty and
 * {@link #adaptAll(List)} leaves such callbacks untouched, so they are not gated. The
 * lookup is reflective so this module does not depend on {@code spring-ai-mcp} or on a
 * particular Spring AI version.
 */
public final class McpToolAnnotations {

	private McpToolAnnotations() {
	}

	/**
	 * Wrap a callback so it reports the annotations of its MCP tool.
	 * @param callback the callback
	 * @return the callback itself if it is already {@link ToolAnnotationsAware}, an adapter
	 * if it exposes its MCP tool annotations, otherwise empty
	 */
	public static Optional<ToolCallback> adapt(ToolCallback callback) {
		Assert.notNull(callback, "callback must not be null");
		if (callback instanceof ToolAnnotationsAware) {
			return Optional.of(callback);
		}
		Method accessor = accessor(callback.getClass());
		if (accessor == null) {
			return Optional.empty();
		}
		try {
			Object value = accessor.invoke(callback);
			McpSchema.@Nullable ToolAnnotations annotations = (value instanceof McpSchema.Tool tool)
					? tool.annotations() : (McpSchema.ToolAnnotations) value;
			return Optional.of(new AnnotatedToolCallback(callback, annotations));
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Adapt every callback that can be adapted, leaving the others as they are. Order is
	 * preserved. Feed the result to {@link ToolApprovalGate#apply}.
	 * @param callbacks the callbacks
	 * @return the callbacks, MCP ones made {@link ToolAnnotationsAware}
	 */
	public static List<ToolCallback> adaptAll(List<? extends ToolCallback> callbacks) {
		Assert.notNull(callbacks, "callbacks must not be null");
		List<ToolCallback> result = new ArrayList<>(callbacks.size());
		for (ToolCallback callback : callbacks) {
			result.add(adapt(callback).orElse(callback));
		}
		return List.copyOf(result);
	}

	private static @Nullable Method accessor(Class<?> type) {
		Method method = publicMethod(type, "getToolAnnotations", McpSchema.ToolAnnotations.class);
		return (method != null) ? method : publicMethod(type, "getTool", McpSchema.Tool.class);
	}

	private static @Nullable Method publicMethod(Class<?> type, String name, Class<?> returnType) {
		try {
			Method method = type.getMethod(name);
			if (Modifier.isStatic(method.getModifiers()) || !returnType.isAssignableFrom(method.getReturnType())) {
				return null;
			}
			return method;
		}
		catch (NoSuchMethodException ex) {
			return null;
		}
	}

	/**
	 * Delegates everything to the wrapped callback and adds its MCP annotations.
	 */
	static final class AnnotatedToolCallback implements ToolCallback, ToolAnnotationsAware {

		private final ToolCallback delegate;

		private final McpSchema.@Nullable ToolAnnotations annotations;

		AnnotatedToolCallback(ToolCallback delegate, McpSchema.@Nullable ToolAnnotations annotations) {
			this.delegate = delegate;
			this.annotations = annotations;
		}

		ToolCallback getDelegate() {
			return this.delegate;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.delegate.getToolDefinition();
		}

		@Override
		public ToolMetadata getToolMetadata() {
			return this.delegate.getToolMetadata();
		}

		@Override
		public McpSchema.@Nullable ToolAnnotations getToolAnnotations() {
			return this.annotations;
		}

		@Override
		public String call(String toolInput) {
			return this.delegate.call(toolInput);
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			return this.delegate.call(toolInput, toolContext);
		}

		@Override
		public String toString() {
			return "McpToolAnnotations[" + this.delegate + "]";
		}

	}

}
