[![CI](https://github.com/runcycles/cycles-spring-ai-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/runcycles/cycles-spring-ai-starter/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-100%25-brightgreen)](https://github.com/runcycles/cycles-spring-ai-starter/actions)

# Cycles Spring AI Starter — runtime authority for Spring AI agents

**Spring AI advisor + auto-configuration that adds budget enforcement to `ChatClient` invocations.** Integrates with the [Cycles Protocol](https://github.com/runcycles/cycles-protocol) for runtime authority over LLM spend, multi-tenant agent governance, and tamper-evident audit. Built for production Spring AI applications that need to gate LLM calls *before* they hit the provider.

Per-call lifecycle: **reserve → call → commit** on success, **reserve → call → release** on exception. When the Cycles server denies the reservation, the LLM call never happens and a `CyclesBudgetDeniedException` is thrown. Compatible with Java 21+, Spring Boot 3.5+, and Spring AI 1.0+.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.runcycles</groupId>
    <artifactId>cycles-spring-ai-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

> **Note on versions.** `0.1.0` is the latest **released** version on Maven Central. Features documented below under "What's new in `0.2.0-SNAPSHOT`" — tool gating via `CyclesToolGate`, the `CyclesChatClientObservationConvention`, prompt-based per-call estimates (`estimate-from-prompt`), per-token cost rates (`input-cost-per-token` / `output-cost-per-token`), and streaming gating via `CyclesBudgetStreamAdvisor` — are not in `0.1.0`. They land in the upcoming `0.2.0` release; until then they're available by building `cycles-spring-ai-starter:0.2.0-SNAPSHOT` from `main` and installing locally (`mvn -B install --file cycles-spring-ai-starter/pom.xml -DskipTests`).

This dependency transitively pulls in [`cycles-client-java-spring`](https://github.com/runcycles/cycles-spring-boot-starter) which provides the underlying HTTP client to the Cycles server.

### 2. Configure connection + subject

In `application.yml`:

```yaml
cycles:
  base-url: http://localhost:7878      # Cycles server URL
  api-key:  ${CYCLES_API_KEY}          # provisioned via Cycles Admin
  tenant:    acme-corp                 # subject defaults applied to every call
  workspace: production
  app:       order-agent

cycles.spring-ai:
  enabled: true                        # default true; set false to bypass
  default-estimate: 1000               # default per-call estimate (USD_MICROCENTS)
  estimate-unit: USD_MICROCENTS        # also accepts TOKENS, CREDITS, RISK_POINTS
  action-kind: llm.chat
  action-name: spring-ai-chat
  fail-open: false                     # true = log + proceed on transport errors
```

The first block (`cycles.*`) is owned by the underlying `cycles-client-java-spring` SDK; the second block (`cycles.spring-ai.*`) is owned by this starter.

### 3. Use ChatClient normally

```java
@Service
public class OrderAgent {
    private final ChatClient chatClient;

    public OrderAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String summarize(String order) {
        // Cycles reserves budget BEFORE this call hits the LLM provider.
        // If the budget is exhausted, CyclesBudgetDeniedException is thrown
        // and the LLM call never happens. On success, usage is committed
        // back to Cycles. On exception, the reservation is released.
        return chatClient.prompt()
                .user("Summarize: " + order)
                .call()
                .content();
    }
}
```

No code changes to your call sites. The advisor is auto-attached to every `ChatClient` built from the auto-configured `ChatClient.Builder` via a `ChatClientCustomizer` bean.

### 4. (Optional) Gate tool invocations

For agents that call tools, wrap each `ToolCallback` with the auto-configured `CyclesToolGate` to reserve / commit / release per tool call. Tool reservations report `tool.call` / `spring-ai-tool:<name>` action labels so they're separable from chat reservations in audit history.

```java
@Configuration
class ToolWiring {
    @Bean
    ToolCallback getWeatherTool(CyclesToolGate cyclesToolGate) {
        ToolCallback raw = MethodToolCallback.builder()
                .toolDefinition(ToolDefinition.builder().name("get_weather").build())
                .toolMethod(...)
                .build();
        return cyclesToolGate.wrap(raw); // ← Cycles-gated
    }
}
```

Tool gating is opt-in: Spring AI doesn't provide a hook to auto-decorate every registered tool, so you choose which tools to gate. Currently tool reservations commit `default-estimate` as actual (tool callbacks don't expose token usage to the gate).

### 5. (Optional) Cycles attribution on observability traces

The auto-configured `CyclesChatClientObservationConvention` appends low-cardinality Cycles attribution tags (`cycles.tenant`, `cycles.workspace`, `cycles.app`, `cycles.action_kind`, `cycles.action_name`) to every chat-client trace. Apply it explicitly on a `ChatClient.Builder` to opt in:

```java
@Service
class TracedAgent {
    private final ChatClient chatClient;

    TracedAgent(ChatClient.Builder builder, CyclesChatClientObservationConvention cyclesConvention) {
        this.chatClient = builder
                .observationConvention(cyclesConvention)
                .build();
    }
    // ...
}
```

The bean is auto-configured but NOT auto-attached — applying a convention has cross-cutting trace-visibility implications that should be a deliberate user decision.

## How it works

**Non-streaming chat** (`chatClient.prompt(...).call()`):

| Step | Cycles wire call | Spring AI insertion point |
|---|---|---|
| Pre-call | `POST /v1/reservations` with subject + action + estimate | `CallAdvisor.adviseCall(...)` runs at `HIGHEST_PRECEDENCE + 100` |
| Call | (advisor delegates to `chain.nextCall(request)`) | Spring AI continues advisor chain → provider call |
| Commit on success | `POST /v1/reservations/{id}/commit` with actual amount | After `chain.nextCall` returns |
| Release on error | `POST /v1/reservations/{id}/release` with reason | Catch block re-throws original after release |

**Streaming chat** (`chatClient.prompt(...).stream()`) — same lifecycle adapted to the reactive signal model:

| Step | Cycles wire call | Reactor signal |
|---|---|---|
| Pre-stream | `POST /v1/reservations` | Synchronous, before subscribing to the upstream Flux |
| Stream | (advisor passes chunks through, tracking last seen) | `doOnNext(lastResponse::set)` |
| Commit on complete | `POST /v1/reservations/{id}/commit` with usage from the last chunk | `doFinally` when terminal was `ON_COMPLETE` |
| Release on error | `POST /v1/reservations/{id}/release` | `doOnError` |
| Release on cancel | `POST /v1/reservations/{id}/release` | `doOnCancel` |

**Tool invocations** (when wrapped via `CyclesToolGate.wrap`):

| Step | Cycles wire call | Tool insertion point |
|---|---|---|
| Pre-call | `POST /v1/reservations` with `tool.call` action kind | Before `delegate.call(...)` |
| Call | (wrapper delegates to the wrapped tool) | Spring AI invokes the tool |
| Commit on success | `POST /v1/reservations/{id}/commit` with `default-estimate` as actual | After delegate returns |
| Release on exception | `POST /v1/reservations/{id}/release` | Wrapper re-throws original after release |

Both chat advisors are registered automatically via Spring AI's `ChatClientCustomizer` mechanism — `ChatClientAutoConfiguration` discovers customizer beans and applies them to the builder. **Simply exposing a `CallAdvisor` bean is not enough** in Spring AI 1.0+ — the customizer is the supported wiring path. The tool gate and observation convention are exposed as beans for explicit opt-in (see Quick Start steps 4 and 5).

## Compatibility

- **Java**: 21+
- **Spring Boot**: 3.5.x
- **Spring AI**: 1.0.x (BOM-managed; tested compatible with 1.1.x via the post-scaffold Dependabot bump to 1.1.6)

## What's new in `0.2.0-SNAPSHOT`

All known limitations from v0.1.0 have been addressed:

- ✅ **Streaming chat gating.** `CyclesBudgetStreamAdvisor` mirrors the lifecycle of the non-streaming advisor for `chatClient.prompt(...).stream()` invocations. Reserves before subscribing; commits on stream complete; releases on error or subscriber cancellation. Both advisors are auto-attached to the auto-configured `ChatClient.Builder`.
- ✅ **Real `ChatResponse.Usage` extraction on commit** — when the LLM provider returns usage and either `input-cost-per-token` / `output-cost-per-token` are configured (or `estimate-unit=TOKENS`), the advisor commits the actual cost computed from tokens rather than the estimate. Falls back to estimate-as-actual when usage data is missing. Applies to both the call and stream advisors (the stream advisor uses the last chunk that carried usage).
- ✅ **Prompt-based per-call estimate.** When `cycles.spring-ai.estimate-from-prompt=true` and one of the cost-per-token rates is configured, the pre-call reservation is sized from the prompt's character count (chars / 4 → tokens) rather than the fixed `default-estimate`. Falls back to `default-estimate` when the prompt is empty or rates are zero.
- ✅ **`ToolCallback` decoration.** `CyclesToolCallback` wraps any Spring AI `ToolCallback` with the same reserve / commit / release lifecycle. Users opt in via the auto-configured `CyclesToolGate.wrap(...)` factory. Tool reservations report distinct `tool.call` / `spring-ai-tool:<name>` action labels so they're separable from chat reservations in audit history.
- ✅ **`ObservationConvention` for chat-client traces.** `CyclesChatClientObservationConvention` extends Spring AI's default convention and appends low-cardinality Cycles attribution tags (`cycles.tenant`, `cycles.workspace`, `cycles.app`, `cycles.action_kind`, `cycles.action_name`) to every chat-client observation. Auto-configured as a bean but NOT auto-attached — users apply it explicitly via `chatClientBuilder.observationConvention(cyclesConvention)`.

## Configuration reference

| Property | Default | Description |
|---|---|---|
| `cycles.spring-ai.enabled` | `true` | Master switch. Set false to disable Cycles wiring entirely. |
| `cycles.spring-ai.default-estimate` | `1000` | Default per-call estimate, in the configured unit. Used unless `estimate-from-prompt=true` derives a per-call value from prompt size. |
| `cycles.spring-ai.estimate-unit` | `USD_MICROCENTS` | Unit for the estimate. Cycles `Unit` enum values: `USD_MICROCENTS`, `TOKENS`, `CREDITS`, `RISK_POINTS`. |
| `cycles.spring-ai.action-kind` | `llm.chat` | Action.kind label reported to Cycles. |
| `cycles.spring-ai.action-name` | `spring-ai-chat` | Action.name label reported to Cycles. |
| `cycles.spring-ai.fail-open` | `false` | When true, transport errors against Cycles are logged and the LLM call proceeds. Budget denials are always surfaced. |
| `cycles.spring-ai.input-cost-per-token` | `0` | Per-input-token cost in the estimate unit. When set (with `output-cost-per-token`), the advisor commits actual token-based cost instead of the estimate. Example: 25 (= $2.50/1M tokens for OpenAI gpt-4o input). |
| `cycles.spring-ai.output-cost-per-token` | `0` | Per-output-token cost. Example: 100 (= $10.00/1M tokens for OpenAI gpt-4o output). |
| `cycles.spring-ai.estimate-from-prompt` | `false` | When `true` and at least one cost-per-token rate is set, sizes the pre-call reservation from the prompt char count (`chars / 4` × combined rate). Falls back to `default-estimate` when the prompt is empty or rates are zero. |
| `cycles.spring-ai.tool-action-kind` | `tool.call` | Action.kind label reported for `CyclesToolCallback`-wrapped tool invocations (distinct from chat's `action-kind`). |
| `cycles.spring-ai.tool-action-name-prefix` | `spring-ai-tool:` | Prefix prepended to the wrapped tool's name to produce the action.name label (e.g. `spring-ai-tool:get_weather`). |

Connection + subject properties (`cycles.base-url`, `cycles.api-key`, `cycles.tenant`, etc.) come from [`cycles-client-java-spring`](https://github.com/runcycles/cycles-spring-boot-starter) — see that repo's README for the full list.

## Relationship to cycles-spring-boot-starter

The two Java integrations are **complementary, not competing** — they target different layers of the same problem. This starter actually *depends on* `cycles-spring-boot-starter` and reuses its `CyclesClient`, `CyclesProperties`, and connection plumbing.

### What each one is

| Aspect | [`cycles-spring-boot-starter`](https://github.com/runcycles/cycles-spring-boot-starter) | `cycles-spring-ai-starter` (this repo) |
|---|---|---|
| Maven artifact | `io.runcycles:cycles-client-java-spring` | `io.runcycles:cycles-spring-ai-starter` |
| Integration mechanism | Spring AOP via `@Cycles` annotation | Spring AI `CallAdvisor` + `ChatClientCustomizer` |
| Where it intercepts | Any Java method you annotate | Every `chatClient.prompt(...).call()` invocation |
| Granularity | Method-level, explicit opt-in | Framework-level, transparent |
| Call-site changes | Yes — annotate methods with `@Cycles` | No — wired automatically |
| Estimate computation | SpEL: `@Cycles("#tokens * 10")` (dynamic per-call) | `default-estimate`, or prompt-char × token-rate when `estimate-from-prompt=true` |
| Subject routing | SpEL: can pull tenant from method args | Constant from `cycles.tenant/workspace/app` properties |
| Knows about LLMs? | No — generic | Yes — Spring AI ChatClient specific |
| Scope | Any cost-incurring Java code | Only Spring AI chat calls |

In one line: the Java/Spring starter is a **method-level** integration where you decide where to put the gates. This starter is a **framework-level** integration where every Spring AI call surface is gated transparently.

### When to use which

**Use [`cycles-spring-boot-starter`](https://github.com/runcycles/cycles-spring-boot-starter) when:**

- You call LLMs through code that is **not** Spring AI's `ChatClient` — direct HTTP calls, custom OpenAI / Anthropic / Bedrock SDKs, LangChain4j, in-house wrappers, etc.
- You want **per-method dynamic estimates** via SpEL (e.g. `@Cycles("#tokens * 10")` where `#tokens` is a method arg).
- You want **per-method subject routing** — extract tenant from a DTO, request context, or thread-local.
- You want **explicit control** over which methods are gated, not blanket coverage.
- You're cost-gating non-LLM operations: vector-store queries, document processing, third-party metered APIs.
- You're not using Spring AI at all.

**Use `cycles-spring-ai-starter` (this repo) when:**

- You're using Spring AI's `ChatClient` as your LLM call surface.
- You want **transparent gating** of every chat call without touching call sites.
- You want **minimal integration friction** — add the dep, set 6 properties, done. (Per-call estimates from prompt size are available via `estimate-from-prompt=true`; for richer dynamic estimates use the `cycles-spring-boot-starter` SpEL surface.)

**Use both when:**

- You have a Spring AI app that *also* has non-Spring-AI cost-incurring code (e.g., a service method that runs a vector-store query and then a Spring AI chat call — the vector store has cost, the chat has cost).
- They wire on different conditions and don't conflict at the bean-wiring layer.

Because this starter declares a dependency on `cycles-client-java-spring`, the `@Cycles` annotation is *always* on your classpath when you use this starter — no need to explicitly add the other dependency to use both.

### ⚠️ The double-charge gotcha

The two starters are designed to coexist, but you can accidentally double-charge if you wrap a Spring AI chat call inside an `@Cycles`-annotated method:

```java
@Service
class SummaryService {
    @Cycles("#tokens * 10")                       // ← Reservation #1 (AOP)
    public String summarize(String text, int tokens) {
        return chatClient.prompt()                // ← Reservation #2 (Spring AI advisor)
                         .user(text)
                         .call()
                         .content();
    }
}
```

That method consumes budget *twice* for one user-perceivable operation. Both reservations charge against the same budget.

**Rule of thumb:** pick one strategy per call path.

| Your call path | Use |
|---|---|
| Spring AI `ChatClient.call()` directly | `cycles-spring-ai-starter` alone — don't also `@Cycles` the caller |
| LLM via a non-Spring-AI client | `cycles-spring-boot-starter` with `@Cycles` on the method |
| Non-LLM cost-incurring operation | `cycles-spring-boot-starter` with `@Cycles` on the method |
| Method that *both* does non-LLM work *and* a Spring AI chat call | Either `@Cycles` (charging once for the whole method) **or** let the Spring AI advisor handle just the chat part — not both |

### One-line recommendation

- **Pure Spring AI app**: this starter alone. Transparent gating, no code changes.
- **Pure non-Spring-AI Java/Spring app**: [`cycles-spring-boot-starter`](https://github.com/runcycles/cycles-spring-boot-starter) with `@Cycles` on the methods that cost money.
- **Mixed**: depend on this starter (you get the other transitively), use `@Cycles` for non-LLM paths, let the Spring AI advisor handle Spring AI paths, and don't combine them on the same path.

## Project layout

```
cycles-spring-ai-starter/
├── cycles-spring-ai-starter/        ← the library (published to Maven Central)
└── cycles-spring-ai-demo/           ← a runnable demo app (not published)
```

## Development

```bash
mvn -B verify --file cycles-spring-ai-starter/pom.xml
mvn -B install --file cycles-spring-ai-starter/pom.xml -DskipTests
mvn -B verify --file cycles-spring-ai-demo/pom.xml
```

(In Claude Code remote environments, use `mvn-proxy` instead of `mvn` — see [CLAUDE.md](./CLAUDE.md).)

## License

Apache 2.0 — see [LICENSE](./LICENSE).

## Org-wide policies

- [Security Policy](https://github.com/runcycles/.github/blob/main/SECURITY.md)
- [Code of Conduct](https://github.com/runcycles/.github/blob/main/CODE_OF_CONDUCT.md)
- [Contributing Guide](https://github.com/runcycles/.github/blob/main/CONTRIBUTING.md)
