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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.ikanos.IkanosCallerClaimsResolver;
import org.springaicommunity.ikanos.IkanosToolCallbackProvider;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.ai.ikanos.authorization.mode} and the caller claims resolver: a tool that
 * requires a scope runs as the user Spring Security authenticated, and only when that user holds
 * the scope.
 */
class ToolAuthorizationAutoConfigurationTests {

	@TempDir
	Path dir;

	private final List<String> userIds = new CopyOnWriteArrayList<>();

	private HttpServer upstream;

	private ApplicationContextRunner runner;

	@BeforeEach
	void setUp() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.upstream.createContext("/", exchange -> {
			this.userIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-User-Id")));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		this.upstream.start();
		Files.writeString(this.dir.resolve("fleet.yml"), yaml(this.upstream.getAddress().getPort()));
		String location = "file:" + this.dir.toAbsolutePath().toString().replace('\\', '/') + "/*.yml";
		this.runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(IkanosAutoConfiguration.class))
			.withPropertyValues("spring.ai.ikanos.capabilities=" + location, "spring.ai.ikanos.approval.mode=none");
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		this.upstream.stop(0);
	}

	@Test
	void toolRunsAsTheAuthenticatedUserHoldingTheScope() {
		authenticate("alice", "fleet.read fleet.admin");

		this.runner.run(context -> {
			assertThat(context).hasSingleBean(SecurityContextCallerClaimsResolver.class);
			decommission(context.getBean(IkanosToolCallbackProvider.class)).call("{}");
		});

		assertThat(this.userIds).containsExactly("alice");
	}

	@Test
	void authenticatedUserWithoutTheScopeIsDenied() {
		authenticate("bob", "fleet.read");

		this.runner.run(context -> assertThat(decommission(context.getBean(IkanosToolCallbackProvider.class)).call("{}"))
			.startsWith("Denied: "));

		assertThat(this.userIds).isEmpty();
	}

	@Test
	void startupFailsWhenNoResolverCanFindTheCaller() {
		this.runner.withClassLoader(new FilteredClassLoader(AbstractOAuth2TokenAuthenticationToken.class))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("[decommission-ship]")
				.hasMessageContaining("IkanosCallerClaimsResolver"));
	}

	@Test
	void applicationResolverReplacesTheSecurityContextResolver() {
		this.runner.withBean(IkanosCallerClaimsResolver.class, IkanosCallerClaimsResolver::fromToolContext)
			.run(context -> assertThat(context).doesNotHaveBean(SecurityContextCallerClaimsResolver.class));
	}

	@Test
	void noneModeRunsTheToolWithoutCheckingTheScope() {
		authenticate("bob", "fleet.read");

		this.runner.withPropertyValues("spring.ai.ikanos.authorization.mode=none")
			.run(context -> decommission(context.getBean(IkanosToolCallbackProvider.class)).call("{}"));

		assertThat(this.userIds).containsExactly("bob");
	}

	private static void authenticate(String subject, String scope) {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(subject).claim("scope", scope).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
	}

	private static ToolCallback decommission(IkanosToolCallbackProvider provider) {
		return provider.getToolCallbacks()[0];
	}

	private static String yaml(int upstreamPort) {
		return """
				ikanos: "1.0.0-beta10"
				info:
				  display: Fleet
				  description: spring-ai-ikanos test capability
				capability:
				  consumes:
				  - namespace: registry
				    type: http
				    baseUri: "http://localhost:%d"
				    inputParameters:
				      X-User-Id:
				        in: header
				        value: "{{auth.claims.sub}}"
				    resources:
				      ship:
				        path: /ship
				        operations:
				          decommission:
				            method: DELETE
				  exposes:
				  - type: mcp
				    address: localhost
				    port: 0
				    namespace: fleet
				    description: Fleet tools
				    authentication:
				      type: oauth2
				      authorizationServerUri: "http://localhost:1"
				      resource: "http://localhost/mcp"
				      scopes: ["fleet.read", "fleet.admin"]
				    tools:
				      decommission-ship:
				        description: Decommission the ship, for fleet admins
				        authorization:
				          scopes: ["fleet.admin"]
				        call: registry.decommission
				""".formatted(upstreamPort);
	}

}
