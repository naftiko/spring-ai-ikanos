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

import java.util.Arrays;
import java.util.List;

import io.ikanos.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * U2 — catalog assembly from real (built, unstarted) capabilities.
 */
class IkanosToolCallbackProviderTests {

	private FleetFixture fixture;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();
	}

	@AfterEach
	void tearDown() {
		this.fixture.close();
	}

	private static List<String> names(IkanosToolCallbackProvider provider) {
		return Arrays.stream(provider.getToolCallbacks()).map(tc -> tc.getToolDefinition().name()).toList();
	}

	@Test
	void bridgesEveryMcpTool() throws Exception {
		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.capabilities(this.fixture.capability("fleet"))
			.build();

		assertThat(names(provider)).containsExactly("list-ships", "decommission-ship");
		assertThat(provider.getToolCallbacks()).allMatch(IkanosToolCallback.class::isInstance);
	}

	@Test
	void destructiveHintSurvivesTheBridge() throws Exception {
		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.capabilities(this.fixture.capability("fleet"))
			.build();

		ToolCallback[] callbacks = provider.getToolCallbacks();
		assertThat(((IkanosToolCallback) callbacks[0]).getToolAnnotations().readOnlyHint()).isTrue();
		assertThat(((IkanosToolCallback) callbacks[1]).getToolAnnotations().destructiveHint()).isTrue();
	}

	@Test
	void duplicateNamesAcrossCapabilitiesFailFast() throws Exception {
		Capability a = this.fixture.capability("fleet");
		Capability b = this.fixture.capability("fleet2");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> IkanosToolCallbackProvider.builder().capabilities(a, b).build())
			.withMessageContaining("Duplicate Ikanos tool name 'list-ships'");
	}

	@Test
	void namespaceFilterSelectsOneExpose() throws Exception {
		Capability a = this.fixture.capability("fleet");
		Capability b = this.fixture.capability("fleet2");

		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.capabilities(a, b)
			.exposeNamespaces(List.of("fleet2"))
			.build();

		assertThat(names(provider)).containsExactly("list-ships", "decommission-ship");
	}

	@Test
	void unknownNamespaceFailsFast() throws Exception {
		Capability a = this.fixture.capability("fleet");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> IkanosToolCallbackProvider.builder()
				.capabilities(a)
				.exposeNamespaces(List.of("nope"))
				.build())
			.withMessageContaining("nope");
	}

	@Test
	void prefixAppliesToExposedNameOnly() throws Exception {
		IkanosToolCallbackProvider provider = IkanosToolCallbackProvider.builder()
			.capabilities(this.fixture.capability("fleet"))
			.toolNamePrefix("fleet_")
			.build();

		assertThat(names(provider)).containsExactly("fleet_list-ships", "fleet_decommission-ship");
		assertThat(((IkanosToolCallback) provider.getToolCallbacks()[0]).getTool().name()).isEqualTo("list-ships");
	}

}
