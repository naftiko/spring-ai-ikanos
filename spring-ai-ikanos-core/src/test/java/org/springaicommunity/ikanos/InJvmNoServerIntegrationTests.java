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

import java.util.List;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 — in a Spring context, the bridged tool reaches the upstream while the capability's MCP
 * server is never started: nothing listens on the declared MCP port.
 */
class InJvmNoServerIntegrationTests {

	private FleetFixture fixture;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();
	}

	@AfterEach
	void tearDown() {
		this.fixture.close();
	}

	@Test
	void toolRunsInJvmWithNoListeningSocket() throws Exception {
		Capability capability = this.fixture.capability("fleet");

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(IkanosCapabilityLifecycle.class, () -> new IkanosCapabilityLifecycle(List.of(capability)));
			context.registerBean(IkanosToolCallbackProvider.class,
					() -> IkanosToolCallbackProvider.builder().capabilities(capability).build());
			context.refresh();

			assertThat(context.getBean(IkanosCapabilityLifecycle.class).isRunning()).isTrue();

			ToolCallback listShips = context.getBean(IkanosToolCallbackProvider.class).getToolCallbacks()[0];
			String result = listShips.call("{\"status\":\"active\"}");

			assertThat(result).contains("IMO-1").contains("Sea Eagle");
			assertThat(this.fixture.requests).containsExactly("GET /ships?status=active");

			McpServerAdapter mcp = (McpServerAdapter) capability.getServerAdapters().get(0);
			assertThat(mcp.getServer().isStarted()).as("MCP server started").isFalse();
			assertThat(FleetFixture.isListening(this.fixture.mcpPort)).as("MCP port listening").isFalse();
		}
	}

	@Test
	void lifecycleStopsClientAdaptersOnClose() throws Exception {
		Capability capability = this.fixture.capability("fleet");
		IkanosCapabilityLifecycle lifecycle;

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(IkanosCapabilityLifecycle.class, () -> new IkanosCapabilityLifecycle(List.of(capability)));
			context.refresh();
			lifecycle = context.getBean(IkanosCapabilityLifecycle.class);
			assertThat(lifecycle.isRunning()).isTrue();
		}
		assertThat(lifecycle.isRunning()).isFalse();
	}

}
