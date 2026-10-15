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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.util.JsonHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U1 — callback contract, with the engine replaced by a {@link ToolInvoker} stub.
 */
class IkanosToolCallbackTests {

	private static McpSchema.Tool tool(String name, McpSchema.ToolAnnotations annotations) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", Map.of("status", Map.of("type", "string", "description", "Status")));
		schema.put("required", List.of("status"));
		return McpSchema.Tool.builder(name, schema).description("List ships").annotations(annotations).build();
	}

	private static McpSchema.CallToolResult text(String text, boolean error) {
		return new McpSchema.CallToolResult(List.of(McpSchema.TextContent.builder(text).build()), error, null, null);
	}

	@Test
	void definitionUsesExposedNameAndMcpSchema() {
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> text("{}", false), tool("list-ships", null),
				"fleet_list-ships");

		assertThat(callback.getToolDefinition().name()).isEqualTo("fleet_list-ships");
		assertThat(callback.getToolDefinition().description()).isEqualTo("List ships");
		Map<String, Object> schema = new JsonHelper().fromJsonToMap(callback.getToolDefinition().inputSchema());
		assertThat(schema).containsEntry("type", "object").containsKey("properties");
		assertThat(schema.get("required")).isEqualTo(List.of("status"));
		assertThat(callback.getToolMetadata().returnDirect()).isFalse();
	}

	@Test
	void callSendsOriginalNameAndParsedArguments() {
		List<String> names = new ArrayList<>();
		List<Map<String, Object>> args = new ArrayList<>();
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> {
			names.add(n);
			args.add(a);
			return text("[{\"imo\":\"IMO-1\"}]", false);
		}, tool("list-ships", null), "fleet_list-ships");

		String out = callback.call("{\"status\":\"active\"}");

		assertThat(names).containsExactly("list-ships");
		assertThat(args).containsExactly(Map.of("status", "active"));
		assertThat(out).isEqualTo(new JsonHelper().toJson(text("[{\"imo\":\"IMO-1\"}]", false).content()));
	}

	@Test
	void blankInputBecomesEmptyArguments() {
		List<Map<String, Object>> args = new ArrayList<>();
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> {
			args.add(a);
			return text("ok", false);
		}, tool("t", null), "t");

		callback.call("");
		callback.call("   ");

		assertThat(args).containsExactly(Map.of(), Map.of());
	}

	@Test
	void isErrorResultBecomesToolExecutionExceptionWithContent() {
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> text("ship not found", true), tool("t", null),
				"t");

		assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(ToolExecutionException.class)
			.satisfies(ex -> assertThat(((ToolExecutionException) ex).getToolDefinition().name()).isEqualTo("t"))
			.cause()
			.hasMessageContaining("ship not found");
	}

	@Test
	void engineExceptionIsSanitised() {
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> {
			throw new IllegalStateException("GET https://internal.example/secret?token=abc failed");
		}, tool("t", null), "t");

		assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(ToolExecutionException.class)
			.cause()
			.hasMessage("Tool 't' failed")
			.hasNoCause();
	}

	@Test
	void annotationsAreExposed() {
		McpSchema.ToolAnnotations hints = new McpSchema.ToolAnnotations(null, false, true, null, null, null);
		IkanosToolCallback callback = new IkanosToolCallback((n, a) -> text("ok", false), tool("t", hints), "t");

		assertThat(callback.getToolAnnotations()).isNotNull();
		assertThat(callback.getToolAnnotations().destructiveHint()).isTrue();
	}

}
