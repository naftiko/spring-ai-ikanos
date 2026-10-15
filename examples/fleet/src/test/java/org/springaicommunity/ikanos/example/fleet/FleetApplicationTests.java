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

package org.springaicommunity.ikanos.example.fleet;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;
import org.springaicommunity.ikanos.example.fleet.FleetApplication.FleetAssistant;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The example boots with only the starter and a model bean, and wires the fleet tools.
 */
@SpringBootTest
class FleetApplicationTests {

	@Autowired
	IkanosToolCallbackProvider tools;

	@Autowired
	FleetAssistant assistant;

	@Test
	void contextLoadsWithFleetTools() {
		assertThat(Arrays.stream(this.tools.getToolCallbacks()).map(tc -> tc.getToolDefinition().name()))
			.containsExactly("list-ships");
		assertThat(this.assistant).isNotNull();
	}

	@TestConfiguration
	static class ModelConfig {

		@Bean
		ChatModel chatModel() {
			return mock(ChatModel.class);
		}

	}

}
