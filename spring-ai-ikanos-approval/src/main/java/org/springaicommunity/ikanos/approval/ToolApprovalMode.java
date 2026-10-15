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
 * How {@link ToolApprovalGate} treats tools that need approval (see
 * {@link ToolApprovalGate#requiresApproval}).
 */
public enum ToolApprovalMode {

	/**
	 * Gate them with the supplied {@link ToolApprovalHandler}. If there is none, fail at
	 * configuration time with {@link ToolApprovalRequiredException}, naming the tools.
	 */
	REQUIRE_HANDLER,

	/**
	 * Register them, but deny every call with a fixed reason. Any handler is ignored.
	 */
	DENY_DESTRUCTIVE,

	/**
	 * No gate. An explicit opt-out, logged at {@code WARN}.
	 */
	NONE

}
