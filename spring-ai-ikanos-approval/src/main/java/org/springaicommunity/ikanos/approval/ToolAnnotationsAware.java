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

/**
 * Implemented by a {@code ToolCallback} that can report the MCP {@code ToolAnnotations} of the
 * tool it executes. Only callbacks implementing this interface are considered by
 * {@link ToolApprovalGate}; any other callback is passed through untouched.
 */
public interface ToolAnnotationsAware {

	/**
	 * The MCP annotations of the tool.
	 * @return the annotations, or {@code null} if the tool declares none (MCP defaults then
	 * apply: not read-only, destructive)
	 */
	McpSchema.@Nullable ToolAnnotations getToolAnnotations();

}
