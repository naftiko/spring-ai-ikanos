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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import io.ikanos.Capability;

/**
 * Test fixture: a JDK {@link HttpServer} standing in for the upstream API, and a capability
 * whose MCP expose declares a read-only and a destructive tool against it.
 */
public final class FleetFixture implements AutoCloseable {

	public static final String SHIPS_JSON = "{\"ships\":[{\"imo_number\":\"IMO-1\",\"vessel_name\":\"Sea Eagle\"}]}";

	public final List<String> requests = new CopyOnWriteArrayList<>();

	public final int upstreamPort;

	public final int mcpPort;

	private final HttpServer upstream;

	public FleetFixture() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.upstream.createContext("/ships", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			this.requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
					+ ((query != null) ? "?" + query : ""));
			byte[] body = SHIPS_JSON.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		this.upstream.start();
		this.upstreamPort = this.upstream.getAddress().getPort();
		this.mcpPort = freePort();
	}

	public String yaml(String namespace) {
		return """
				ikanos: "1.0.0-beta8"
				info:
				  display: Fleet %1$s
				  description: spring-ai-ikanos test capability
				capability:
				  consumes:
				  - namespace: registry
				    type: http
				    baseUri: "http://localhost:%2$d"
				    resources:
				      ships:
				        path: /ships
				        operations:
				          list-ships:
				            method: GET
				            inputParameters:
				              status:
				                in: query
				          decommission:
				            method: DELETE
				  exposes:
				  - type: mcp
				    address: localhost
				    port: %3$d
				    namespace: %1$s
				    description: Fleet tools
				    tools:
				      list-ships:
				        description: List ships by status
				        hints:
				          readOnly: true
				        inputParameters:
				          status:
				            type: string
				            required: true
				            description: Operational status
				        call: registry.list-ships
				        with:
				          status: %1$s.status
				        outputParameters:
				        - type: array
				          mapping: "$.ships"
				          items:
				            type: object
				            properties:
				              imo:
				                type: string
				                mapping: "$.imo_number"
				              name:
				                type: string
				                mapping: "$.vessel_name"
				      decommission-ship:
				        description: Decommission the current ship
				        hints:
				          readOnly: false
				          destructive: true
				        call: registry.decommission
				""".formatted(namespace, this.upstreamPort, this.mcpPort);
	}

	public Capability capability(String namespace) throws IOException {
		// parse through the engine, never with a test-owned mapper (engine Jackson major may differ)
		Path file = Files.createTempFile("fleet-" + namespace + "-", ".yml");
		try {
			Files.writeString(file, yaml(namespace));
			return Capability.builder().loadFromFile(file).telemetry(false).build();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	public static boolean isListening(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", port), 500);
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Override
	public void close() {
		this.upstream.stop(0);
	}

}
