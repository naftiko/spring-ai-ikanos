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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.ikanos.Capability;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.modelcontextprotocol.spec.McpSchema;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.Assert;

/**
 * A {@link ToolCallbackProvider} exposing every tool declared in the {@code exposes: type: mcp}
 * blocks of one or more Ikanos capabilities as an {@link IkanosToolCallback}.
 *
 * <p>
 * The provider only reads the MCP server adapters built by the capability; it never starts them.
 * Starting the outbound client adapters is the job of {@link IkanosCapabilityLifecycle}.
 *
 * <p>
 * Duplicate tool names across capabilities (after prefixing) fail fast with an
 * {@link IllegalArgumentException}, like {@code MethodToolCallbackProvider}.
 */
public final class IkanosToolCallbackProvider implements ToolCallbackProvider {

	private final ToolCallback[] toolCallbacks;

	private IkanosToolCallbackProvider(List<ToolCallback> toolCallbacks) {
		this.toolCallbacks = toolCallbacks.toArray(ToolCallback[]::new);
	}

	@Override
	public ToolCallback[] getToolCallbacks() {
		return this.toolCallbacks.clone();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link IkanosToolCallbackProvider}.
	 */
	public static final class Builder {

		private final List<Capability> capabilities = new ArrayList<>();

		private final Set<String> namespaces = new LinkedHashSet<>();

		private String toolNamePrefix = "";

		private Builder() {
		}

		/**
		 * Add capabilities whose MCP tools should be bridged.
		 * @param capabilities built (not necessarily started) capabilities
		 * @return this builder
		 */
		public Builder capabilities(Collection<Capability> capabilities) {
			Assert.notNull(capabilities, "capabilities must not be null");
			this.capabilities.addAll(capabilities);
			return this;
		}

		public Builder capabilities(Capability... capabilities) {
			return capabilities(List.of(capabilities));
		}

		/**
		 * Restrict bridging to the MCP exposes with these namespaces. Empty means all.
		 * @param namespaces MCP expose namespaces
		 * @return this builder
		 */
		public Builder exposeNamespaces(Collection<String> namespaces) {
			Assert.notNull(namespaces, "namespaces must not be null");
			this.namespaces.addAll(namespaces);
			return this;
		}

		/**
		 * Prefix prepended verbatim to every tool name shown to the model. The engine is always
		 * called with the original name. Include your own separator, e.g. {@code "fleet_"}.
		 * @param toolNamePrefix the prefix, or {@code null}/empty for none
		 * @return this builder
		 */
		public Builder toolNamePrefix(String toolNamePrefix) {
			this.toolNamePrefix = (toolNamePrefix != null) ? toolNamePrefix : "";
			return this;
		}

		public IkanosToolCallbackProvider build() {
			Map<String, ToolCallback> byName = new LinkedHashMap<>();
			Set<String> matchedNamespaces = new LinkedHashSet<>();

			for (Capability capability : this.capabilities) {
				for (ServerAdapter adapter : capability.getServerAdapters()) {
					if (!(adapter instanceof McpServerAdapter mcp)) {
						continue;
					}
					String namespace = mcp.getMcpServerSpec().getNamespace();
					if (!this.namespaces.isEmpty() && !this.namespaces.contains(namespace)) {
						continue;
					}
					matchedNamespaces.add(namespace);
					ToolInvoker invoker = mcp.getToolHandler()::handleToolCall;
					for (McpSchema.Tool tool : mcp.getTools()) {
						String exposedName = this.toolNamePrefix + tool.name();
						ToolCallback previous = byName.putIfAbsent(exposedName,
								new IkanosToolCallback(invoker, tool, exposedName));
						if (previous != null) {
							throw new IllegalArgumentException("Duplicate Ikanos tool name '" + exposedName
									+ "' (MCP namespace '" + namespace + "'). Use distinct tool names, "
									+ "restrict exposeNamespaces, or set a toolNamePrefix.");
						}
					}
				}
			}

			Set<String> unknown = new LinkedHashSet<>(this.namespaces);
			unknown.removeAll(matchedNamespaces);
			if (!unknown.isEmpty()) {
				throw new IllegalArgumentException("No MCP expose found for namespace(s) " + unknown);
			}
			return new IkanosToolCallbackProvider(new ArrayList<>(byName.values()));
		}

	}

}
