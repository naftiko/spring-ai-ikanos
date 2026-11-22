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

import java.nio.file.Files;
import java.nio.file.Path;

import io.ikanos.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.ikanos.approval.ToolApprovalMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bindings declared under {@code capability.binds} (naftiko/ikanos#955, from
 * {@code 1.0.0-beta10}) reach a bridged tool's consumed call, and a capability that still declares
 * them at the document root fails to load.
 */
class CapabilityBindsInJvmIntegrationTests {

	private RecordingUpstream upstream;

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() throws Exception {
		this.upstream = new RecordingUpstream();
	}

	@AfterEach
	void tearDown() {
		this.upstream.close();
	}

	@Test
	void bindingUnderCapabilityBindsReachesTheConsumedCall() throws Exception {
		Path secrets = Files.writeString(this.dir.resolve("secrets.yaml"), "upstream-token: \"test-token\"\n");
		Capability capability = RecordingUpstream.load(yaml("""
				capability:
				  binds:
				  - namespace: upstream-env
				    description: Upstream credentials
				    location: "%s"
				    keys:
				      UPSTREAM_TOKEN: "upstream-token"
				""".formatted(secrets.toUri())));
		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.approvalMode(ToolApprovalMode.NONE)
			.capabilities(capability)
			.build();

		RecordingUpstream.tool(provider, "get-status").call("{}");

		assertThat(this.upstream.requests).singleElement()
			.extracting(RecordingUpstream.Request::authorization)
			.isEqualTo("Bearer test-token");
	}

	@Test
	void rootBindsFailsToLoadNamingTheMigration() {
		String yaml = """
				binds:
				- namespace: upstream-env
				  description: Upstream credentials
				  keys:
				    UPSTREAM_TOKEN: "upstream-token"
				""" + yaml("capability:\n");

		assertThatThrownBy(() -> RecordingUpstream.load(yaml)).hasStackTraceContaining("capability.binds");
	}

	private String yaml(String capabilityHead) {
		return """
				ikanos: "1.0.0-beta10"
				info:
				  display: Binds
				  description: spring-ai-ikanos engine-next test capability
				%s  consumes:
				  - namespace: upstream
				    type: http
				    baseUri: "http://localhost:%d"
				    authentication:
				      type: bearer
				      token: "{{UPSTREAM_TOKEN}}"
				    resources:
				      status:
				        path: /status
				        operations:
				          get-status:
				            method: GET
				  exposes:
				  - type: mcp
				    address: localhost
				    port: 0
				    namespace: binds
				    description: Binds tools
				    tools:
				      get-status:
				        description: Read the upstream status
				        hints:
				          readOnly: true
				        call: upstream.get-status
				        outputParameters:
				        - type: boolean
				          mapping: "$.ok"
				""".formatted(capabilityHead, this.upstream.port);
	}

}
