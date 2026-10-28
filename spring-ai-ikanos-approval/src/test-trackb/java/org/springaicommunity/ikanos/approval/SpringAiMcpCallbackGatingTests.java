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

package org.springaicommunity.ikanos.approval;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Track B against the real Spring AI {@link SyncMcpToolCallback} built from the proposed
 * upstream change (a local {@code spring-ai-mcp:2.1.0-SNAPSHOT} with
 * {@code getToolAnnotations()}).
 * Run with {@code mvn verify -Ptrackb -pl spring-ai-ikanos-approval}.
 */
class SpringAiMcpCallbackGatingTests {

	@Test
	void destructiveRemoteToolIsGatedAndNeverCalledWhenDenied() {
		McpSyncClient client = mock(McpSyncClient.class);
		McpSchema.Tool deleteFile = McpSchema.Tool.builder("deleteFile", Map.of())
			.annotations(McpSchema.ToolAnnotations.builder().destructiveHint(true).build())
			.build();
		McpSchema.Tool readFile = McpSchema.Tool.builder("readFile", Map.of())
			.annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
			.build();
		SyncMcpToolCallback delete = SyncMcpToolCallback.builder().mcpClient(client).tool(deleteFile).build();
		SyncMcpToolCallback read = SyncMcpToolCallback.builder().mcpClient(client).tool(readFile).build();

		List<ToolCallback> gated = ToolApprovalGate.apply(McpToolAnnotations.adaptAll(List.of(read, delete)),
				ToolApprovalMode.REQUIRE_HANDLER, request -> ToolApprovalDecision.deny("remote delete needs a ticket"));

		assertThat(delete.getToolAnnotations()).isSameAs(deleteFile.annotations());
		assertThat(gated.get(0)).isInstanceOf(ToolAnnotationsAware.class)
			.isNotInstanceOf(ApprovalGatedToolCallback.class);
		assertThat(gated.get(1)).isInstanceOf(ApprovalGatedToolCallback.class);
		assertThat(gated.get(1).getToolDefinition().name()).isEqualTo(delete.getToolDefinition().name());
		assertThat(gated.get(1).call("{\"path\":\"/etc\"}")).isEqualTo("Denied: remote delete needs a ticket");
		verify(client, never()).callTool(any());
	}

}
