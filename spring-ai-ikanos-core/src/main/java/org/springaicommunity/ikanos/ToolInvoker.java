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

/**
 * Executes one Ikanos tool in-heap. The production implementation is a method reference to
 * {@code io.ikanos.engine.exposes.mcp.ToolHandler#handleToolCall}; the interface exists so the
 * callback contract can be unit-tested without an engine.
 */
@FunctionalInterface
public interface ToolInvoker {

	/**
	 * Invoke a tool by its original (unprefixed) Ikanos name.
	 * @param toolName the tool name as declared in the capability
	 * @param arguments the tool arguments, never {@code null}
	 * @return the MCP tool result
	 * @throws Exception any engine failure
	 */
	McpSchema.CallToolResult invoke(String toolName, Map<String, Object> arguments) throws Exception;

}
