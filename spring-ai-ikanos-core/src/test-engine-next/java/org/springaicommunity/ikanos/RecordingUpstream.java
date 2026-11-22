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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;

import org.springframework.ai.tool.ToolCallback;

/**
 * Test fixture for engine features newer than the default engine: a JDK {@link HttpServer}
 * standing in for the upstream API, recording each request with its {@code Authorization}
 * header.
 */
final class RecordingUpstream implements AutoCloseable {

	/** One request seen by the upstream. */
	record Request(String method, String path, String authorization) {
	}

	final List<Request> requests = new CopyOnWriteArrayList<>();

	final int port;

	private final HttpServer server;

	RecordingUpstream() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.server.createContext("/", exchange -> {
			this.requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization")));
			byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		this.server.start();
		this.port = this.server.getAddress().getPort();
	}

	/** Load a capability through the engine, never with a test-owned mapper. */
	static Capability load(String yaml) throws IOException {
		Path file = Files.createTempFile("engine-next-", ".yml");
		try {
			Files.writeString(file, yaml);
			return Capability.builder().loadFromFile(file).telemetry(false).build();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	static ToolCallback tool(IkanosToolCallbackProvider provider, String name) {
		for (ToolCallback callback : provider.getToolCallbacks()) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new IllegalArgumentException("No tool " + name);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}
