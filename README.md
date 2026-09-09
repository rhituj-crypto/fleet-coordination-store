# Fleet Coordination Store

Phase-1 hardened distributed telemetry prototype.

## Phase-1 correctness changes

1. MySQL 8 primary/replica replication is bootstrapped automatically with GTID-based replication.
2. The `fleet_state` schema is created automatically on the primary and replicated to the replica.
3. The simulator schedules all configured drones and uses asynchronous HTTP instead of tying one infinite drone loop to each worker thread.
4. Every telemetry update carries a monotonically increasing `sequence`; older or duplicate updates cannot overwrite newer state.
5. Reads no longer assume a fixed replication delay. The service checks the current primary sequence and uses replica data only when the replica has caught up.
6. Replica read failures, missing rows, and stale rows automatically fall back to the primary.
7. Missing vehicles return HTTP 404 instead of HTTP 500.

## Start databases

```bash
docker compose up -d
```

On a completely clean reset:

```bash
docker compose down -v
docker compose up -d
```

Check replication:

```bash
docker exec fleet-replica mysql -uroot -padmin_password -e "SHOW REPLICA STATUS\\G"
```

Both `Replica_IO_Running` and `Replica_SQL_Running` should be `Yes`.

## Start backend

```bash
cd backend-service
mvn spring-boot:run
```

## Telemetry format

A sequence number is mandatory:

```json
{"sequence":1,"lat":100.0,"lng":200.0,"bat":90}
```

Example:

```bash
curl -X POST http://localhost:8080/api/telemetry/drone-1 \
  -H "Content-Type: application/json" \
  -d '{"sequence":1,"lat":100.0,"lng":200.0,"bat":90}'

curl http://localhost:8080/api/telemetry/drone-1
```

If sequence 5 has already been stored, a late sequence 4 request is accepted as an idempotent transport operation but **does not overwrite sequence 5**.

## Run simulator

```bash
cd telemetry-simulator
mvn package
java -cp target/classes com.fleetstore.simulator.LoadGenerator
```

Defaults to 10,000 drones. For a smaller local test:

```bash
java -Dfleet.droneCount=100 -Dfleet.updateIntervalMs=1000 \
  -cp target/classes com.fleetstore.simulator.LoadGenerator
```
