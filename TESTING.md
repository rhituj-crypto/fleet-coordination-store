# Phase-1 Testing

## Tests included in the project

The backend contains JUnit tests for:

- mandatory sequence validation;
- sequence-aware upsert construction;
- fresh replica reads;
- stale replica fallback to primary;
- missing replica-row fallback;
- replica exception fallback;
- missing primary row -> `VehicleNotFoundException`;
- controller-level HTTP 404 behavior.

Run them with:

```bash
cd backend-service
mvn test
```

## End-to-end Docker check

With both databases and the backend running:

```bash
./scripts/phase1-e2e-check.sh
```

That script checks live MySQL replication, out-of-order update protection, stale-replica fallback, replica-down fallback, and HTTP 404.

## Validation performed while preparing this ZIP

The project was additionally checked with:

- Java 17 compilation of the telemetry simulator;
- 10,000 simulated `DroneState` objects generating 100 strictly increasing local sequence numbers each;
- a live HTTP capture test with 1,000 configured drones, confirming all 1,000 emitted telemetry and no duplicate per-drone sequence values were generated;
- an executable backend service harness covering fresh/stale/failed/missing replica paths, primary not-found behavior, and sequence validation;
- Java 17 syntax/type checking of backend sources against API-compatible stubs;
- YAML and XML parsing of Docker Compose and both Maven POM files;
- shell syntax checks for both MySQL initialization scripts;
- source-tree checks confirming the old 2-second replication timer and stale committed `.class` file are gone.

Docker and Maven were not available in the preparation environment, so the real MySQL-container and Maven/JUnit suites could not be executed there. The included end-to-end script is intended to make those remaining environment-dependent checks reproducible on a Docker-enabled machine.
