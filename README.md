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

1. Sentinel displays the incident's status, suspected cause, observed failing services, and potentially affected services.
2. The developer selects supporting evidence.
3. Sentinel displays the relevant health check results and dependency relationships supporting its diagnosis.
4. The developer inspects how the failure may affect dependent services.
5. Sentinel presents recommended troubleshooting steps or supported recovery actions.
6. The developer reviews the recommendations to determine the next action.

**Extensions:**

- **1a. Cause is uncertain:** Sentinel states that the cause is unknown and displays the available evidence without presenting a confirmed diagnosis.
- **3a. Dependency information is unavailable:** Sentinel displays recorded failures and explains that dependency-based impact analysis is unavailable.
- **5a. No recovery action is supported:** Sentinel provides manual troubleshooting guidance.

**Success outcome:** The developer can identify the recorded failures, inspect the evidence behind any suspected cause, and review available next steps. Investigation alone does not resolve the incident.

### Use Case 4

**Name:** Recover a Service and Verify Resolution

**Primary actor:** Developer.

**Preconditions:** An unresolved incident exists, a recovery recommendation is available, and monitoring is active to verify recovery.

**Trigger:** The developer selects a recovery recommendation for the incident.

**Main success scenario:**

1. Sentinel displays the proposed recovery action and target service.
2. The developer confirms a supported action, such as starting a stopped Docker container.
3. Sentinel executes the action and records the attempt's outcome.
4. Sentinel checks the target service and affected dependent services again.
5. Sentinel confirms that the configured recovery criteria are satisfied.
6. Sentinel marks the incident as resolved and retains it in the incident history.
7. Sentinel displays the recovery result to the developer.

**Extensions:**

- **1a. Execution is unsupported:** Sentinel provides manual recovery instructions. After the developer performs the action externally, monitoring continues verification at step 4.
- **2a. Developer cancels:** Sentinel performs no recovery action, and the incident remains unresolved.
- **3a. Action fails or permission is denied:** Sentinel records and displays the failure. The incident remains unresolved, and the developer may retry or follow manual instructions.
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

### Entity 1

**MonitoredSystem**

Represents the application being monitored and groups its registered services.

- Attributes: `id`, `name`, `description`, `serviceIds`.

### Entity 2

**Service**

Represents an application component, such as an API or database, and its monitoring configuration.

- Attributes: `id`, `systemId`, `name`, `monitoringConfig`, `currentHealth`.
- The monitoring configuration includes the method, endpoint or container ID, check interval, timeout, and failure threshold.
- Health states: `HEALTHY`, `UNHEALTHY`, `UNKNOWN`.

### Entity 3

**Dependency**

Represents a directed relationship in which one service depends on another.

- Attributes: `id`, `dependentServiceId`, `requiredServiceId`.

### Entity 4

**HealthCheckResult**

Records one monitoring observation for a service and can provide evidence for an incident.

- Attributes: `id`, `serviceId`, `timestamp`, `outcome`, `responseTimeMs`, `message`.
- Outcomes: `PASS`, `FAIL`, `MONITORING_ERROR`.
- Response time is optional for monitoring methods where it is not applicable.

### Entity 5

**Incident**

Tracks a problem, its suspected cause, supporting observations, affected services, and resolution.

- Attributes: `id`, `systemId`, `status`, `openedAt`, `resolvedAt`, `suspectedCauseServiceId`, `failingServiceIds`, `potentiallyAffectedServiceIds`, `evidenceIds`.
- Statuses: `OPEN`, `INVESTIGATING`, `RESOLVED`.
- Evidence references health check results. A suspected cause may be unknown, and a resolution time is recorded only after resolution.

### Entity 6

**RecoveryAction**

Describes a proposed recovery action for a service associated with an incident.

- Attributes: `id`, `incidentId`, `targetServiceId`, `actionType`, `description`, `requiresConfirmation`.

### Entity 7

**RecoveryAttempt**

Records the outcome of an attempt to execute a recovery action. Successful execution does not by itself mean that the incident is resolved.

- Attributes: `id`, `actionId`, `startedAt`, `completedAt`, `outcome`, `message`.

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
