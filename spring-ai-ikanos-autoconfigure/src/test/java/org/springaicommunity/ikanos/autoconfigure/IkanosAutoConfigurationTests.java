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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.ikanos.FleetFixture;
import org.springaicommunity.ikanos.IkanosCapabilityLifecycle;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration contract: properties, conditions, failure modes and an end-to-end call.
 */
class IkanosAutoConfigurationTests {

	@TempDir
	Path dir;

	private FleetFixture fixture;

	private ApplicationContextRunner runner;

	private String location;

	@BeforeEach
	void setUp() throws Exception {
		this.fixture = new FleetFixture();
		Path yaml = this.dir.resolve("fleet.yml");
		Files.writeString(yaml, this.fixture.yaml("fleet"));
		this.location = "file:" + this.dir.toAbsolutePath().toString().replace('\\', '/') + "/*.yml";
		this.runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(IkanosAutoConfiguration.class));
	}

	@AfterEach
	void tearDown() {
		this.fixture.close();
	}

	private static String[] names(IkanosToolCallbackProvider provider) {
		return Arrays.stream(provider.getToolCallbacks()).map(tc -> tc.getToolDefinition().name()).toArray(String[]::new);
	}

	@Test
	void loadsCapabilitiesFromPatternAndCallsToolInJvm() {
		this.runner.withPropertyValues("spring.ai.ikanos.capabilities=" + this.location).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(IkanosCapabilityLifecycle.class).isRunning()).isTrue();

			IkanosToolCallbackProvider provider = context.getBean(IkanosToolCallbackProvider.class);
			assertThat(names(provider)).containsExactly("list-ships", "decommission-ship");

			ToolCallback listShips = provider.getToolCallbacks()[0];
			assertThat(listShips.call("{\"status\":\"active\"}")).contains("Sea Eagle");
			assertThat(this.fixture.requests).containsExactly("GET /ships?status=active");
			assertThat(FleetFixture.isListening(this.fixture.mcpPort)).isFalse();
		});
	}

	@Test
	void appliesPrefixAndExposeFilter() {
		this.runner
			.withPropertyValues("spring.ai.ikanos.capabilities=" + this.location, "spring.ai.ikanos.expose=fleet",
					"spring.ai.ikanos.tool-name-prefix=fleet_")
			.run(context -> assertThat(names(context.getBean(IkanosToolCallbackProvider.class)))
				.containsExactly("fleet_list-ships", "fleet_decommission-ship"));
	}

	@Test
	void disabledBacksOff() {
		this.runner
			.withPropertyValues("spring.ai.ikanos.enabled=false", "spring.ai.ikanos.capabilities=" + this.location)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(IkanosToolCallbackProvider.class);
				assertThat(context).doesNotHaveBean(IkanosCapabilityLifecycle.class);
			});
	}

	@Test
	void missingCapabilitiesPropertyFailsWithHint() {
		this.runner.run(context -> assertThat(context).hasFailed()
			.getFailure()
			.rootCause()
			.hasMessageContaining("spring.ai.ikanos.capabilities"));
	}

	@Test
	void locationMatchingNothingFails() {
		this.runner.withPropertyValues("spring.ai.ikanos.capabilities=classpath:nope/*.yml")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("No Ikanos capability found at 'classpath:nope/*.yml'"));
	}

	@Test
	void unknownExposeNamespaceFails() {
		this.runner
			.withPropertyValues("spring.ai.ikanos.capabilities=" + this.location, "spring.ai.ikanos.expose=nope")
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("nope"));
	}

	@Test
	void userProviderTakesPrecedence() {
		this.runner.withPropertyValues("spring.ai.ikanos.capabilities=" + this.location)
			.withBean(IkanosToolCallbackProvider.class, () -> IkanosToolCallbackProvider.builder().build())
			.run(context -> assertThat(context.getBean(IkanosToolCallbackProvider.class).getToolCallbacks()).isEmpty());
	}

}
