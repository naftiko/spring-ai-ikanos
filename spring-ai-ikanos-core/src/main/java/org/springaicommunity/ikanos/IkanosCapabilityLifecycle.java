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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.ikanos.Capability;
import io.ikanos.engine.consumes.ClientAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

/**
 * Owns the outbound lifecycle of embedded Ikanos capabilities: starts every client adapter on
 * {@link #start()} and stops them on {@link #stop()}.
 *
 * <p>
 * Server adapters are never touched, so no port is bound and no MCP server runs.
 * {@code Capability.start()} is deliberately not used, because it starts server adapters too.
 */
public class IkanosCapabilityLifecycle implements SmartLifecycle {

	private static final Log logger = LogFactory.getLog(IkanosCapabilityLifecycle.class);

	private final List<Capability> capabilities;

	private final Deque<ClientAdapter> started = new ArrayDeque<>();

	private volatile boolean running;

	public IkanosCapabilityLifecycle(List<Capability> capabilities) {
		Assert.notNull(capabilities, "capabilities must not be null");
		this.capabilities = List.copyOf(capabilities);
	}

	public List<Capability> getCapabilities() {
		return this.capabilities;
	}

	@Override
	public synchronized void start() {
		if (this.running) {
			return;
		}
		try {
			for (Capability capability : this.capabilities) {
				for (ClientAdapter adapter : capability.getClientAdapters()) {
					adapter.start();
					this.started.push(adapter);
				}
			}
		}
		catch (Exception ex) {
			stopStarted();
			throw new IllegalStateException("Failed to start Ikanos client adapters", ex);
		}
		this.running = true;
	}

	@Override
	public synchronized void stop() {
		if (!this.running) {
			return;
		}
		stopStarted();
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	private void stopStarted() {
		while (!this.started.isEmpty()) {
			ClientAdapter adapter = this.started.pop();
			try {
				adapter.stop();
			}
			catch (Exception ex) {
				logger.warn("Failed to stop Ikanos client adapter " + adapter, ex);
			}
		}
	}

}
