# AGENTS.md — Sentinel

## Project overview

**Sentinel** is a Java desktop application for monitoring, diagnosing, and tracking problems in interconnected software services. Users register a software system, configure its services and dependencies, monitor health through HTTP checks and Docker status, and investigate incidents. A deterministic, rule-based diagnostic engine uses observations, timestamps, and dependency relationships to produce **explainable findings** and recommend troubleshooting or recovery actions.

Sentinel must distinguish **observed facts** (for example, a failed health check) from **hypotheses** (for example, a database outage may explain an API failure). It must retain the evidence behind its conclusions and avoid claiming causal certainty without support.

## Stack and constraints

- **Language:** Java.
- **Desktop GUI:** **Java Swing**. JavaFX requires instructor approval. **Do not convert the project into a web app.**
- **External API:** Docker Engine API for container status and, if implemented, user-approved container operations. HTTP health endpoints provide additional observations.
- **Persistence:** Local JSON files for system definitions and incident/history data (initial plan; keep persistence behind interfaces).
- **Build and testing:** Use the build system and testing libraries adopted by the repository; do not introduce a new framework without team agreement. Prefer automated unit tests for domain logic.
- **Concurrency:** Run network calls, Docker operations, file I/O, and periodic polling off the Swing Event Dispatch Thread (EDT). Update Swing components only on the EDT.

## Expected core workflows

1. **Configure a monitored system:** Register services, define health checks, and specify directed dependencies.
2. **Monitor health:** Poll configured targets, store timestamped observations, and derive service health transitions with thresholds.
3. **Diagnose incidents:** Apply deterministic rules to observations and dependency graphs; generate findings with traceable evidence and conservatively correlate related failures.
4. **Manage incidents and recovery:** Track incident status/history, present recommended actions, and verify recovery. Require explicit user confirmation before executing changes to running services.

These are proposed use-case boundaries, not a mandate to implement all optional features at once. Favor a small, complete end-to-end flow over unrelated utilities.

## Clean Architecture requirements

This course project **must attempt to follow Clean Architecture and SOLID principles**, and the team must be able to explain and justify design decisions at the final presentation.

- **Entities/domain:** Represent core business concepts and invariants independently of presentation and infrastructure. Examples: `MonitoredSystem`, `Service`, `ServiceDependency`, `HealthObservation`, `FailureEvent`, `DiagnosticFinding`, `Incident`.
- **Use cases/application:** Implement actions such as configuring systems, recording observations, diagnosing failures, and managing incidents. Coordinate domain logic through ports/interfaces.
- **Interface adapters:** Map Swing inputs to use-case input data, present output data to Swing views, and translate external data into domain objects.
- **Frameworks/infrastructure:** Implement Docker/HTTP clients, JSON repositories, and other I/O behind interfaces defined by inner layers.

**Dependency rule:** Source-code dependencies point inward. Domain code must not import Swing, Docker clients, HTTP frameworks, JSON libraries, or persistence implementations. Use cases must not depend on concrete Swing views or infrastructure adapters. Wire concrete implementations at the composition root.

Keep UI controllers thin. Do not place diagnostic logic, incident-correlation rules, or persistence logic in Swing event listeners.

## SOLID and implementation guidelines

- **Single Responsibility:** Each class should have a focused reason to change. Separate health polling, health-state evaluation, diagnostics, incident correlation, and persistence.
- **Open/Closed:** Add new diagnostic rules and monitoring adapters by implementing stable interfaces rather than editing large conditionals throughout the codebase.
- **Liskov Substitution:** All implementations of an interface must honor its documented behavior, error conditions, and invariants.
- **Interface Segregation:** Prefer small capabilities such as health checking, service control, and repository access over one large infrastructure interface.
- **Dependency Inversion:** High-level policies depend on abstractions. Inject gateways, repositories, clocks, and rule collections rather than constructing infrastructure dependencies inside use cases.
- Prefer composition over inheritance, explicit types and names, and immutable value objects/observation records when appropriate.
- Keep one clear source of truth for service configuration. Do not conflate desired configuration with observed health.
- Treat unexpected API responses, unavailable Docker, timeouts, and missing configuration as normal failure cases; report them clearly without crashing the GUI.

## Design patterns — appropriate and justified use

The project should **incorporate appropriate object-oriented design patterns** alongside SOLID and Clean Architecture. The course covers **Factory, Builder, Strategy, Observer, Adapter, and Façade**. Apply patterns where they solve an identifiable design problem; **do not force all six into the codebase** or create unnecessary abstractions just to demonstrate pattern usage. Favor **loose coupling, high cohesion, and delegation/composition** where practical.

