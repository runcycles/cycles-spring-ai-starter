# Changelog

All notable changes to `cycles-spring-ai-starter` will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-05-12

Three new extension points and a trace-correlation tag. Nothing in 0.3.0 breaks v0.2.0 callers — the new behaviors are off by default or controlled by `@ConditionalOnMissingBean` beans that supplement (not replace) v0.2.0 defaults.

### Added

- **Pluggable `SubjectResolver`.** New interface `io.runcycles.client.java.springai.subject.SubjectResolver` — `Subject resolveSubject(ChatClientRequest)`. Default impl `PropertiesSubjectResolver` preserves v0.2.0 behavior (reads tenant/workspace/app from `CyclesProperties` on every call, ignores the request). Users register a custom `SubjectResolver` bean for per-call attribution (e.g. tenant from an authenticated principal); auto-config's default backs off via `@ConditionalOnMissingBean`. The request parameter is `null` on the tool-gating path; implementations should handle `null` defensively. Threaded through `CyclesBudgetAdvisor`, `CyclesBudgetStreamAdvisor`, `CyclesToolGate`, `CyclesToolCallback`, and `CyclesBudgetLifecycle` via new constructor overloads (old constructors preserved for backward compatibility).
- **Pluggable `PromptTokenEstimator`.** New interface `io.runcycles.client.java.springai.tokenizer.PromptTokenEstimator` — `long estimateTokens(ChatClientRequest)`. Default impl `CharsPerTokenEstimator` preserves v0.2.0 `chars / 4` heuristic; also exposes an explicit-ratio constructor for tuning (e.g. ratio=1 for CJK-heavy content). Optimization: the lifecycle short-circuits before invoking the estimator when `estimate-from-prompt=false` OR both cost-per-token rates are 0.
- **`JtokkitPromptTokenEstimator`** — real BPE encoding via `com.knuddels:jtokkit` (`optional=true` Maven dep). Supports `cl100k_base` (gpt-3.5-turbo, gpt-4), `o200k_base` (gpt-4o family), `p50k_base`, `p50k_edit`, `r50k_base`. Opt in via `cycles.spring-ai.token-estimator-encoding=<name>`. When the property is set but jtokkit isn't on the classpath, the auto-config logs a WARN at startup and falls back to chars/4. Unknown encoding name fails bean initialization at startup (not silently at first call).
- **`cycles.reservation_id` on chat-client observations.** When `CyclesChatClientObservationConvention` is applied, the advisor stores the active reservation_id in `request.context()` after a successful reserve, and the convention emits it as a high-cardinality KeyValue at observation-stop time. Enables trace ↔ Cycles reservation correlation in tracing backends. Disable via `cycles.spring-ai.emit-reservation-id-on-trace=false`. New internal constants class `CyclesObservationContextKeys` holds the well-known context key (`cycles.reservation_id`) so the advisor and convention agree on spelling.
- **End-to-end integration test** — `CyclesSpringAiIntegrationTest` boots a Spring context with the real auto-configuration active, a mock `CyclesClient`, and a stub `ChatModel`, and verifies the advisor attachment + reserve/call/commit lifecycle through real `chatClient.prompt(...).call()` invocations. Covers happy path, user-provided `SubjectResolver` override, observation convention availability for opt-in, and reservation_id key constant alignment.

### Configuration properties (new)

- `cycles.spring-ai.token-estimator-encoding` (string, default unset). When set + jtokkit on classpath, swaps the chars/4 default for real BPE encoding.
- `cycles.spring-ai.emit-reservation-id-on-trace` (boolean, default `true`). Operator opt-out for the high-cardinality reservation_id tag.

### Auto-configuration

Now wires seven beans (each `@ConditionalOnMissingBean` so users can override): the existing five (`CyclesBudgetAdvisor`, `CyclesBudgetStreamAdvisor`, `ChatClientCustomizer`, `CyclesToolGate`, `CyclesChatClientObservationConvention`) plus the new `SubjectResolver` (`PropertiesSubjectResolver` default) and `PromptTokenEstimator` (`CharsPerTokenEstimator` default, or `JtokkitPromptTokenEstimator` when the encoding property is set + jtokkit on classpath).

### Internal

