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

import io.ikanos.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.ikanos.approval.ToolApprovalMode;

import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In-JVM there is no HTTP request, so no OAuth2 caller: with no caller claims resolver, a tool
 * that reads the caller's claims (naftiko/ikanos#998, from {@code 1.0.0-beta10}) fails closed.
 * Authorization requirements are covered by {@code ToolAuthorizationIntegrationTests}.
 */
class AuthorizationInJvmIntegrationTests {

	private RecordingUpstream upstream;

	private IkanosToolCallbackProvider provider;

	@BeforeEach
	void setUp() throws Exception {
		this.upstream = new RecordingUpstream();
		Capability capability = RecordingUpstream.load("""
				ikanos: "1.0.0-beta10"
				info:
				  display: Authorization
				  description: spring-ai-ikanos test capability
				capability:
				  consumes:
				  - namespace: registry
				    type: http
				    baseUri: "http://localhost:%1$d"
				    resources:
				      ship:
				        path: /ship
				        operations:
				          get-ship:
				            method: GET
				            inputParameters:
				              owner:
				                in: query
				  exposes:
				  - type: mcp
				    address: localhost
				    port: 0
				    namespace: authz
				    description: Authorization tools
				    authentication:
				      type: oauth2
				      authorizationServerUri: "http://localhost:1"
				      resource: "http://localhost/mcp"
				      scopes: ["fleet.read", "fleet.admin"]
				    tools:
				      my-ship:
				        description: Read the caller's own ship
				        hints:
				          readOnly: true
				        call: registry.get-ship
				        with:
				          owner: "{{auth.claims.sub}}"
				        outputParameters:
				        - type: boolean
				          mapping: "$.ok"
				""".formatted(this.upstream.port));
		this.provider = IkanosToolCallbackProvider.builder()
			.approvalMode(ToolApprovalMode.NONE)
			.capabilities(capability)
			.build();
	}

	@AfterEach
	void tearDown() {
		this.upstream.close();
	}

	@Test
	void toolReadingCallerClaimsShouldFailWithNoCaller() {
		assertThatThrownBy(() -> RecordingUpstream.tool(this.provider, "my-ship").call("{}"))
			.isInstanceOf(ToolExecutionException.class)
			.hasMessageContaining("does not carry auth.claims.sub");
	}

	@Test
	void toolReadingCallerClaimsShouldNotReachTheUpstreamWithNoCaller() {
		try {
			RecordingUpstream.tool(this.provider, "my-ship").call("{}");
		}
		catch (ToolExecutionException expected) {
			// asserted in toolReadingCallerClaimsShouldFailWithNoCaller
		}

		assertThat(this.upstream.requests).isEmpty();
	}

}
