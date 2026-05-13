# AUDIT.md — cycles-spring-ai-starter

This file tracks **protocol-surface** and **public-API** changes. Per the project rule (see [CLAUDE.md](./CLAUDE.md)): update this file whenever the public Java API, configuration property surface, or auto-configuration behavior changes.

## What "protocol surface" means here

The Spring AI starter does not own its own protocol — it delegates to the Cycles runtime via the existing client surface in [cycles-spring-boot-starter](https://github.com/runcycles/cycles-spring-boot-starter) (and through it, the [cycles-protocol](https://github.com/runcycles/cycles-protocol) YAML spec). Changes recorded here are limited to:

- Public Java types under `io.runcycles.client.java.springai.*`
- `@ConfigurationProperties` keys (`cycles.spring-ai.*`)
- Auto-configuration ordering / conditions
- Advisor `@Order` values and the contract they imply

## Public surface (v0.1.0)

| Component | FQCN | Stability |
|---|---|---|
| Auto-configuration | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiAutoConfiguration` | API stable |
| Configuration properties | `io.runcycles.client.java.springai.autoconfigure.CyclesSpringAiProperties` (prefix: `cycles.spring-ai`) | API stable |
| Budget advisor (non-streaming) | `io.runcycles.client.java.springai.advisor.CyclesBudgetAdvisor` | API stable |
| Denial exception | `io.runcycles.client.java.springai.CyclesBudgetDeniedException` | API stable |

## Public surface added in 0.2.0-SNAPSHOT

| Component | FQCN | Notes |
|---|---|---|
| Budget advisor (streaming) | `io.runcycles.client.java.springai.advisor.CyclesBudgetStreamAdvisor` | Mirrors the call advisor's lifecycle for `ChatClient.stream()` invocations. Reserves before subscribing; commits on `ON_COMPLETE` using usage from the last chunk; releases on error or cancel |
| Lifecycle helper (internal API) | `io.runcycles.client.java.springai.advisor.CyclesBudgetLifecycle` | Promoted to `public` from package-private so the new tool package can reuse the reserve / commit / release plumbing. Marked **internal API** in javadoc — surface may change between minor releases |
| Tool callback decorator | `io.runcycles.client.java.springai.tool.CyclesToolCallback` | Spring AI `ToolCallback` wrapper that reserves / commits / releases per tool invocation. Reports `tool.call` / `spring-ai-tool:<name>` action labels (configurable). Commits `default-estimate` as actual (no usage data from tools) |
| Tool gate factory | `io.runcycles.client.java.springai.tool.CyclesToolGate` | Auto-configured factory: users call `cyclesToolGate.wrap(myTool)` to opt in to per-tool gating. Not auto-applied to tools |
| Observation convention | `io.runcycles.client.java.springai.observation.CyclesChatClientObservationConvention` | Extends Spring AI's `DefaultChatClientObservationConvention` to append low-cardinality `cycles.*` attribution tags to chat-client traces. Auto-configured as a bean but not auto-attached — users apply via `chatClientBuilder.observationConvention(...)` |

## Public surface added in 0.3.0-SNAPSHOT

| Component | FQCN | Notes |
|---|---|---|
| Subject resolver | `io.runcycles.client.java.springai.subject.SubjectResolver` | Functional interface: `Subject resolveSubject(ChatClientRequest)`. Auto-configured default backs off via `@ConditionalOnMissingBean` — users register their own bean for per-request subject routing (e.g. tenant from auth principal). The request parameter is `null` on the tool-gating path; implementations must handle `null` |
| Default subject resolver | `io.runcycles.client.java.springai.subject.PropertiesSubjectResolver` | Default impl. Reads tenant/workspace/app/workflow/agent/toolset from `CyclesProperties` on every call (preserves v0.2.0 behavior). Ignores the request parameter |
| Prompt token estimator | `io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator` | Functional interface: `long estimateTokens(ChatClientRequest)`. Auto-configured default backs off via `@ConditionalOnMissingBean`. Used by the lifecycle for prompt-based reservation sizing when `estimate-from-prompt=true` |
| Default token estimator | `io.runcycles.client.java.springai.tokenizer.CharsPerTokenEstimator` | Default impl. Preserves v0.2.0 `chars / 4` heuristic. Constructor variant accepts an explicit ratio for tuning (e.g. ratio=1 for CJK content) |
| jtokkit token estimator | `io.runcycles.client.java.springai.tokenizer.JtokkitPromptTokenEstimator` | Real BPE encoding via `com.knuddels:jtokkit:1.1.0` (`optional=true` Maven dep). Supports `cl100k_base`, `o200k_base`, `p50k_base`, `p50k_edit`, `r50k_base`. Auto-configured when `cycles.spring-ai.token-estimator-encoding` is set + jtokkit on classpath |
| Observation context keys | `io.runcycles.client.java.springai.observation.CyclesObservationContextKeys` | Internal constants — well-known keys used to thread Cycles state through `ChatClientRequest.context()` for the observation convention to read. Only `RESERVATION_ID = "cycles.reservation_id"` currently |

## Property keys (v0.1.0)

| Key | Type | Default | Notes |
|---|---|---|---|
| `cycles.spring-ai.enabled` | boolean | `true` | Master switch; when `false`, no auto-configured beans register. |
| `cycles.spring-ai.default-estimate` | long | `1000` | Default per-call reservation estimate. Used unless `estimate-from-prompt=true` derives one per call. |
| `cycles.spring-ai.estimate-unit` | string | `USD_MICROCENTS` | Cycles `Unit` enum name. Falls back to `USD_MICROCENTS` if unrecognized. |
| `cycles.spring-ai.action-kind` | string | `llm.chat` | Action.kind label reported to Cycles for chat invocations. |
| `cycles.spring-ai.action-name` | string | `spring-ai-chat` | Action.name label reported to Cycles for chat invocations. |
| `cycles.spring-ai.fail-open` | boolean | `false` | When `true`, transport / unexpected errors are logged and the call proceeds. Budget denials (`DENY` decision) are always surfaced regardless. |

## Property keys added in 0.2.0-SNAPSHOT

| Key | Type | Default | Notes |
|---|---|---|---|
| `cycles.spring-ai.input-cost-per-token` | long | `0` | Per-input-token cost in the configured estimate unit. When > 0 (or `output-cost-per-token` > 0), enables real-token-based actual accounting at commit time. Rejected as `IllegalArgumentException` at binding when negative. |
| `cycles.spring-ai.output-cost-per-token` | long | `0` | Per-output-token cost in the configured estimate unit. Same validation as the input rate. |
| `cycles.spring-ai.estimate-from-prompt` | boolean | `false` | When `true` and `input-cost-per-token` and/or `output-cost-per-token` is > 0, pre-call reservation is sized from prompt char count (`chars / 4` token approximation × combined rate). Falls back to `default-estimate` when prompt is empty or rates are zero. |
| `cycles.spring-ai.tool-action-kind` | string | `tool.call` | Action.kind label reported to Cycles for `CyclesToolCallback`-wrapped tool invocations (distinct from chat's `action-kind`). |
| `cycles.spring-ai.tool-action-name-prefix` | string | `spring-ai-tool:` | Prefix prepended to the wrapped tool's name to produce the action.name label (e.g. `spring-ai-tool:get_weather`). |

## Property keys added in 0.3.0-SNAPSHOT

| Key | Type | Default | Notes |
|---|---|---|---|
| `cycles.spring-ai.token-estimator-encoding` | string | _unset_ | When non-null/non-blank AND jtokkit is on the classpath, the auto-config registers `JtokkitPromptTokenEstimator` with this encoding instead of `CharsPerTokenEstimator`. Valid values (case-insensitive): `cl100k_base`, `o200k_base`, `p50k_base`, `p50k_edit`, `r50k_base`. Unknown values fail bean initialization at app startup (not silently at first call). When property set but jtokkit absent, the auto-config logs a WARN and falls back to chars/4. |
| `cycles.spring-ai.emit-reservation-id-on-trace` | boolean | `true` | When `true`, the `CyclesChatClientObservationConvention` emits the active `cycles.reservation_id` as a high-cardinality KeyValue on chat-client observations. Default is `true` because the convention itself is already opt-in (users apply via `builder.observationConvention(...)`). Set to `false` to omit when tracing backend charges by unique tag-value combinations. |

## Auto-configuration conditions

`CyclesSpringAiAutoConfiguration` activates when ALL of:

1. `org.springframework.ai.chat.client.ChatClient` is on the classpath.
2. `org.springframework.ai.chat.client.ChatClientCustomizer` is on the classpath.
3. A `CyclesClient` bean is registered (provided by `cycles-client-java-spring`'s `CyclesAutoConfiguration`).
4. `cycles.spring-ai.enabled=true` (default).

When active, it registers (each with `@ConditionalOnMissingBean` semantics so users can override):

- `SubjectResolver` — default impl `PropertiesSubjectResolver` reads subject defaults from `CyclesProperties` on every call. Override with a custom bean for per-request attribution. **Added in 0.3.0-SNAPSHOT.**
- `PromptTokenEstimator` — default impl `CharsPerTokenEstimator` (chars/4 heuristic), OR `JtokkitPromptTokenEstimator` when `cycles.spring-ai.token-estimator-encoding` is set + jtokkit on the classpath. Override with a custom bean for provider-specific tokenizers. **Added in 0.3.0-SNAPSHOT.**
- `CyclesBudgetAdvisor` — the non-streaming chat advisor.
- `CyclesBudgetStreamAdvisor` — the streaming chat advisor.
- A name-conditional `ChatClientCustomizer` (bean name `cyclesChatClientCustomizer`) that attaches both advisors via `builder.defaultAdvisors(callAdvisor, streamAdvisor)`. Name-keyed so it doesn't back off in the presence of unrelated `ChatClientCustomizer` beans (memory, RAG, etc.).
- `CyclesToolGate` — the tool-wrapper factory (not auto-applied to tools).
- `CyclesChatClientObservationConvention` — the observation convention bean (not auto-attached to builders). As of 0.3.0-SNAPSHOT also emits `cycles.reservation_id` as a high-cardinality KeyValue when applied (controlled by `cycles.spring-ai.emit-reservation-id-on-trace`).

If any condition fails, none of the above register.

## Advisor order

Both `CyclesBudgetAdvisor.getOrder()` and `CyclesBudgetStreamAdvisor.getOrder()` return `Ordered.HIGHEST_PRECEDENCE + 100`. Documented intent: run early enough that budget denial precedes any cost-incurring downstream advisor (e.g. RAG augmentation that incurs vector-store queries; chat-memory advisors that issue extra LLM calls).

Future Cycles advisors (`CyclesAuthorityAdvisor` etc.) should follow the same precedence range to keep "deny before cost" semantics consistent.

## Wire-call contracts per invocation type

### Non-streaming chat (`chatClient.prompt(...).call()`)

1. `POST /v1/reservations` — body includes subject (tenant/workspace/app/workflow/agent/toolset from `CyclesProperties`), action (`action-kind` / `action-name`), estimate (default-estimate or prompt-derived × estimate-unit), idempotency_key (per-call UUID).
2. If decision is `DENY` → `CyclesBudgetDeniedException` thrown, no LLM call.
3. If decision is `ALLOW`/`ALLOW_WITH_CAPS` → `chain.nextCall(request)` proceeds.
4. On success → `POST /v1/reservations/{id}/commit` with actual computed from response usage:
   - `estimate-unit=TOKENS` + usage present → actual = `usage.getTotalTokens()`.
   - `input-cost-per-token` and/or `output-cost-per-token` set + usage present → actual = `(promptTokens × inputRate) + (completionTokens × outputRate)`.
   - Otherwise → actual = estimate (v0.1.0-compatible fallback).
5. On exception → `POST /v1/reservations/{id}/release` with reason = `chat-call-failed: <ExceptionClass>`, then the original exception is re-thrown.

### Streaming chat (`chatClient.prompt(...).stream()`)

The entire pipeline is wrapped in `Flux.defer(...)`, so all of the steps below execute on subscription (not at assembly). Each subscription to the returned Flux creates its own reservation; resubscribing produces a fresh reservation.

1. Reservation step is identical to non-streaming, but executes on subscription. Reservation failures (denial, transport) surface as `onError` to the subscriber rather than as a synchronous throw from `adviseStream`.
2. If `chain.nextStream(request)` throws during assembly after we reserved → release with reason = `chat-stream-assembly-failed: <ExceptionClass>` and re-throw (becomes `onError`).
3. On natural completion of the upstream → commit using usage from the **last emitted chunk** (most providers populate `Usage` only on the final chunk; some never do, in which case the fallback chain applies the same as non-streaming). Commit runs inside `concatWith(Mono.defer(...))` before the subscriber observes terminal `onComplete`, so a commit failure in fail-closed mode surfaces as `onError` to the subscriber — same fail-fast behavior as the non-streaming advisor.
4. On `onError` → release with reason = `chat-stream-failed: <ExceptionClass>`.
5. On cancel (subscriber cancels) → release with reason = `chat-stream-cancelled`.

### Tool invocations (when wrapped via `CyclesToolGate.wrap`)

1. `POST /v1/reservations` with `tool-action-kind` (default `tool.call`) and `tool-action-name-prefix + delegate.getToolDefinition().name()`. Prompt-based estimation never applies (no `ChatClientRequest` available), so the estimate is always `default-estimate`.
2. If decision is `DENY` → `CyclesBudgetDeniedException` thrown, wrapped tool is **not** invoked.
3. On `ALLOW` → delegate to wrapped tool.
4. On success → `POST .../commit` with `default-estimate` as actual (no token-usage signal from tools).
5. On `RuntimeException` from the wrapped tool → `POST .../release` with reason = `tool-call-failed: <ExceptionClass>`, then re-throw.

## Change log

### Unreleased — 2026-05-13
Build-only patch — no public-API, property-key, or wire-protocol changes. Pinned transitive Netty to **4.1.133.Final** (was 4.1.132.Final, managed by `spring-boot-dependencies:3.5.14`) via a `netty-bom` import placed *before* `spring-boot-dependencies` in both `cycles-spring-ai-starter/pom.xml` and `cycles-spring-ai-demo/pom.xml`. Maven's first-import-wins rule applies because Spring Boot's published BOM bakes in literal Netty versions rather than the `${netty.version}` placeholder, so the property override alone is a no-op. Closes the batch of high/medium Netty CVEs flagged by OSSF Scorecard alert #7: GHSA-mj4r-2hfc-f8p6, GHSA-cm33-6792-r9fm, GHSA-38f8-5428-x5cv, GHSA-57rv-r2g8-2cj3, GHSA-f6hv-jmp6-3vwv, GHSA-m4cv-j2px-7723, GHSA-v8h7-rr48-vmmv, GHSA-xxqh-mfjm-7mv9, GHSA-45q3-82m4-75jr, GHSA-rwm7-x88c-3g2p (HTTP smuggling, HttpClientCodec desync, decompression-bomb DoS, DNS codec input validation, HttpProxyHandler header injection, epoll RST half-close DoS).

### 0.3.1 — 2026-05-12
Documentation-only patch. No public-API or property-key changes vs 0.3.0. Cut to ship corrected `inputCostPerToken` / `outputCostPerToken` javadoc + README examples on Maven Central (the 0.3.0 shipped values were off by 10x — see [CHANGELOG.md](./CHANGELOG.md) `[0.3.1]` for the full explanation).

### 0.3.0 — 2026-05-12
See [CHANGELOG.md](./CHANGELOG.md) for the full entry. Surface deltas vs 0.2.0 are captured in the "added in 0.3.0-SNAPSHOT" sub-tables above (sub-table headers preserve the SNAPSHOT wording for historical context — what landed in which dev cycle; the released version is 0.3.0). New extension points (`SubjectResolver`, `PromptTokenEstimator`) and the `cycles.reservation_id` trace correlation tag; no breaking changes to v0.2.0 callers.

### 0.2.0 — 2026-05-12
See [CHANGELOG.md](./CHANGELOG.md) for the full entry. Surface deltas vs v0.1.0 are captured in the "added in 0.2.0-SNAPSHOT" sub-tables above (the sub-table headers preserve "0.2.0-SNAPSHOT" wording so historical context — what landed in which dev cycle — stays readable; the released version is 0.2.0).

### 0.1.0 — 2026-05-12
Initial public surface. See [CHANGELOG.md](./CHANGELOG.md).
