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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Spring AI Ikanos.
 */
@ConfigurationProperties(prefix = IkanosProperties.PREFIX)
public class IkanosProperties {

	public static final String PREFIX = "spring.ai.ikanos";

	/**
	 * Whether to enable the in-JVM Ikanos tool bridge.
	 */
	private boolean enabled = true;

	/**
	 * Capability YAML locations; Spring resource patterns are supported, e.g.
	 * {@code classpath:capabilities/*.yml}.
	 */
	private List<String> capabilities = new ArrayList<>();

	/**
	 * MCP expose namespaces to bridge. Empty means every {@code type: mcp} expose.
	 */
	private List<String> expose = new ArrayList<>();

	/**
	 * Prefix prepended to every tool name shown to the model (include your own separator).
	 */
	private @Nullable String toolNamePrefix;

	/**
	 * Enable Ikanos' own OpenTelemetry tracing and metrics. Spring AI already observes tool
	 * calls, so this is off by default.
	 */
	private boolean telemetry;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<String> getCapabilities() {
		return this.capabilities;
	}

	public void setCapabilities(List<String> capabilities) {
		this.capabilities = capabilities;
	}

	public List<String> getExpose() {
		return this.expose;
	}

	public void setExpose(List<String> expose) {
		this.expose = expose;
	}

	public @Nullable String getToolNamePrefix() {
		return this.toolNamePrefix;
	}

	public void setToolNamePrefix(@Nullable String toolNamePrefix) {
		this.toolNamePrefix = toolNamePrefix;
	}

	public boolean isTelemetry() {
		return this.telemetry;
	}

	public void setTelemetry(boolean telemetry) {
		this.telemetry = telemetry;
	}

}
