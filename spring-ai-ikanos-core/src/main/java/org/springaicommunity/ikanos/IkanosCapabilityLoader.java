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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import io.ikanos.Capability;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.Assert;

/**
 * Builds Ikanos {@link Capability capabilities} from Spring resource locations (patterns
 * allowed, e.g. {@code classpath:capabilities/*.yml}).
 *
 * <p>
 * Capabilities are built, never started. YAML is only ever parsed by the engine itself, so the
 * bridge does not depend on the Jackson major the engine uses. Filesystem resources are loaded
 * with {@code Capability.Builder.loadFromFile} so that relative {@code import} paths resolve;
 * other resources (e.g. inside a jar) are copied to a temporary file first, in which case
 * relative imports cannot resolve.
 */
public final class IkanosCapabilityLoader {

	private final ResourcePatternResolver resolver;

	private final boolean telemetry;

	public IkanosCapabilityLoader(ResourcePatternResolver resolver, boolean telemetry) {
		Assert.notNull(resolver, "resolver must not be null");
		this.resolver = resolver;
		this.telemetry = telemetry;
	}

	/**
	 * Resolve every location and build one capability per matching resource.
	 * @param locations resource locations or patterns
	 * @return the built capabilities, in location order then by resource description
	 * @throws IllegalStateException if a location matches nothing or a capability fails to load
	 */
	public List<Capability> load(List<String> locations) {
		List<Capability> capabilities = new ArrayList<>();
		for (String location : locations) {
			List<Resource> resources = resolve(location);
			if (resources.isEmpty()) {
				throw new IllegalStateException("No Ikanos capability found at '" + location + "'");
			}
			for (Resource resource : resources) {
				capabilities.add(load(resource));
			}
		}
		return capabilities;
	}

	private List<Resource> resolve(String location) {
		try {
			return Arrays.stream(this.resolver.getResources(location))
				.filter(Resource::exists)
				.sorted(Comparator.comparing(Resource::getDescription))
				.toList();
		}
		catch (FileNotFoundException ex) {
			// a pattern whose root directory does not exist matches nothing
			return List.of();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to resolve Ikanos capability location '" + location + "'", ex);
		}
	}

	private Capability load(Resource resource) {
		try {
			Capability.Builder builder = Capability.builder().telemetry(this.telemetry);
			if (resource.isFile()) {
				builder.loadFromFile(resource.getFile().toPath());
			}
			else {
				loadFromStream(builder, resource);
			}
			return builder.build();
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("Failed to load Ikanos capability from " + resource.getDescription(), ex);
		}
	}

	/**
	 * The engine only parses from the classpath (relative to its own class loader) or from a
	 * file. A temporary copy keeps parsing inside the engine for any other resource.
	 */
	private static void loadFromStream(Capability.Builder builder, Resource resource) throws IOException {
		Path tmp = Files.createTempFile("ikanos-capability-", ".yml");
		try {
			try (InputStream in = resource.getInputStream()) {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			}
			builder.loadFromFile(tmp);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}

}
