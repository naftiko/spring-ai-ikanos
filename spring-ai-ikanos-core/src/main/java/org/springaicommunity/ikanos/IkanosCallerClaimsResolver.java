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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;

/**
 * Finds the validated claims of the user a tool call is made for: the end user of the
 * application, already authenticated by it (for example by a Spring Security resource server).
 *
 * <p>
 * The claims drive the capability's {@code authorization} requirements (scopes, claims) and its
 * {@code {{auth.claims...}}} templates, as the claims of a validated access token do on a remote
 * Ikanos MCP server. Ikanos does not validate them: returning claims asserts that the user holds
 * them, so they must come from the application's own authentication, never from the model.
 *
 * @see ToolAuthorizationMode
 */
@FunctionalInterface
public interface IkanosCallerClaimsResolver {

	/**
	 * The {@link ToolContext} key read by {@link #fromToolContext()}: a {@code Map<String, Object>}
	 * of claims.
	 */
	String TOOL_CONTEXT_KEY = "ikanos.caller.claims";

	/**
	 * The claims of the user this call is made for.
	 * @param toolContext the call's tool context, if any
	 * @return the validated claims by claim name, or {@code null} when there is no authenticated user
	 */
	@Nullable Map<String, Object> resolve(@Nullable ToolContext toolContext);

	/**
	 * Read the claims the application put in the {@link ToolContext} under
	 * {@link #TOOL_CONTEXT_KEY}, for example
	 * {@code chatClient.prompt().toolContext(Map.of(TOOL_CONTEXT_KEY, jwt.getClaims()))}. Works
	 * whatever thread runs the tool.
	 * @return the resolver
	 */
	@SuppressWarnings("unchecked")
	static IkanosCallerClaimsResolver fromToolContext() {
		return toolContext -> (toolContext != null
				&& toolContext.getContext().get(TOOL_CONTEXT_KEY) instanceof Map<?, ?> claims)
						? (Map<String, Object>) claims : null;
	}

}
