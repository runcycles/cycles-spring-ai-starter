# Changelog

All notable changes to `cycles-spring-ai-starter` will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial project scaffold (CI, docs, package layout, auto-configuration skeleton).
- Skeleton `CyclesBudgetAdvisor` implementing Spring AI `CallAdvisor` — pre-call budget check, post-call usage record. Currently a no-op pending integration with the Cycles runtime client.
- Auto-configuration: `CyclesSpringAiAutoConfiguration` registers the advisor on the auto-configured `ChatClient.Builder` when `cycles.spring-ai.enabled=true` and Spring AI is on the classpath.
- Configuration properties: `cycles.spring-ai.enabled`, `cycles.spring-ai.budget-id`, `cycles.spring-ai.fail-open`, `cycles.spring-ai.server-url`.

## [0.1.0] — TBD

First release planned for v0.1.0 once `CyclesBudgetAdvisor` integrates with the Cycles runtime client and integration tests pass against at least one chat provider (Ollama in Testcontainers).
