# Spring AI Ikanos

Run the tools of a declarative [Ikanos](https://github.com/naftiko/ikanos) capability **inside** a Spring AI application.

Every tool declared in a capability's `exposes: type: mcp` block becomes a Spring AI `ToolCallback` that executes in the same JVM. No MCP server is started, no port is bound and no second process runs. The same YAML can still be served as a remote MCP server; choosing between the two is a deployment decision, not a rewrite.

> **Status: proof of concept (phase 3).** Core bridge, approval gate, Boot auto-configuration, starter and example. See the [design](#design).

## When to use it

| Agent and tools share... | Use |
|---|---|
| Team, release cadence, deployment unit | **In-JVM** — this project |
| Nothing (tools are shared or owned by another team) | **Remote MCP** — an Ikanos MCP server (see [Known limits](#known-limits) on client compatibility) |

## Quick start (Spring Boot)

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-ikanos</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
spring:
  ai:
    ikanos:
      capabilities:
        - classpath:capabilities/*.yml
```

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, IkanosToolCallbackProvider ikanosTools) {
    return builder.defaultTools(ikanosTools).build();
}
```

The tools are deliberately **not** added to every `ChatClient`: attach them where you want them, with `defaultTools(...)` or per request with `.tools(...)`. A complete application is in [`examples/fleet`](examples/fleet).

### Properties

| Property | Default | Meaning |
|---|---|---|
| `spring.ai.ikanos.enabled` | `true` | Master switch |
| `spring.ai.ikanos.capabilities` | — (required) | Capability YAML locations; Spring resource patterns allowed |
| `spring.ai.ikanos.expose` | all `type: mcp` exposes | Restrict to these MCP expose namespaces |
| `spring.ai.ikanos.tool-name-prefix` | none | Prefix for tool names shown to the model (the engine always gets the original name) |
| `spring.ai.ikanos.telemetry` | `false` | Ikanos' own OpenTelemetry tracing and metrics |
| `spring.ai.ikanos.approval.mode` | `require-handler` | How tools that may be destructive are gated: `require-handler`, `deny-destructive` or `none` (see [Approval gate](#approval-gate)) |

Startup fails fast when no capability is configured, a location matches nothing, an `expose` namespace does not exist, two tools end up with the same name, or a tool that may be destructive has no approval handler.

Use `file:` locations when a capability uses relative `import`s: resources inside a jar are parsed from their stream and cannot resolve relative paths.

## Usage without Boot (core module)

```java
Capability capability = Capability.builder()
        .loadFromClasspath("/capabilities/fleet.yml")
        .telemetry(false)          // Spring AI already observes tool calls
        .build();                  // builds, never starts the MCP server

IkanosCapabilityLifecycle lifecycle = new IkanosCapabilityLifecycle(List.of(capability)); // a SmartLifecycle bean in Spring
IkanosToolCallbackProvider tools = IkanosToolCallbackProvider.builder()
        .capabilities(capability)
        .approvalHandler(request -> ToolApprovalDecision.approve()) // see Approval gate
        .build();

String answer = chatClient.prompt()
        .user("Which ships are active?")
        .tools(tools)
        .call()
        .content();
```

`IkanosCapabilityLifecycle` starts the capability's outbound HTTP clients and stops them on shutdown. It never touches server adapters.

## Behaviour

- **Tool definitions** come from `McpToolUtils.createToolDefinition`, and successful results are serialized like `SyncMcpToolCallback` does, so a tool looks the same to the model in-JVM or over MCP.
- **`isError` results** become a `ToolExecutionException` with the capability-authored content.
- **Engine exceptions** are logged and replaced by a generic `Tool '<name>' failed` message, so upstream URLs or configuration never reach the model.
- **Duplicate tool names** across capabilities fail fast. Use `exposeNamespaces(...)` or `toolNamePrefix(...)`.
- MCP `ToolAnnotations` (`readOnlyHint`, `destructiveHint`, ...) are available via `IkanosToolCallback.getToolAnnotations()`.

## Approval gate

Tools that may be destructive are reviewed by a `ToolApprovalHandler` before they run. Which tools are gated follows the MCP `ToolAnnotations` defaults (`readOnlyHint` false, `destructiveHint` true): every tool is gated **unless** its capability declares `hints: readOnly: true` or `hints: destructive: false`. A tool with no `hints` at all is gated.

```java
@Bean
ToolApprovalHandler toolApprovalHandler() {
    return request -> isAllowed(request.toolName(), request.input(), request.toolContext())
            ? ToolApprovalDecision.approve()
            : ToolApprovalDecision.deny("needs a change ticket");
}
```

| `spring.ai.ikanos.approval.mode` | Without a handler bean | With a handler bean |
|---|---|---|
| `require-handler` (default) | **Startup fails**, naming the tools | Handler reviews each call |
| `deny-destructive` | Every call denied | Handler ignored; every call denied |
| `none` | No gate (logged at `WARN`) | No gate |

- A denial is **returned to the model** as the tool result (`Denied: <reason>`), so it can explain or try another path. A handler that throws becomes a `ToolExecutionException`; the tool does not run.
- The gate is a `ToolCallback` decorator, not a `ToolAdvisor`, so it composes with `ToolSearchToolCallingAdvisor` (`DefaultChatClient` allows only one `ToolAdvisor`).
- It runs synchronously on the tool-calling thread. A handler that waits for a human blocks that thread.
- Hints are authored by the capability. The gate enforces the declared classification; it does not infer one. Tools derived from flows via `ref` are gated unless they declare `readOnly` or `destructive: false`.
- The `spring-ai-ikanos-approval` module has no Ikanos dependency. Any `ToolCallback` implementing `ToolAnnotationsAware` can be gated with `ToolApprovalGate` or `ApprovalGatedToolCallbackProvider`.
- **Remote MCP tools.** `McpToolAnnotations.adaptAll(...)` makes any callback that exposes its `McpSchema.Tool` through `getTool()` gateable. Spring AI's `SyncMcpToolCallback`/`AsyncMcpToolCallback` do not expose it yet; we propose adding it upstream. Until then, Spring AI MCP tools are passed through ungated:

  ```java
  List<ToolCallback> gated = ToolApprovalGate.apply(
          McpToolAnnotations.adaptAll(List.of(syncMcpToolCallbackProvider.getToolCallbacks())),
          ToolApprovalMode.REQUIRE_HANDLER, handler);
  ```

## Known limits

- **Flat input schemas.** Ikanos input parameters carry a type and description only (no nested objects, `enum` or `format`).
- **`ToolContext` is not propagated** to the engine yet.
- **Bindings resolve from environment variables** at capability construction, not from the Spring `Environment`.
- **Classpath weight.** `ikanos-engine` `1.0.0-beta8` brings Restlet `3.0.0-m1` (a milestone, Jetty 12) and uses Jackson 3 like Spring Boot 4. A residual Jackson 2 remains for the engine's swagger-parser, Avro and json-structure dependencies; it lives in a different package and does not conflict. No server starts, and `opentelemetry-sdk` is not needed.
- **Remote posture and `spring-ai-starter-mcp-client`.** An Ikanos MCP server speaks only MCP `2026-07-28` (stateless, no `initialize`). Spring AI `2.0.2` ships MCP Java SDK `2.1.0`, whose client speaks at most `2025-11-25` and starts with `initialize`, so it cannot connect to an Ikanos server today. SDK `2.2.0-M1` adds `2026-07-28`; no Spring AI release uses it yet. Tool definitions and outputs are nevertheless identical in both postures (verified over raw `2026-07-28` HTTP in `PostureParityIntegrationTests`).

- **Spring Boot 4.1: pin `json-path` `3.0.0`.** From `ikanos-engine` `1.0.0-beta8` (Jackson 3), output mappings need `Jackson3JsonNodeJsonProvider`, which only exists in `json-path` `3.0.0` and later. Spring Boot 4.1 manages `2.10.0`, so every tool with a `mapping:` fails on first call with `NoClassDefFoundError`. Your application must pin it: the `<json-path.version>3.0.0</json-path.version>` property with `spring-boot-starter-parent`, or a direct `com.jayway.jsonpath:json-path:3.0.0` entry in `<dependencyManagement>` when you import the `spring-boot-dependencies` BOM. The starter cannot do it for you: the application's Boot dependency management wins over the starter's.

## Requirements

Java 21+, Spring AI `2.0.x`, Spring Boot `4.0`/`4.1`, `io.ikanos:ikanos-engine` `1.0.0-beta8` or later. The build enforces a single `mcp-core` `2.x` on the classpath.

`mvn verify` builds against `ikanos-engine` `1.0.0-beta8` from Maven Central.

To build against the engine's `main` branch, install it under any snapshot version and pass that version:

```shell
git clone https://github.com/naftiko/ikanos && cd ikanos
mvn install -pl modules/ikanos-engine -am -DskipTests -Djacoco.skip=true -Drevision=0.0.0-engine-next-SNAPSHOT
```

Then `mvn verify -Dikanos.version=0.0.0-engine-next-SNAPSHOT` here.

## Design

The design, roadmap and the planned Spring AI Community proposal live in the Naftiko design document *Spring AI Ikanos* (phases 1–6).

## Commercial relationship

Naftiko develops the Ikanos engine (Apache 2.0) and offers commercial products and services around it. This project is Apache 2.0 and does not require any commercial component.

## License

[Apache License 2.0](LICENSE)
