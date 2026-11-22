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

package org.springaicommunity.ikanos.autoconfigure;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.ikanos.IkanosCallerClaimsResolver;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * Finds the caller's claims in the {@link ToolContext} first
 * ({@link IkanosCallerClaimsResolver#TOOL_CONTEXT_KEY}), else in the Spring Security context:
 * the attributes of the access token a resource server authenticated (a JWT's claims, or an
 * opaque token's introspected attributes).
 *
 * <p>
 * The security context is bound to the request thread. It is found when the tool runs on that
 * thread, as with a synchronous {@code ChatClient} call; when tools may run on another thread,
 * pass the claims in the tool context instead.
 */
public final class SecurityContextCallerClaimsResolver implements IkanosCallerClaimsResolver {

	private final IkanosCallerClaimsResolver toolContext = IkanosCallerClaimsResolver.fromToolContext();

	@Override
	public @Nullable Map<String, Object> resolve(@Nullable ToolContext toolContext) {
		Map<String, Object> claims = this.toolContext.resolve(toolContext);
		if (claims != null) {
			return claims;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token && token.isAuthenticated())
				? token.getTokenAttributes() : null;
	}

}
