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

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A {@link ToolCallback} decorator that asks a {@link ToolApprovalHandler} before every call.
 *
 * <p>
 * A decorator rather than a {@code ToolAdvisor}: Spring AI's {@code DefaultChatClient} allows
 * exactly one {@code ToolAdvisor} per chain, so an advisor-based gate would displace
 * {@code ToolSearchToolCallingAdvisor}. A decorator runs inside whichever advisor drives the
 * loop, and makes the gate a property of the tool rather than of the chat client.
 *
 * <p>
 * The tool definition and metadata are the delegate's, unchanged, so the model sees the same
 * tool. A denial is <em>returned</em> as the tool result ({@code "Denied: <reason>"}) so the
 * model can explain or try another path. A handler that throws becomes a
 * {@link ToolExecutionException} and the delegate is not called.
 */
public final class ApprovalGatedToolCallback implements ToolCallback, ToolAnnotationsAware {

	/** Prefix of the tool result returned to the model on denial. */
	public static final String DENIED_PREFIX = "Denied: ";

	private static final String NO_REASON = "the tool call was not approved";

	private static final Log logger = LogFactory.getLog(ApprovalGatedToolCallback.class);

	private final ToolCallback delegate;

	private final McpSchema.@Nullable ToolAnnotations annotations;

	private final ToolApprovalHandler handler;

	/**
	 * Gate a callback.
	 * @param delegate the tool to protect
	 * @param annotations the tool's MCP annotations, passed to the handler
	 * @param handler decides each call
	 */
	public ApprovalGatedToolCallback(ToolCallback delegate, McpSchema.@Nullable ToolAnnotations annotations,
			ToolApprovalHandler handler) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.delegate = delegate;
		this.annotations = annotations;
		this.handler = handler;
	}

	/**
	 * @return the protected callback
	 */
	public ToolCallback getDelegate() {
		return this.delegate;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public McpSchema.@Nullable ToolAnnotations getToolAnnotations() {
		return this.annotations;
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		ToolDefinition definition = getToolDefinition();
		ToolApprovalDecision decision;
		try {
			decision = this.handler.review(new ToolApprovalRequest(definition, this.annotations, toolInput, toolContext));
		}
		catch (ToolExecutionException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new ToolExecutionException(definition, ex);
		}

		if (decision != null && decision.approved()) {
			return (toolContext != null) ? this.delegate.call(toolInput, toolContext) : this.delegate.call(toolInput);
		}
		String reason = (decision != null && StringUtils.hasText(decision.reason())) ? decision.reason() : NO_REASON;
		if (logger.isInfoEnabled()) {
			logger.info("Tool '" + definition.name() + "' denied: " + reason);
		}
		return DENIED_PREFIX + reason;
	}

	@Override
	public String toString() {
		return "ApprovalGatedToolCallback[" + this.delegate + "]";
	}

}
