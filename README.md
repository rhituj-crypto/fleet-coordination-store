# Fleet Coordination Store

A Spring Boot backend for managing the latest telemetry state of fleet vehicles using a **primary–replica MySQL architecture**.

The service is designed around a common distributed-systems problem: **serving fresh data immediately after a write while still allowing reads to be handled by a database replica**. To address potential replication lag, recently updated vehicles are temporarily read from the primary database before being routed back to the replica.

---

## Overview

Fleet vehicles continuously send telemetry updates such as location, battery level, or other state information.

Instead of storing every telemetry event, this service maintains the **latest known state for each vehicle**.

The application provides two core operations:

* **Write telemetry** → always sent to the primary database.
* **Read telemetry** → served from the primary for recently updated vehicles, otherwise from the replica.

This approach demonstrates:

* Read/write database separation
* MySQL primary–replica architecture
* Read-after-write consistency
* Replication-lag handling
* Concurrent-safe in-memory state tracking
* REST API development with Spring Boot

---

## Architecture

```text
                        ┌─────────────────────┐
                        │       Client        │
                        │  / Vehicle Fleet    │
                        └──────────┬──────────┘
                                   │
                          REST / HTTP Requests
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │   Spring Boot API   │
                        │                     │
                        │   FleetController   │
                        │          │          │
                        │   FleetService      │
                        └──────────┬──────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     │                           │
                  WRITE                       READ
                     │                           │
                     ▼                           ▼
          ┌──────────────────┐         ┌──────────────────┐
          │  MySQL Primary   │ ──────► │  MySQL Replica   │
          │    :3306         │         │      :3307        │
          └──────────────────┘         └──────────────────┘
                     │
                     │
              Recent Write Cache
              (2 second window)
```

### Read Routing

After a successful write, the vehicle ID and write timestamp are stored in an in-memory `ConcurrentHashMap`.

For the next **2 seconds**, reads for that vehicle are sent to the primary database.

After that window, reads are routed to the replica.

```text
                    GET /api/telemetry/{vehicleId}
                                  │
                                  ▼
                      Was the vehicle recently written?
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                   YES                          NO
                    │                           │
                    ▼                           ▼
             Read from Primary          Read from Replica
```

This reduces the chance of returning stale data immediately after a write when replication is still in progress.

---

## Tech Stack

| Technology        | Purpose                         |
| ----------------- | ------------------------------- |
| Java              | Application development         |
| Spring Boot 3.1.2 | Backend framework               |
| Spring Web        | REST API                        |
| Spring JDBC       | Database access                 |
| MySQL 8.0         | Primary and replica databases   |
| Docker Compose    | Database infrastructure         |
| Maven             | Build and dependency management |

---

## Project Structure

```text
fleet-coordination-store/
│
├── backend-service/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/fleetstore/
│   │       │   ├── FleetApplication.java
│   │       │   ├── controller/
│   │       │   │   └── FleetController.java
│   │       │   └── service/
│   │       │       └── FleetService.java
│   │       │
│   │       └── resources/
│   │           └── application.yml
│   │
│   └── pom.xml
│
├── infrastructure/
│   └── schema.sql
│
├── docker-compose.yml
└── README.md
```

---

## Data Model

The service stores one row per vehicle.

```sql
CREATE TABLE fleet_state (
    vehicle_id VARCHAR(50) PRIMARY KEY,
    state_data JSON NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);

CREATE INDEX idx_last_active
ON fleet_state(last_updated);
```

### Fields

| Field          | Type          | Description                              |
| -------------- | ------------- | ---------------------------------------- |
| `vehicle_id`   | `VARCHAR(50)` | Unique vehicle identifier                |
| `state_data`   | `JSON`        | Latest telemetry payload                 |
| `last_updated` | `TIMESTAMP`   | Last time the vehicle state was modified |

The primary key on `vehicle_id` allows the service to perform an **upsert**, ensuring that only the latest state is maintained.

---

## API

### Update Vehicle State

```http
POST /api/telemetry/{vehicleId}
```

Stores or updates the latest telemetry for a vehicle.

#### Example

```bash
curl -X POST http://localhost:8080/api/telemetry/drone-001 \
  -H "Content-Type: application/json" \
  -d '{"lat":28.6139,"lng":77.2090,"bat":87}'
```

#### Response

```http
200 OK
```

The endpoint currently accepts the request body as a JSON string and stores it directly in the `state_data` column.

---

### Retrieve Vehicle State

```http
GET /api/telemetry/{vehicleId}
```

Returns the latest known state for the requested vehicle.

#### Example

```bash
curl http://localhost:8080/api/telemetry/drone-001
```

#### Example Response

```json
{
  "lat": 28.6139,
  "lng": 77.209,
  "bat": 87
}
```

---

## Database Configuration

The service is configured with two independent data sources.

### Primary

