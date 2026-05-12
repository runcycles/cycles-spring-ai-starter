# Changelog

All notable changes to `cycles-spring-ai-starter` will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 0.2.0-SNAPSHOT

### Added
- **Real `ChatResponse.Usage` extraction on commit.** `CyclesBudgetAdvisor` now reads token usage from the chat response after a successful call and commits the actual cost instead of the pre-call estimate. Three modes:
  - `estimate-unit=TOKENS`: commits total tokens from `Usage.getTotalTokens()`.
  - `input-cost-per-token` and/or `output-cost-per-token` configured: commits `(promptTokens × inputRate) + (completionTokens × outputRate)`.
  - Otherwise: continues to commit the estimate as actual (v0.1.0-compatible fallback).
- Two new configuration properties:
  - `cycles.spring-ai.input-cost-per-token` (long, default 0) — per-input-token cost in the configured estimate unit.
  - `cycles.spring-ai.output-cost-per-token` (long, default 0) — per-output-token cost.
- Both new properties reject negative values at config-binding time (same pattern as `default-estimate`).

### Still pending for v0.2
- Streaming chat (`StreamAdvisor`) — non-streaming calls only so far.
- Per-call estimate derivation from prompt token count — estimate is still a fixed constant.
- `ToolCallback` decoration (tool-level authority gates).
- `ObservationConvention` (richer audit-trail attribution).

## [0.1.0] — 2026-05-12

### Added
- `CyclesBudgetAdvisor` — Spring AI `CallAdvisor` that performs the reserve → call → commit/release lifecycle against the Cycles server for every `chatClient.prompt(...).call()` invocation. Pre-call denials throw `CyclesBudgetDeniedException` before the LLM is contacted. Reserves the estimate before delegating to `chain.nextCall`; commits the estimate as actual on success; releases the reservation on chain exception.
- `CyclesChatClientCustomizer` (auto-configured) — the `ChatClientCustomizer` bean that actually attaches the advisor to the auto-built `ChatClient.Builder` via `builder.defaultAdvisors(advisor)`. Exposing only the `CallAdvisor` bean is **not** sufficient in Spring AI 1.0+; the customizer is the supported wiring path.
- `CyclesBudgetDeniedException` — public exception carrying `reasonCode` and `scopePath` from the Cycles denial response so callers can branch on specific failure modes.
- `CyclesSpringAiAutoConfiguration` — gated on `ChatClient` and `ChatClientCustomizer` classes on the classpath, on a `CyclesClient` bean being present (provided by the underlying `cycles-client-java-spring` auto-configuration), and on `cycles.spring-ai.enabled` (default true).
- `CyclesSpringAiProperties` — configuration surface under `cycles.spring-ai.*`: `enabled`, `default-estimate`, `estimate-unit`, `action-kind`, `action-name`, `fail-open`.
- Demo module `cycles-spring-ai-demo` showing the wiring against the Spring AI OpenAI starter.
- Comprehensive unit test coverage of the reserve / call / commit / release matrix including malformed 2xx response handling, commit-failure-doesn't-release semantics, and release HTTP failure logging. 100% bundle instruction coverage (jacoco `check` rule ≥ 95%).

### Known limitations (carried into v0.2)
- Streaming chat (`StreamAdvisor`) is not yet covered — see README "Known limitations".
- Estimate is a fixed constant (`default-estimate`); per-call dynamic estimates from prompt token count land in v0.2.
- Commit uses estimate as actual; real `ChatResponse.Usage` token-count extraction lands in v0.2.
- No `ToolCallback` decoration; tool-level authority gates land in v0.2.
- No `ObservationConvention`; richer audit-trail attribution lands in v0.2.

### Dependencies
- Spring Boot 3.5.3
- Spring AI 1.0.0 (BOM-managed; verified compatible up through 1.1.6 via Dependabot bump)
- `io.runcycles:cycles-client-java-spring:0.2.2` (transitive, provides the HTTP client)