- `CyclesBudgetLifecycle.buildSubject()` removed; delegates to the injected `SubjectResolver`. The old inline subject-building logic now lives in `PropertiesSubjectResolver`.
- `CyclesBudgetLifecycle.extractPromptCharCount()` removed; delegates to the injected `PromptTokenEstimator`. The chars/4 logic now lives in `CharsPerTokenEstimator`.
- `CyclesChatClientObservationConvention.getHighCardinalityKeyValues()` no longer calls `super` — Spring AI's default impl NPEs on insufficiently-stubbed contexts and emits nothing we currently care about. Documented in the source.
- Test bundle: 142 tests across 12 test classes (up from 93 in v0.2.0). Bundle coverage gate met (`mvn -B clean verify` passes the jacoco `check` rule with 24 missed / 1108 covered instructions, 6 missed / 102 covered branches; full breakdown in TEST_COVERAGE_GAPS.md).

### Dependencies

- `com.knuddels:jtokkit:1.1.0` added as `optional=true` Maven dependency. Consumers who opt in to the jtokkit estimator must add it explicitly to their app pom.

## [0.2.0] — 2026-05-12

All v0.1.0 "known limitations" addressed. This release lands streaming-chat gating, real `ChatResponse.Usage` extraction on commit, prompt-based per-call estimates, `ToolCallback` decoration via `CyclesToolGate`, and a chat-client `ObservationConvention` for Cycles attribution on traces. See per-feature entries below.

### Added

- **Streaming chat gating via `CyclesBudgetStreamAdvisor`.** Mirrors the `CyclesBudgetAdvisor` lifecycle for `chatClient.prompt(...).stream()` invocations. The full pipeline runs inside `Flux.defer(...)`: reservation happens **per subscription** (no leak when the Flux is assembled but never subscribed; resubscribing produces a fresh reservation). Reserve failures (denial, transport) surface as `onError` rather than synchronous throws. Commit runs inside `concatWith(Mono.defer(...))` so fail-closed commit failures surface as `onError` to the subscriber — matching the non-streaming advisor's fail-fast contract. Releases on stream error, subscriber cancellation, or `chain.nextStream` throwing during assembly.
- **Real `ChatResponse.Usage` extraction on commit** (applies to both the call and stream advisors). Reads token usage from the chat response after a successful call/completion and commits the actual cost instead of the pre-call estimate. Three modes:
  - `estimate-unit=TOKENS`: commits total tokens from `Usage.getTotalTokens()`.
  - `input-cost-per-token` and/or `output-cost-per-token` configured: commits `(promptTokens × inputRate) + (completionTokens × outputRate)`.
  - Otherwise: continues to commit the estimate as actual (v0.1.0-compatible fallback).
- **Prompt-based reservation estimate.** New `cycles.spring-ai.estimate-from-prompt` boolean property (default `false`). When enabled with `input-cost-per-token` and/or `output-cost-per-token` set, the pre-call reservation is computed from the prompt char count rather than the fixed `default-estimate`. Token approximation: `prompt-chars / 4`; reservation amount: `tokens × (inputRate + outputRate)`. Falls back to `default-estimate` when prompt text is empty, rates are 0, or the computed estimate would be 0. Applies to both the call and stream advisors.
- **Tool-level budget gating via `CyclesToolCallback`.** Wrapper class around Spring AI `ToolCallback` that reserves before each tool invocation, commits on success, releases on exception. Tool reservations commit `default-estimate` as actual — tool callbacks don't expose token usage to the gate. Action labels are reported separately from chat: `cycles.spring-ai.tool-action-kind` (default `tool.call`) and `cycles.spring-ai.tool-action-name-prefix` (default `spring-ai-tool:`, the wrapped tool's name is appended). Auto-configured `CyclesToolGate` factory bean — users opt in by calling `cyclesToolGate.wrap(myTool)` where they construct their tools.
- **`CyclesChatClientObservationConvention`** — Spring AI `ChatClientObservationConvention` that extends `DefaultChatClientObservationConvention` and appends low-cardinality Cycles attribution tags to every chat-client trace: `cycles.tenant`, `cycles.workspace`, `cycles.app`, `cycles.action_kind`, `cycles.action_name`. Auto-configured as a bean but **not auto-attached** to the ChatClient.Builder — users opt in via `builder.observationConvention(cyclesConvention)` so trace visibility remains a deliberate choice. Per-call high-cardinality identifiers (reservation IDs) are intentionally excluded; that surface can be added in a future release if there's demand for trace ↔ reservation correlation. Null SDK properties (tenant/workspace/app) are substituted as `unknown` to satisfy Micrometer's KeyValues non-null contract.

