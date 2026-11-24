# Sentinel

## Team Information

### Team Members

- Corey Keyu Dai
- Brian Jeen
- Jiaming Zhang
- Chelsea Wu

---

## Project Description

### Domain

Software Diagnostics

### Description

Sentinel is a desktop application that helps developers monitor the health of services in a software system, understand their dependencies, and investigate failures.
Users can register HTTP services and Docker containers, view their health and dependency relationships, and investigate incidents with supporting evidence and suspected causes.
For supported services, users can confirm recovery actions and verify that the affected services have recovered.

---

## User Stories

### User Story 1

As a developer, I want to register my services and their dependencies, so that I can centralize configuration and visuzlize dependencies.

### User Story 2

As a developer, I want to see current and recent service health, so that I can identify availability problems.

### User Story 3

As a developer, I want related failures grouped into an incident with supporting evidence, so that I can understand the suspected cause and potential impact.

### User Story 4

As a developer, I want to perform a supported recovery action and verify its result, so that I know whether the incident is resolved.

---

## Use Cases

### Use Case 1

**Name:** Configure a Monitored System

**Primary actor:** Developer.

**Preconditions:** Sentinel is open, and the developer knows which services and dependencies to register.

**Trigger:** The developer selects the option to create a monitored system.

**Main success scenario:**

1. The developer enters a system name and description.
2. The developer adds services, choosing an HTTP health endpoint or Docker container as each monitoring target.
3. The developer defines dependencies, such as Order Service depending on Order Database.
4. The developer submits the configuration.
5. Sentinel validates and saves the configuration.
6. Sentinel displays the services and dependencies in the System Map.

**Extensions:**

- **5a. Invalid configuration:** Sentinel identifies invalid endpoint formats, duplicate services, or dependencies referencing nonexistent services. The developer corrects the configuration and resubmits it at step 4.
- **5b. Save fails:** Sentinel reports that the configuration could not be saved and allows the developer to retry without re-entering it.

**Success outcome:** A valid monitored system, its services, and their dependencies are saved and available for monitoring.

### Run the configuration editor

Run `mvn compile exec:java -Dexec.mainClass=app.Sentinel` from the repository root with a graphical session.
Create a system or select a saved one, add or edit HTTP and Docker services, and use the System Map to specify which services depend on others.
Removing a service from the draft also removes its dependency edges. Save the configuration to commit the changes.
Configurations are stored under `~/.sentinel/systems` and can be reopened after restarting Sentinel.

Run the UC1 checks with `mvn test-compile` followed by
`mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=use_case.configure_monitored_system.ConfigurationInteractorChecks`
and `mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass=data_access.configuration.JsonConfigurationRepositoryChecks`.

### Use Case 2

**Name:** Monitor Service Health

**Primary actor:** Developer.

**Preconditions:** A monitored system with at least one service and a valid monitoring configuration has been saved.

**Trigger:** The developer selects a configured system and starts monitoring.

**Main success scenario:**

1. Sentinel starts periodic health checks for the system's registered services.
2. Sentinel checks each service using its configured monitoring method.
3. Sentinel records the check results and updates each service's health status.
4. The developer opens the Overview to view current service health.
5. The developer selects a service.
6. Sentinel displays its recent checks, response times where available, and failure messages.
7. Sentinel repeats checks at the configured interval while monitoring remains active.

**Extensions:**

- **2a. Service check fails:** Sentinel records the failure and updates health according to the configured failure threshold. Monitoring continues.
- **2b. Monitoring source is inaccessible:** Sentinel records a monitoring error and displays the service's current health as unknown rather than treating the access problem as a confirmed service failure.
- **7a. Developer stops monitoring:** Sentinel stops scheduling checks and retains recorded results for inspection.

**Success outcome:** The developer can inspect current service health and recorded observations, with updates continuing while monitoring is active.

### Use Case 3

**Name:** Investigate an Incident

**Primary actor:** Developer.

**Preconditions:** Monitoring has detected a failure that satisfies an incident rule, and Sentinel has created or updated an incident using recent observations and service dependencies.

**Trigger:** The developer opens an incident from the Overview or incident list.

**Main success scenario:**

1. Sentinel marks an open incident as investigating and displays its status, suspected cause, observed failing services, and potentially affected services.
2. The developer selects supporting evidence.
3. Sentinel displays the relevant health check results and dependency relationships supporting its diagnosis.
4. The developer inspects how the failure may affect dependent services.
5. Sentinel presents troubleshooting steps from its findings and, when the most upstream failing service is a Docker container, recommends recovering that container.
6. The developer reviews the recommendations to determine the next action.

**Extensions:**

- **1a. Cause is uncertain:** Sentinel states that the cause is unknown and displays the available evidence without presenting a confirmed diagnosis.
- **3a. Dependency information is unavailable:** Sentinel displays recorded failures and explains that dependency-based impact analysis is unavailable.
- **5a. No recovery action is supported:** Sentinel provides manual troubleshooting guidance.

**Success outcome:** The developer can identify the recorded failures, inspect the evidence behind any suspected cause, and review available next steps. Investigation alone does not resolve the incident.

