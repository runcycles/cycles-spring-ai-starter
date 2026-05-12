# AUDIT.md — cycles-spring-ai-starter

This file tracks **protocol-surface** and **public-API** changes for the Spring AI starter. Per the project rule (see [CLAUDE.md](./CLAUDE.md)): update this file whenever the public Java API, configuration property surface, or auto-configuration behavior changes.

## What "protocol surface" means here

The Spring AI starter does not own its own protocol — it delegates to the Cycles runtime via the existing client surface in [cycles-spring-boot-starter](https://github.com/runcycles/cycles-spring-boot-starter) (and through it, the [cycles-protocol](https://github.com/runcycles/cycles-protocol) YAML spec). Changes recorded here are limited to:

- Public Java types under `io.runcycles.client.java.springai.*`
- `@ConfigurationProperties` keys (`cycles.spring-ai.*`)
- Auto-configuration ordering / conditions
- Advisor `@Order` values and the contract they imply
- Tool-callback decoration semantics that affect downstream tool selection

## Initial public surface (v0.1.0 — TBD)

| Component | FQCN | Stability |
|---|---|---|
| Auto-configuration | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration` | API stable |
| Configuration properties | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties` (prefix: `cycles.spring-ai`) | API stable |
| Budget advisor | `io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor` | Skeleton — API may change before v0.1.0 |

## Property keys

| Key | Type | Default | Notes |
|---|---|---|---|
| `cycles.spring-ai.enabled` | boolean | `true` | Master switch; when `false`, no auto-configured beans register. |
| `cycles.spring-ai.budget-id` | String | (none) | Required when enabled. Identifies which Cycles budget applies to this application's calls. |
| `cycles.spring-ai.fail-open` | boolean | `false` | When `true`, advisor errors (e.g., Cycles server unreachable) are logged and the call proceeds; when `false`, the advisor surfaces the error. |
| `cycles.spring-ai.server-url` | String | `http://localhost:8080` | Cycles server URL. |

## Change log

(no entries yet — initial scaffold)