### Configuration properties (new)

- `cycles.spring-ai.input-cost-per-token` (long, default `0`). Rejected at binding time when negative.
- `cycles.spring-ai.output-cost-per-token` (long, default `0`). Rejected at binding time when negative.
- `cycles.spring-ai.estimate-from-prompt` (boolean, default `false`).
- `cycles.spring-ai.tool-action-kind` (string, default `tool.call`).
- `cycles.spring-ai.tool-action-name-prefix` (string, default `spring-ai-tool:`).

### Auto-configuration

Now wires five beans (each with `@ConditionalOnMissingBean` so users can override): `CyclesBudgetAdvisor`, `CyclesBudgetStreamAdvisor`, the `ChatClientCustomizer` (bean name `cyclesChatClientCustomizer`) that attaches both advisors, `CyclesToolGate` (tool-wrapper factory, not auto-applied), and `CyclesChatClientObservationConvention` (not auto-attached to builders).

### Internal

- Reserve / commit / release plumbing extracted to `CyclesBudgetLifecycle`, shared by the call advisor, stream advisor, and tool callback. Promoted to `public` (marked **internal API** in javadoc) so the new tool package can reuse it. The lifecycle accepts explicit action-kind / action-name labels so tool reservations are distinguishable from chat reservations in audit history.
- Build dependency: `spring-boot-dependencies` BOM imported alongside `spring-ai-bom` in `dependencyManagement` so `reactor-test` (used by the streaming tests) has a managed version.
- Test bundle: 93 tests across 6 test classes; 100% instruction / 100% branch coverage on the bundle.

## [0.1.0] — 2026-05-12

### Added
- `CyclesBudgetAdvisor` — Spring AI `CallAdvisor` that performs the reserve → call → commit/release lifecycle against the Cycles server for every `chatClient.prompt(...).call()` invocation. Pre-call denials throw `CyclesBudgetDeniedException` before the LLM is contacted. Reserves the estimate before delegating to `chain.nextCall`; commits the estimate as actual on success; releases the reservation on chain exception.
- `CyclesChatClientCustomizer` (auto-configured) — the `ChatClientCustomizer` bean that actually attaches the advisor to the auto-built `ChatClient.Builder` via `builder.defaultAdvisors(advisor)`. Exposing only the `CallAdvisor` bean is **not** sufficient in Spring AI 1.0+; the customizer is the supported wiring path.
- `CyclesBudgetDeniedException` — public exception carrying `reasonCode` and `scopePath` from the Cycles denial response so callers can branch on specific failure modes.
- `CyclesSpringAiAutoConfiguration` — gated on `ChatClient` and `ChatClientCustomizer` classes on the classpath, on a `CyclesClient` bean being present (provided by the underlying `cycles-client-java-spring` auto-configuration), and on `cycles.spring-ai.enabled` (default true).
- `CyclesSpringAiProperties` — configuration surface under `cycles.spring-ai.*`: `enabled`, `default-estimate`, `estimate-unit`, `action-kind`, `action-name`, `fail-open`.
- Demo module `cycles-spring-ai-demo` showing the wiring against the Spring AI OpenAI starter.
- Comprehensive unit test coverage of the reserve / call / commit / release matrix including malformed 2xx response handling, commit-failure-doesn't-release semantics, and release HTTP failure logging. 100% bundle instruction coverage (jacoco `check` rule ≥ 95%).

### Known limitations (all addressed in 0.2.0)
- Streaming chat (`StreamAdvisor`) was not yet covered.
- Estimate was a fixed constant (`default-estimate`); no per-call dynamic estimates.
- Commit used estimate as actual; no real `ChatResponse.Usage` token-count extraction.
- No `ToolCallback` decoration; no tool-level authority gates.
- No `ObservationConvention`; no richer audit-trail attribution.

### Dependencies
- Spring Boot 3.5.3
- Spring AI 1.0.0 (BOM-managed; verified compatible up through 1.1.6 via Dependabot bump)
- `io.runcycles:cycles-client-java-spring:0.2.2` (transitive, provides the HTTP client)