Potential applications in Sentinel:

- **Strategy:** Model interchangeable diagnostic rules (`DiagnosticRule`), health-check algorithms, or incident-correlation policies behind clear interfaces. New strategies should be introduced without rewriting their callers.
- **Observer:** Notify interested UI presenters or application components when service health or incident state changes, without coupling monitoring code to Swing. Prefer explicit listener interfaces or `PropertyChangeSupport` over deprecated `java.util.Observable`/`Observer`. Marshal Swing updates onto the EDT.
- **Adapter:** Wrap the Docker Engine API and HTTP health-check clients behind application-facing gateway interfaces. Translate external status codes, errors, and DTOs into domain-specific types.
- **Factory:** Centralize construction of different health-check implementations or monitoring adapters when creation varies by service configuration. A simple constructor is preferable if there is no meaningful variation.
- **Builder:** Construct complex immutable configuration objects, diagnostic reports, or recovery plans step by step when constructors would otherwise have many optional parameters. Do not add builders for trivial records.
- **Façade:** Offer a small, cohesive entry point to a genuinely complex subsystem (for example, coordinated health monitoring) while delegating work to focused components. Do not create a god object that absorbs domain responsibilities.

For each nontrivial pattern introduced:

1. State the **problem** it addresses and why the simpler alternative is insufficient.
2. Identify the **pattern participants** and where their responsibilities live within the architecture.
3. Consider the **tradeoffs** (complexity, testability, extensibility, and coupling).
4. Cover relevant behavior with tests and briefly document the design rationale in the PR or project documentation, so the team can justify it during the final presentation.

Patterns must **respect Clean Architecture's dependency direction**. For example, an Adapter belongs in the outer layer, and a Strategy interface used by domain/application logic must not import its infrastructure implementations.

## Diagnostic and incident rules

- Separate **detection** (what failed), **diagnosis** (what may explain it), and **correlation** (which failures belong to an incident).
- Rules should consume domain data/snapshots and return findings; they must not directly manipulate Swing, call external APIs, or execute recovery actions.
- Each finding should identify the rule, affected service, supporting evidence, explanation, and possible next steps.
- Use failure/recovery thresholds to avoid generating repeated events or incidents for every failed poll.
- Do not merge failures solely because they occur close together. Require relevant dependency relationships and supporting evidence.
- Preserve historical observations and incident events; reevaluate active hypotheses as new evidence arrives.
- Do not automatically kill processes, remove containers, or perform destructive recovery. Require an explicit approved action for any mutation.

## Testing and quality

- Write unit tests for dependency validation/traversal, health-state transitions, detection rules, diagnostic findings, incident correlation, and incident lifecycle transitions.
- Use fake/in-memory implementations of ports to test application logic without requiring Docker, network access, or a running Swing GUI.
- Test negative and edge cases: transient failure, repeated polling, flapping health, unrelated simultaneous failures, ambiguous correlations, partially recovered dependencies, and API errors.
- Where practical, test serialization and external adapters separately with integration tests.
- Keep the application responsive; ensure background tasks can be stopped and exceptions are surfaced meaningfully.

## Team workflow and course expectations

- **Pull requests are required throughout development.** Make focused changes, explain their purpose, and seek teammate review before merging.
- **Each team member must lead at least one use case** and write the majority of the code for that use case. Respect ownership while collaborating on shared interfaces and integration.
- Commit meaningful work regularly. The course evaluates individual contribution and reviews Git history; do not implement a teammate's entire assigned use case for them.
- Attend labs and team meetings or communicate absences; coordinate shared interfaces early to reduce integration conflicts.
- Keep scope aligned with approximately **one user story per team member**.
- The final demonstration should clearly show the Swing UI, an external API, persistence, use-case functionality, and explainable architecture/design decisions.

## Instructions for coding agents

1. Inspect existing project structure, conventions, and tests before editing; follow the repository's established build and package conventions.
2. Identify the relevant use case and layer before adding or changing code. Do not bypass boundaries for convenience.
3. Make small, reviewable changes. Avoid unnecessary dependencies, speculative abstractions, or unrelated refactors.
4. Add or update tests whenever behavior or domain rules change.
5. Do not invent monitoring observations or claim a diagnosis is proven when evidence is incomplete.
6. Do not introduce JavaFX, web-application architecture, or destructive automatic remediation without explicit team/instructor approval where applicable.
7. Document important tradeoffs and assumptions in the PR, especially changes affecting domain rules, persistence, APIs, and shared contracts.
