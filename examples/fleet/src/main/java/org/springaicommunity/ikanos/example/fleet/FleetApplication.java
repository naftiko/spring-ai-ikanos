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

import org.springaicommunity.ikanos.IkanosToolCallbackProvider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;


/**
 * Example: the tools of {@code capabilities/fleet.yml} become in-JVM Spring AI tools.
 *
 * <p>
 * Add any Spring AI model starter (e.g. {@code spring-ai-starter-model-openai}) to get a
 * {@link ChatModel} bean; the {@link ChatClient} below then calls the fleet tools in the same
 * JVM, with no MCP server running.
 */
@SpringBootApplication
public class FleetApplication {

	public static void main(String[] args) {
		SpringApplication.run(FleetApplication.class, args);
	}

	/**
	 * Tools are attached explicitly, never added to every ChatClient by default.
	 */
	@Bean
	FleetAssistant fleetAssistant(ChatModel chatModel, IkanosToolCallbackProvider ikanosTools) {
		return new FleetAssistant(ChatClient.builder(chatModel).defaultTools(ikanosTools).build());
	}

	/**
	 * A tiny facade over the chat client.
	 */
	public record FleetAssistant(ChatClient chatClient) {

		public String ask(String question) {
			return this.chatClient.prompt().user(question).call().content();
		}

	}

}
