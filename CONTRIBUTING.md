# Contributing to Spring AI Ikanos

This project follows the conventions of the [Spring Framework contribution guidelines](https://github.com/spring-projects/spring-framework/blob/main/CONTRIBUTING.md), adapted as follows.

## Before you start

- Discuss non-trivial changes in an issue first.
- Keep pull requests focused: one concern per PR.

## Developer Certificate of Origin

All commits must be signed off (`git commit -s`), certifying the [DCO](https://developercertificate.org/). PRs with unsigned commits cannot be merged.

## Build and test

```shell
mvn verify
```

Requirements: Java 21+, and a locally installed `io.ikanos:ikanos-engine` snapshot until `1.0.0-beta7` is published (see the README).

Tests must not need network access beyond `localhost` or any API key. Optional real-LLM tests must be gated by an environment variable and skipped by default.

## Code style

- Spring Framework style: tabs for indentation, `this.` for field access, Apache 2.0 header on every source file.
- Public API is null-marked with JSpecify (`@NullMarked` per package, `@Nullable` where needed).
- New behaviour comes with tests.

## Versioning

Semantic versioning. The project stays on `0.x` until the Ikanos engine reaches `1.0.0` GA.
