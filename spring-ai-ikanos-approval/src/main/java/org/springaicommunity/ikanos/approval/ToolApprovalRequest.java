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

import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A tool call awaiting approval.
 *
 * @param definition the tool definition as shown to the model
 * @param annotations the tool's MCP annotations, or {@code null} if it declares none
 * @param input the JSON input supplied by the model
 * @param toolContext the Spring AI tool context of the request, if any
 */
public record ToolApprovalRequest(ToolDefinition definition, McpSchema.@Nullable ToolAnnotations annotations,
		String input, @Nullable ToolContext toolContext) {

	/**
	 * @return the tool name as shown to the model
	 */
	public String toolName() {
		return this.definition.name();
	}

}
