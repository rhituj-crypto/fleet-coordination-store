package com.fleetstore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetstore.exception.InvalidTelemetryException;
import com.fleetstore.exception.VehicleNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Configuration
class DataSourceConfig {
    @Bean(name = "primaryDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "replicaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.replica")
    public DataSource replicaDataSource() {
        return DataSourceBuilder.create().build();
    }
}

@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private static final String UPSERT_SQL = """
            INSERT INTO fleet_state (vehicle_id, sequence_number, state_data, last_updated)
            VALUES (?, ?, CAST(? AS JSON), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
              last_updated = IF(VALUES(sequence_number) > sequence_number, CURRENT_TIMESTAMP(3), last_updated),
              state_data = IF(VALUES(sequence_number) > sequence_number, VALUES(state_data), state_data),
              sequence_number = GREATEST(sequence_number, VALUES(sequence_number))
            """;

    private static final String SELECT_ROW_SQL =
            "SELECT sequence_number, state_data FROM fleet_state WHERE vehicle_id = ?";

    private final JdbcTemplate primaryDb;
    private final JdbcTemplate replicaDb;
    private final ObjectMapper objectMapper;

    public FleetService(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource,
            ObjectMapper objectMapper) {
        this(new JdbcTemplate(primaryDataSource), new JdbcTemplate(replicaDataSource), objectMapper);
    }

    FleetService(JdbcTemplate primaryDb, JdbcTemplate replicaDb, ObjectMapper objectMapper) {
        this.primaryDb = primaryDb;
        this.replicaDb = replicaDb;
        this.objectMapper = objectMapper;
    }

    public void updateVehicleState(String vehicleId, JsonNode telemetry) {
        long sequence = requireSequence(telemetry);
        final String jsonData;
        try {
            jsonData = objectMapper.writeValueAsString(telemetry);
        } catch (JsonProcessingException e) {
            throw new InvalidTelemetryException("Telemetry payload could not be serialized");
        }

        primaryDb.update(UPSERT_SQL, vehicleId, sequence, jsonData);
    }

    /**
     * Correctness-first read routing for Phase 1:
     * 1) Read the latest sequence from primary.
     * 2) Use replica only if it has reached at least that sequence.
     * 3) If replica is stale, missing the row, or unavailable, return primary data.
     *
     * This removes the unsafe fixed replication-delay assumption.
     */
    public String getVehicleState(String vehicleId) {
        VehicleRow primaryRow = readPrimary(vehicleId);

        try {
            VehicleRow replicaRow = replicaDb.queryForObject(
                    SELECT_ROW_SQL,
                    (rs, rowNum) -> new VehicleRow(rs.getLong("sequence_number"), rs.getString("state_data")),
                    vehicleId);

            if (replicaRow != null && replicaRow.sequenceNumber() == primaryRow.sequenceNumber()) {
                return replicaRow.stateData();
            }

            if (replicaRow != null) {
                log.debug("Replica stale for {}: replica sequence={}, primary sequence={}; using primary",
                        vehicleId, replicaRow.sequenceNumber(), primaryRow.sequenceNumber());
            }
        } catch (EmptyResultDataAccessException e) {
            log.debug("Replica does not have {} yet; using primary", vehicleId);
        } catch (DataAccessException e) {
            log.warn("Replica read failed for {}; falling back to primary: {}", vehicleId, e.getMessage());
        }

        return primaryRow.stateData();
    }

    private VehicleRow readPrimary(String vehicleId) {
        try {
            VehicleRow row = primaryDb.queryForObject(
                    SELECT_ROW_SQL,
                    (rs, rowNum) -> new VehicleRow(rs.getLong("sequence_number"), rs.getString("state_data")),
                    vehicleId);
            if (row == null) {
                throw new VehicleNotFoundException(vehicleId);
            }
            return row;
        } catch (EmptyResultDataAccessException e) {
            throw new VehicleNotFoundException(vehicleId);
        }
    }

    private long requireSequence(JsonNode telemetry) {
        if (telemetry == null || !telemetry.isObject()) {
            throw new InvalidTelemetryException("Telemetry payload must be a JSON object");
        }

        JsonNode sequenceNode = telemetry.get("sequence");
        if (sequenceNode == null || !sequenceNode.isIntegralNumber() || !sequenceNode.canConvertToLong()) {
            throw new InvalidTelemetryException("Telemetry payload requires an integer 'sequence' field");
        }

        long sequence = sequenceNode.longValue();
        if (sequence < 0) {
            throw new InvalidTelemetryException("Telemetry sequence must be non-negative");
        }
        return sequence;
    }

    private record VehicleRow(long sequenceNumber, String stateData) {}
}
