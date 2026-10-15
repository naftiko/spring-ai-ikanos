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

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.JsonHelper;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A Spring AI {@link ToolCallback} that executes an Ikanos capability tool in the same JVM, with
 * no MCP server, port or transport involved.
 *
 * <p>
 * The tool definition is produced by {@link McpToolUtils#createToolDefinition}, and a successful
 * result is serialized exactly like {@code SyncMcpToolCallback} does ({@code toJson(content)}),
 * so the model sees the same tool whether it is bridged in-JVM or consumed from a remote Ikanos
 * MCP server.
 *
 * <p>
 * Error handling:
 * <ul>
 * <li>a result with {@code isError = true} becomes a {@link ToolExecutionException} carrying the
 * capability-authored content, as in {@code SyncMcpToolCallback};</li>
 * <li>an unexpected engine exception is logged and replaced by a generic message, so upstream
 * URLs or resolved configuration never reach the model.</li>
 * </ul>
 *
 * <p>
 * The {@link ToolContext} is currently ignored: the Ikanos engine has no per-call context
 * channel yet.
 */
public final class IkanosToolCallback implements ToolCallback {

	private static final Log logger = LogFactory.getLog(IkanosToolCallback.class);

	private static final JsonHelper json = new JsonHelper();

	private final ToolInvoker invoker;

	private final McpSchema.Tool tool;

	private final ToolDefinition definition;

	private final ToolMetadata metadata = ToolMetadata.builder().build();

	/**
	 * Create a callback.
	 * @param invoker executes the tool in-heap
	 * @param tool the Ikanos tool as advertised by the capability
	 * @param exposedName the name shown to the model (possibly prefixed); the engine is always
	 * called with {@code tool.name()}
	 */
	public IkanosToolCallback(ToolInvoker invoker, McpSchema.Tool tool, String exposedName) {
		Assert.notNull(invoker, "invoker must not be null");
		Assert.notNull(tool, "tool must not be null");
		Assert.hasText(exposedName, "exposedName must not be empty");
		this.invoker = invoker;
		this.tool = tool;
		this.definition = McpToolUtils.createToolDefinition(exposedName, tool);
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.definition;
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.metadata;
	}

	/**
	 * The original Ikanos tool, including its MCP {@code ToolAnnotations}.
	 * @return the wrapped tool
	 */
	public McpSchema.Tool getTool() {
		return this.tool;
	}

	/**
	 * The MCP annotations authored in the capability {@code hints} block, if any.
	 * @return the annotations or {@code null}
	 */
	public McpSchema.@Nullable ToolAnnotations getToolAnnotations() {
		return this.tool.annotations();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		Map<String, Object> arguments = StringUtils.hasText(toolInput) ? json.fromJsonToMap(toolInput) : Map.of();

		McpSchema.CallToolResult result;
		try {
			result = this.invoker.invoke(this.tool.name(), arguments);
		}
		catch (Exception ex) {
			logger.warn("Ikanos tool '" + this.tool.name() + "' failed", ex);
			throw new ToolExecutionException(this.definition,
					new IllegalStateException("Tool '" + this.tool.name() + "' failed"));
		}

		if (result == null) {
			throw new ToolExecutionException(this.definition,
					new IllegalStateException("Tool '" + this.tool.name() + "' returned no result"));
		}
		if (Boolean.TRUE.equals(result.isError())) {
			throw new ToolExecutionException(this.definition,
					new IllegalStateException("Error calling tool: " + result.content()));
		}
		return json.toJson(result.content());
	}

	@Override
	public String toString() {
		return "IkanosToolCallback[" + this.definition.name() + " -> " + this.tool.name() + "]";
	}

}
