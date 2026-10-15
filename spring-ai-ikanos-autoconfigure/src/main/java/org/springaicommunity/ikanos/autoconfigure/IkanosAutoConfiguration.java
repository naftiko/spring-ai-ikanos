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

import java.util.List;

import io.ikanos.Capability;
import org.springaicommunity.ikanos.IkanosCapabilityLifecycle;
import org.springaicommunity.ikanos.IkanosCapabilityLoader;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;
import org.springaicommunity.ikanos.approval.ToolApprovalHandler;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.io.ResourceLoader;

/**
 * Auto-configuration for in-JVM Ikanos capability tools.
 *
 * <p>
 * Loads the capabilities listed in {@code spring.ai.ikanos.capabilities}, owns their outbound
 * lifecycle, and publishes one {@link IkanosToolCallbackProvider} bean. Following Spring AI's MCP
 * client precedent, the tools are <em>not</em> added to every {@code ChatClient} automatically:
 * attach them with {@code .tools(provider)} or {@code defaultTools(provider)}.
 *
 * <p>
 * Tools that may be destructive are gated according to {@code spring.ai.ikanos.approval.mode}.
 * With the default {@code require-handler}, startup fails unless a single
 * {@link ToolApprovalHandler} bean is available.
 */
@AutoConfiguration
@ConditionalOnClass({ Capability.class, ToolCallbackProvider.class })
@ConditionalOnProperty(prefix = IkanosProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IkanosProperties.class)
public class IkanosAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	IkanosCapabilityLifecycle ikanosCapabilityLifecycle(IkanosProperties properties, ResourceLoader resourceLoader) {
		if (properties.getCapabilities().isEmpty()) {
			throw new IllegalStateException(
					"No Ikanos capability configured. Set '" + IkanosProperties.PREFIX + ".capabilities' "
							+ "(e.g. classpath:capabilities/*.yml) or '" + IkanosProperties.PREFIX
							+ ".enabled=false'.");
		}
		ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
		List<Capability> capabilities = new IkanosCapabilityLoader(resolver, properties.isTelemetry())
			.load(properties.getCapabilities());
		return new IkanosCapabilityLifecycle(capabilities);
	}

	@Bean
	@ConditionalOnMissingBean
	IkanosToolCallbackProvider ikanosToolCallbackProvider(IkanosCapabilityLifecycle lifecycle,
			IkanosProperties properties, ObjectProvider<ToolApprovalHandler> approvalHandler) {
		return IkanosToolCallbackProvider.builder()
			.capabilities(lifecycle.getCapabilities())
			.exposeNamespaces(properties.getExpose())
			.toolNamePrefix(properties.getToolNamePrefix())
			.approvalMode(properties.getApproval().getMode())
			.approvalHandler(approvalHandler.getIfUnique())
			.build();
	}

}
