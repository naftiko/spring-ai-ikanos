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

/**
 * Thrown in {@link ToolApprovalMode#REQUIRE_HANDLER} mode when tools need approval but no
 * {@link ToolApprovalHandler} is available. Raised at configuration time, never per call.
 */
public class ToolApprovalRequiredException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final List<String> toolNames;

	public ToolApprovalRequiredException(List<String> toolNames) {
		super("No ToolApprovalHandler for tools that may be destructive: " + toolNames
				+ ". MCP treats a tool as destructive unless it is annotated readOnlyHint=true or "
				+ "destructiveHint=false.");
		this.toolNames = List.copyOf(toolNames);
	}

	/**
	 * @return the names of the tools that need approval, as shown to the model
	 */
	public List<String> getToolNames() {
		return this.toolNames;
	}

}
