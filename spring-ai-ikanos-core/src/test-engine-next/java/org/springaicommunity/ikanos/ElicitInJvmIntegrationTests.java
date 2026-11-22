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

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tool whose flow reaches an {@code elicit} step (naftiko/ikanos#921, from
 * {@code 1.0.0-beta10}) has no MCP client to ask when it runs in-JVM: the call fails, and the
 * steps after the question never run.
 */
class ElicitInJvmIntegrationTests {

	private RecordingUpstream upstream;

	private ToolCallback confirmThenDecommission;

	@BeforeEach
	void setUp() throws Exception {
		this.upstream = new RecordingUpstream();
		Capability capability = RecordingUpstream.load("""
				ikanos: "1.0.0-beta10"
				info:
				  display: Elicit
				  description: spring-ai-ikanos engine-next test capability
				capability:
				  consumes:
				  - namespace: registry
				    type: http
				    baseUri: "http://localhost:%d"
				    resources:
				      ship:
				        path: /ship
				        operations:
				          decommission:
				            method: DELETE
				  aggregates:
				  - namespace: fleet
				    flows:
				      confirm-then-decommission:
				        description: Ask for confirmation, then decommission the ship.
				        steps:
				          confirm:
				            type: elicit
				            message: Decommission the ship?
				            fields:
				              confirmed:
				                type: boolean
				                description: Whether the ship is decommissioned
				          decommission:
				            type: call
				            call: registry.decommission
				  exposes:
				  - type: mcp
				    address: localhost
				    port: 0
				    namespace: elicit
				    description: Elicit tools
				    tools:
				      confirm-then-decommission:
				        description: Decommission the ship once confirmed.
				        ref: fleet.confirm-then-decommission
				""".formatted(this.upstream.port));
		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.approvalMode(ToolApprovalMode.NONE)
			.capabilities(capability)
			.build();
		this.confirmThenDecommission = RecordingUpstream.tool(provider, "confirm-then-decommission");
	}

	@AfterEach
	void tearDown() {
		this.upstream.close();
	}

	@Test
	void callShouldFailWhenTheFlowAsksForInput() {
		assertThatThrownBy(() -> this.confirmThenDecommission.call("{}")).isInstanceOf(ToolExecutionException.class);
	}

	@Test
	void stepsAfterTheQuestionShouldNotRunWhenTheFlowAsksForInput() {
		try {
			this.confirmThenDecommission.call("{}");
		}
		catch (ToolExecutionException expected) {
			// asserted in callShouldFailWhenTheFlowAsksForInput
		}

		assertThat(this.upstream.requests).isEmpty();
	}

}