```text
jdbc:mysql://localhost:3306/fleet_db
```

Used for all write operations.

### Replica

```text
jdbc:mysql://localhost:3307/fleet_db
```

Used for reads when the vehicle has not been updated recently.

The two data sources are exposed as separate Spring beans:

```java
primaryDataSource
replicaDataSource
```

and accessed through separate `JdbcTemplate` instances.

---

## Running the Project

### Prerequisites

Make sure the following are installed:

* Java 17+
* Maven
* Docker
* Docker Compose

### 1. Start MySQL

From the project root:

```bash
docker compose up -d
```

This starts:

```text
fleet-primary   → localhost:3306
fleet-replica   → localhost:3307
```

### 2. Initialize the Database

Create the required table in the primary database using:

```bash
mysql -h localhost -P 3306 -u root -p fleet_db < infrastructure/schema.sql
```

The configured password is:

```text
admin_password
```

### 3. Start the Spring Boot Application

```bash
cd backend-service
mvn spring-boot:run
```

The service will be available at:

```text
http://localhost:8080
```

---

## How the Consistency Mechanism Works

A typical sequence looks like this:

```text
1. Client sends POST
        │
        ▼
2. Primary database is updated
        │
        ▼
3. Vehicle ID + timestamp stored in memory
        │
        ▼
4. Client immediately sends GET
        │
        ▼
5. Service detects a recent write
        │
        ▼
6. Read is performed against Primary
        │
        ▼
7. After 2 seconds, reads can use Replica
```

The relevant logic is based on:

```java
private static final long REPLICATION_LAG_BUFFER_MS = 2000;
```

and:

```java
ConcurrentHashMap<String, Long> recentWritesCache
```

Using `ConcurrentHashMap` allows the cache to be safely accessed by multiple request threads.

---

## Design Decisions

### 1. Latest-state storage

The system is intended to provide the **current state of a vehicle**, rather than act as a historical telemetry event store.

Therefore, each `vehicle_id` maps to a single database row.

### 2. Upsert instead of insert-only writes

The write query uses:

```sql
INSERT INTO fleet_state (vehicle_id, state_data)
VALUES (?, ?)
ON DUPLICATE KEY UPDATE state_data = VALUES(state_data);
```

This avoids creating multiple rows for the same vehicle.

### 3. Separate data sources

The application explicitly maintains different JDBC connections for the primary and replica databases.

This makes the read/write routing decision visible at the service layer rather than relying on a single database connection.

### 4. Recent-write protection

Replica databases can temporarily lag behind the primary.

The recent-write cache provides a small consistency window in which reads are deliberately kept on the primary.

---

## Current Limitations

This repository is primarily a **system-design / backend architecture demonstration** rather than a production-ready fleet platform.

Some areas would require additional work for production use:

* MySQL replication initialization is not automated by Docker Compose.
* Database credentials are currently defined directly in configuration files.
* The recent-write cache is local to a single application instance.
* No authentication or authorization is implemented.
* No request validation or centralized exception handling is implemented.
* Historical telemetry is not retained.
* No health checks, metrics, tracing, or observability stack is included.

These limitations are intentional opportunities for further development.

---

## Potential Improvements

A production-oriented version could introduce:

```text
                     ┌───────────────────┐
                     │   Load Balancer   │
                     └─────────┬─────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
          API Instance     API Instance     API Instance
              │                │                │
              └────────────────┼────────────────┘
                               │
                          Message Queue
                               │
                               ▼
                       Telemetry Workers
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
              MySQL Primary          Redis Cache
                    │
                    ▼
              Read Replicas
```

Possible extensions include:

* Redis-backed distributed consistency tracking
* Kafka-based telemetry ingestion
* Multiple read replicas
* Connection pooling and tuning
* Authentication and authorization
* API validation
* Centralized exception handling
* Prometheus/Grafana monitoring
* Distributed tracing
* Historical telemetry storage
* Kubernetes deployment
* Automated database replication setup

---

## Configuration

The backend configuration is located at:

```text
backend-service/src/main/resources/application.yml
```

Current configuration:

```yaml
server:
  port: 8080

spring:
  datasource:
    primary:
      jdbc-url: jdbc:mysql://localhost:3306/fleet_db
      username: root
      password: admin_password

    replica:
      jdbc-url: jdbc:mysql://localhost:3307/fleet_db
      username: root
      password: admin_password
```

For production deployments, these values should be supplied through environment variables or a secrets-management system rather than committed configuration.

---

## What This Project Demonstrates

This project focuses on a small but important distributed-systems problem:

> **How can an application scale reads across replicas without immediately returning stale data after a write?**

The implementation demonstrates a practical approach using:

* Primary/replica databases
* Explicit read/write routing
* A recent-write consistency window
* Concurrent in-memory state tracking
* RESTful service design
* Dockerized database infrastructure

---

## License

This project does not currently specify a license.
