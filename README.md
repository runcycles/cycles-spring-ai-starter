[![CI](https://github.com/runcycles/cycles-spring-ai-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-spring-ai-starter/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-100%25-brightgreen)](https://github.com/runcycles/cycles-spring-ai-starter/actions)

# Cycles Spring AI Starter — runtime authority for Spring AI agents

**Spring AI advisor + auto-configuration that adds budget enforcement, action authority, and audit trails to ChatClient invocations.** Integrates with the [Cycles Protocol](https://github.com/runcycles/cycles-protocol) for runtime authority over LLM spend, tool actions, and per-tenant agent governance — multi-tenant, observable, and Spring AI-native.

Built for production Spring AI applications that need to gate LLM calls *before* they hit the provider (budget exceeded → deny), wrap tool invocations with approval policies, and emit a tamper-evident audit trail tied to subject/tenant attribution. Compatible with Java 21+, Spring Boot 3.5+, and Spring AI 1.0+.

> **Status:** scaffolding. v0.1.0 has not shipped yet; see [CHANGELOG.md](./CHANGELOG.md) and [TEST_COVERAGE_GAPS.md](./TEST_COVERAGE_GAPS.md) for what's pending. For a production-ready Spring Boot integration on the non-Spring-AI path (generic `@Cycles` annotation, SpEL routing), use [cycles-spring-boot-starter](https://github.com/runcycles/cycles-spring-boot-starter) (`io.runcycles:cycles-client-java-spring`).

## What it does

Spring AI gives you observability via Micrometer (traces, metrics, observation conventions). It does **not** give you:

- **Budget enforcement** — pre-call gate that denies invocations when a tenant/subject is over budget.
- **Action authority** — human-in-the-loop or rule-based approval for sensitive tool calls.
- **Cross-call correlation with attribution** — tying agent behavior back to a specific subject/tenant for audit.
- **Cost / token tracking against budget** — recording actual usage against committed reservations.

This starter wires Cycles' runtime authority into Spring AI's extension points so you get all four without writing glue code.

## Insertion points used

| Cycles capability | Spring AI extension point |
|---|---|
| Budget enforcement (pre-call gate) | `CallAdvisor` (chat advisors) |
| Action authority / approval gates | `ToolCallback` decorator |
| Audit trail with attribution | `ObservationConvention` + `Observation.Handler` |
| Cost / token tracking | Post-`CallAdvisor` hook reading `ChatResponse.Usage` |

## Quick start (planned — v0.1.0)

Add the starter:

```xml
<dependency>
  <groupId>io.runcycles</groupId>
  <artifactId>cycles-spring-ai-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Configure in `application.yml`:

```yaml
cycles:
  spring-ai:
    enabled: true
    budget-id: my-tenant-budget
    server-url: http://cycles-server:8080
    fail-open: false
```

That's it — the auto-configuration wires the `CyclesBudgetAdvisor` onto the auto-configured `ChatClient.Builder`. All `ChatClient` invocations now pass through Cycles for budget enforcement before reaching the LLM, and usage is recorded back after the response returns.

## How it sits alongside cycles-spring-boot-starter

If your app uses Spring AI: depend on `cycles-spring-ai-starter` (this).

If your app uses non-Spring-AI Spring Boot (generic AOP via `@Cycles` annotation, SpEL routing): depend on [`cycles-spring-boot-starter`](https://github.com/runcycles/cycles-spring-boot-starter).

You can depend on both if you have a mixed codebase — the two starters wire on different conditions and do not conflict.

## Compatibility

- **Java**: 21+
- **Spring Boot**: 3.5.x line (matches the existing Cycles Java SDK posture)
- **Spring AI**: 1.0.x line (will track 1.1.x and beyond once the API matures; pinning rationale recorded in [AUDIT.md](./AUDIT.md))

## Project layout

```
cycles-spring-ai-starter/
├── cycles-spring-ai-starter/        ← the library (published to Maven Central)
└── cycles-spring-ai-demo/           ← a runnable demo app (not published)
```

## Development

```bash
mvn -B verify --file cycles-spring-ai-starter/pom.xml
mvn -B verify --file cycles-spring-ai-demo/pom.xml
```

(In Claude Code remote environments, use `mvn-proxy` instead of `mvn` — see [CLAUDE.md](./CLAUDE.md).)

## License

Apache 2.0 — see [LICENSE](./LICENSE).

## Org-wide policies

- [Security Policy](https://github.com/runcycles/.github/blob/main/SECURITY.md)
- [Code of Conduct](https://github.com/runcycles/.github/blob/main/CODE_OF_CONDUCT.md)
- [Contributing Guide](https://github.com/runcycles/.github/blob/main/CONTRIBUTING.md)