### Use Case 4

**Name:** Recover a Service and Verify Resolution

**Primary actor:** Developer.

**Preconditions:** An unresolved incident with findings from Use Case 3 exists, a recovery recommendation is available, and monitoring is active to verify recovery.

**Trigger:** The developer selects a recovery recommendation for the incident.

**Main success scenario:**

1. Sentinel displays the recommended target container and how its current state determines the operation.
2. The developer confirms recovery of the container.
3. Sentinel inspects the container state, executes the matching operation (start a stopped container, unpause a paused container, or restart a running but failing container), and records the attempt's outcome.
4. Sentinel checks the target service and affected dependent services again.
5. Sentinel confirms that the configured recovery criteria are satisfied.
6. Sentinel marks the incident as resolved and retains it in the incident history.
7. Sentinel displays the recovery result to the developer.

**Extensions:**

- **1a. Execution is unsupported:** Sentinel shows the manual next steps from the incident's findings. After the developer performs the action externally, monitoring continues verification at step 4.
- **2a. Developer cancels:** Sentinel performs no recovery action, and the incident remains unresolved.
- **3a. Action fails or permission is denied:** Sentinel records and displays the failure. The incident remains unresolved, and the developer may retry or follow manual instructions.
- **3b. Container state is unsuitable for automatic recovery:** If the container is restarting, was killed for exceeding memory, or is missing, Sentinel performs no operation and shows the manual next steps. The incident remains unresolved.
- **4a. Recovery cannot be verified:** Sentinel reports the monitoring error and keeps the incident unresolved until verification succeeds.
- **5a. Recovery criteria are not satisfied:** Sentinel keeps the incident unresolved, displays the remaining failures, and continues monitoring.

**Success outcome:** The target service and affected dependent services satisfy the configured recovery criteria, and the incident is recorded as resolved with the recovery attempt retained.

---

## Minimal Viable Product (MVP)

| Team Member | MVP Use Case                                               |
| ----------- | ---------------------------------------------------------- |
| Brian Jeen         | Use Case 1: Configure systems, services, and dependencies  |
| Chelsea Wu         | Use Case 2: Run health checks and display health history   |
| Jiaming Zhang | Use Case 3: Detect incidents and explain suspected causes  |
| Corey Dai  | Use Case 4: Execute recovery actions and verify resolution |

---

## Entities

| Type | Responsibility |
| --- | --- |
| `MonitoredSystem` | Owns the service roster and directed dependency graph. Service IDs and case-insensitive names are unique within the system. Dependency IDs and edges are unique, and both endpoints must belong to the system. |
| `Service` | Stores its ID, system ID, name, monitoring target, and monitoring policy. |
| `Dependency` | Identifies a directed relationship from the dependent service to the required service. Self-dependencies are invalid. Multi-service cycles are allowed, so graph traversal must track visited services. |
| `HealthCheckResult` | Records a timestamped `PASS`, `FAIL`, or `MONITORING_ERROR`, with optional response time and a message. |
| `FailureEvent` | Records a transition into unhealthy status with the failed checks that support it. The monitoring use case determines when the failure threshold is crossed. |
| `DiagnosticFinding` | Records the rule, affected service, optional suspected cause, observation IDs, dependency evidence, explanation, and next steps. Dependency evidence retains the edges used in the diagnosis. |
| `Incident` | Tracks observed failures, potential impact, current and historical findings, and the `OPEN`, `INVESTIGATING`, and `RESOLVED` lifecycle. |
| `RecoveryAction` | Describes a supported operation and its target within an incident. Every executable action requires explicit confirmation. |
| `RecoveryAttempt` | Records when an action ran and whether it succeeded, failed, or was denied. |

`MonitoringTarget` represents either an HTTP(S) endpoint or a Docker container ID. `MonitoringPolicy` supplies the check interval, timeout, failure threshold, and recovery threshold. `ServiceHealth` keeps evaluated health separate from service configuration. A service starts at `UNKNOWN`, and a monitoring error yields `UNKNOWN`.

An incident can be investigated with an unknown cause. Diagnostic rules can replace the active assessment with findings using fresh IDs while the incident preserves past findings for review. Manual troubleshooting instructions belong in a finding's next steps.

The recovery use case must confirm the action before execution, verify the selected target, and evaluate follow-up checks against the configured recovery threshold. The incident requires fresh passing observations for services with recorded failures, services flagged as potentially affected in the current assessment, and active suspected causes. It retains the passing checks used to resolve it.

---


### Running the domain checks

Compile the main and test classes with `mvn test-compile`. Run the dependency-free checks from the project root.

- PowerShell or Command Prompt: `java -cp "target/classes;target/test-classes" entity.DomainChecks`
- macOS or Linux: `java -cp "target/classes:target/test-classes" entity.DomainChecks`


---

## API Usage

**API Name:**
**Documentation:** [Link]
**How it is used in the program:**

---

## Design Diagrams

### UML Class Diagram

### Sequence Diagrams

---

## How to Run

[Instructions for running the program.]

---

## Notes

[Any additional project information.]
