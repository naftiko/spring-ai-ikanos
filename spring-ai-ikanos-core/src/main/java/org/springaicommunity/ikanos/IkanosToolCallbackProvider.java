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
import io.ikanos.engine.exposes.AccessControl;
import io.ikanos.engine.exposes.ServerAdapter;
import io.ikanos.engine.exposes.mcp.McpServerAdapter;
import io.ikanos.spec.exposes.AuthorizationSpec;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.ikanos.approval.ToolApprovalGate;
import org.springaicommunity.ikanos.approval.ToolApprovalHandler;
import org.springaicommunity.ikanos.approval.ToolApprovalMode;

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
 *
 * <p>
 * Tools that may be destructive (MCP defaults: anything not annotated {@code readOnlyHint: true}
 * or {@code destructiveHint: false}) are wrapped by the approval gate, according to the
 * {@link Builder#approvalMode(ToolApprovalMode) approval mode}. The default,
 * {@link ToolApprovalMode#REQUIRE_HANDLER}, fails {@link Builder#build()} when such tools exist
 * and no {@link Builder#approvalHandler(ToolApprovalHandler) handler} is set.
 */
public final class IkanosToolCallbackProvider implements ToolCallbackProvider {

	private static final Log logger = LogFactory.getLog(IkanosToolCallbackProvider.class);

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

		private ToolApprovalMode approvalMode = ToolApprovalMode.REQUIRE_HANDLER;

		private @Nullable ToolApprovalHandler approvalHandler;

		private ToolAuthorizationMode authorizationMode = ToolAuthorizationMode.ENFORCE;

		private @Nullable IkanosCallerClaimsResolver callerClaimsResolver;

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

		/**
		 * How to treat tools that may be destructive. Defaults to
		 * {@link ToolApprovalMode#REQUIRE_HANDLER}.
		 * @param approvalMode the mode
		 * @return this builder
		 */
		public Builder approvalMode(ToolApprovalMode approvalMode) {
			Assert.notNull(approvalMode, "approvalMode must not be null");
			this.approvalMode = approvalMode;
			return this;
		}

		/**
		 * The handler asked before each call to a tool that may be destructive, in
		 * {@link ToolApprovalMode#REQUIRE_HANDLER} mode.
		 * @param approvalHandler the handler, or {@code null} for none
		 * @return this builder
		 */
		public Builder approvalHandler(@Nullable ToolApprovalHandler approvalHandler) {
			this.approvalHandler = approvalHandler;
			return this;
		}

		/**
		 * How to treat tools whose capability declares an {@code authorization} requirement.
		 * Defaults to {@link ToolAuthorizationMode#ENFORCE}.
		 * @param authorizationMode the mode
		 * @return this builder
		 */
		public Builder authorizationMode(ToolAuthorizationMode authorizationMode) {
			Assert.notNull(authorizationMode, "authorizationMode must not be null");
			this.authorizationMode = authorizationMode;
			return this;
		}

		/**
		 * Finds the claims of the user each call is made for, checked against
		 * {@code authorization} requirements and read by {@code {{auth.claims...}}} templates.
		 * @param callerClaimsResolver the resolver, or {@code null} for no caller identity
		 * @return this builder
		 */
		public Builder callerClaimsResolver(@Nullable IkanosCallerClaimsResolver callerClaimsResolver) {
			this.callerClaimsResolver = callerClaimsResolver;
			return this;
		}

		/**
		 * Build the provider.
		 * @return the provider
		 * @throws IllegalArgumentException on duplicate tool names or unknown namespaces
		 * @throws org.springaicommunity.ikanos.approval.ToolApprovalRequiredException in
		 * {@code REQUIRE_HANDLER} mode when a tool may be destructive and no handler is set
		 * @throws IllegalStateException in {@link ToolAuthorizationMode#ENFORCE} mode when a tool
		 * declares an {@code authorization} requirement and no caller claims resolver is set
		 */
		public IkanosToolCallbackProvider build() {
			Map<String, ToolCallback> byName = new LinkedHashMap<>();
			Set<String> matchedNamespaces = new LinkedHashSet<>();
			Set<String> authorized = new LinkedHashSet<>();

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
						// the tool's requirement, else its adapter's, as the remote MCP transport picks it
						AuthorizationSpec requirement = AccessControl.effective(mcp.getMcpServerSpec(),
								mcp.getMcpServerSpec().getTools().get(tool.name()));
						if (requirement != null) {
							authorized.add(exposedName);
						}
						ToolCallback previous = byName.putIfAbsent(exposedName, new IkanosToolCallback(invoker, tool,
								exposedName, requirement, this.authorizationMode, this.callerClaimsResolver));
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
			checkAuthorization(authorized);
			return new IkanosToolCallbackProvider(
					ToolApprovalGate.apply(new ArrayList<>(byName.values()), this.approvalMode, this.approvalHandler));
		}

		/**
		 * Fail when tools declare a requirement that cannot be checked, or warn when checking is
		 * turned off.
		 * @param authorized the exposed names of the tools that declare a requirement
		 */
		private void checkAuthorization(Set<String> authorized) {
			if (authorized.isEmpty()) {
				return;
			}
			if (this.authorizationMode == ToolAuthorizationMode.NONE) {
				logger.warn("Tool authorization is disabled (mode NONE): the authorization requirements of "
						+ authorized + " are not checked; the application must authorize these calls");
			}
			else if (this.callerClaimsResolver == null) {
				throw new IllegalStateException("Tools " + authorized + " declare an authorization requirement, "
						+ "but no IkanosCallerClaimsResolver is set to find the caller's claims. Set one, or set "
						+ "ToolAuthorizationMode.NONE if the application authorizes these calls itself.");
			}
		}

	}

}
