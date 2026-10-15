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

/**
 * Approval gate for Spring AI {@code ToolCallback}s, driven by MCP {@code ToolAnnotations}.
 *
 * <p>
 * Generic: depends only on Spring AI and the MCP Java SDK, not on Ikanos. Any callback that
 * implements {@link org.springaicommunity.ikanos.approval.ToolAnnotationsAware} can be gated.
 */
@NullMarked
package org.springaicommunity.ikanos.approval;

import org.jspecify.annotations.NullMarked;
