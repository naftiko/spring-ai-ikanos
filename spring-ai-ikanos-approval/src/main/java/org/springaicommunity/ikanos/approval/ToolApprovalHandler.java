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

/**
 * Decides whether a gated tool call may run.
 *
 * <p>
 * Called synchronously on the tool-calling thread, before the tool executes. A handler that
 * waits for a human blocks that thread. Throwing is treated as a tool failure (the call does
 * not run and a {@code ToolExecutionException} is raised); returning a denial lets the model
 * see the reason and choose another path.
 */
@FunctionalInterface
public interface ToolApprovalHandler {

	/**
	 * Review one tool call.
	 * @param request the tool, its annotations, the model-supplied input and the tool context
	 * @return the decision; {@code null} is treated as a denial
	 */
	ToolApprovalDecision review(ToolApprovalRequest request);

}
