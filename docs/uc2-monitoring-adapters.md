# UC2 monitoring adapters

## Architecture and scope

`HealthChecker` remains the inner-layer port. `TargetHealthChecker` selects the HTTP or Docker strategy
from the saved `MonitoringTarget`; neither interactor imports a transport library. `DockerHealthChecker`
maps observations, while `DockerEngineInspector` performs only the read-only Engine inspect request.
Its small `DockerInspector` seam permits fake inspector tests without a daemon or real container IDs.

The composition root passes one application `Clock` to both checkers and UC3. UC2 and UC3 share the same
`JsonObservationStore`, which also implements scoped `ObservationLookup`. The controller runs manual
checks off the EDT and the scheduler runs periodic checks on its own worker. The presenter dispatches
display updates to the EDT.

The Docker dependency is `docker-java-core` plus `docker-java-transport-httpclient5`, pinned to 3.7.1.
Core supplies host/context/TLS configuration; the transport supplies Windows named-pipe, Unix-socket
and TCP/TLS connections. Monitoring sends `GET /containers/{encoded-id-or-name}/json` directly through
the transport and decodes the stable subset of its JSON response with the existing Gson codec.
Unversioned requests use the daemon's current API; there is no pinned, potentially obsolete API version.
No recovery operation or Docker CLI subprocess is invoked.

## Docker outcome policy

| Observation | UC2 outcome |
| --- | --- |
| Running, HEALTHCHECK healthy | PASS |
| Running, no HEALTHCHECK or health `none` | PASS, explicitly labelled **runtime state only** |
| Running, HEALTHCHECK unhealthy | FAIL |
| Running with OOMKilled reported | FAIL |
| Created, paused, restarting, exited, dead, removing | FAIL |
| Engine returns 404 for the target | FAIL: container missing |
| Health `starting` | MONITORING_ERROR: not enough information yet |
| Engine unavailable, timeout, permission denied, server error, malformed/unknown state | MONITORING_ERROR |

These are single-check observations. `HealthStateEvaluator` still applies the configured consecutive
failure/recovery thresholds and generates failure events. A running container without HEALTHCHECK is
not proof of application readiness; its message identifies the weaker runtime-state check.

The per-service timeout configures both connection and response timeouts. Positive sub-millisecond
values round up to one millisecond so that they cannot become an unlimited timeout. These bound
individual transport phases, rather than providing a hard wall-clock deadline for their sum.
Each inspect opens and closes its own client; this avoids sharing mutable timeout configuration between
services. Only responses up to 1 MiB are accepted. API target text is encoded as a single path segment.

## Docker configuration

Start Docker Desktop/Engine before real-container checks. The Docker client library reads its standard
host/context/TLS configuration. Its platform defaults are the Windows `docker_engine` named pipe and
`unix:///var/run/docker.sock` on Unix. An explicit Java property may override the host:

```powershell
java "-Dsentinel.docker.host=npipe:////./pipe/docker_engine" -cp "target/classes;target/dependency/*" app.Sentinel
```

This does not enable or change any Engine listener. Do not enable an unauthenticated TCP listener just
for monitoring. Engine permission/connection failures are presented as monitoring errors.

## Persistence

Observations are stored under `<application-data>/observations/<observation-id>.json`, using schema
version 1 and the shared `JsonCodec`. Every outcome, original ID, service ID, nanosecond timestamp,
optional response time and message is retained. Identical repeated saves are idempotent; conflicting
contents for an existing ID are rejected. Corrupt files fail reads rather than silently shortening a
threshold streak. Writes use a temporary file and an atomic move. Unsupported atomic moves fail.

Access is serialized on the application's shared store instance; simultaneous application processes
sharing a data directory are not supported. History reads scan the directory, and no retention/indexing
policy is implemented yet. Old observations that existed only in memory cannot be recovered.
Pending event-delivery state itself is in memory. On later checks, UC2 re-evaluates durable history and
uses deterministic failure IDs; UC3 acknowledges previously processed failures rather than duplicating
their incidents.

## Checks

Build with `mvn clean test-compile dependency:copy-dependencies`, then run:

```powershell
java -cp "target/classes;target/test-classes;target/dependency/*" data_access.docker.DockerHealthCheckerChecks
java -cp "target/classes;target/test-classes;target/dependency/*" data_access.docker.DockerEngineInspectorChecks
java -cp "target/classes;target/test-classes;target/dependency/*" data_access.persistence.JsonObservationStoreChecks
java "-Djava.awt.headless=true" -cp "target/classes;target/test-classes;target/dependency/*" app.HttpMonitoringWiringChecks
```

The checker checks use fakes. The transport checks use a local simulated Engine HTTP endpoint to cover
request routing/encoding, state decoding, missing containers, denied access, malformed data and timeouts.
They do not prove that the installed Docker daemon or its socket/pipe is reachable. Real-container
verification must be performed separately with Docker Desktop/Engine.

UI testing should cover selecting saved services, runtime-state-only messages, starting/stopping
monitoring, threshold-based incident creation, and stopping obsolete sessions after configuration edits.
After restarting the app, existing incident evidence must still load from the observation store.
