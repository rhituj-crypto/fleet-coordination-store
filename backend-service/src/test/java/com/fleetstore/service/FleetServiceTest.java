package com.fleetstore.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetstore.exception.InvalidTelemetryException;
import com.fleetstore.exception.VehicleNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FleetServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void updateRequiresSequence() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        JsonNode payload = objectMapper.readTree("{\"lat\":1,\"lng\":2,\"bat\":90}");

        assertThrows(InvalidTelemetryException.class,
                () -> service.updateVehicleState("drone-1", payload));
        verifyNoInteractions(primary);
    }

    @Test
    void updatePassesSequenceIntoAtomicConditionalUpsert() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        JsonNode payload = objectMapper.readTree(
                "{\"sequence\":42,\"lat\":1,\"lng\":2,\"bat\":90}");

        service.updateVehicleState("drone-1", payload);

        verify(primary).update(
                argThat(sql -> sql.contains("GREATEST(sequence_number")
                        && sql.contains("VALUES(sequence_number) > sequence_number")),
                eq("drone-1"), eq(42L), contains("\"sequence\":42"));
    }

    @Test
    void freshReplicaIsUsed() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        stubRow(primary, 9, "{\"sequence\":9,\"source\":\"primary\"}");
        stubRow(replica, 9, "{\"sequence\":9,\"source\":\"replica\"}");

        String state = service.getVehicleState("drone-1");

        assertTrue(state.contains("replica"));
    }

    @Test
    void staleReplicaFallsBackToPrimary() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        stubRow(primary, 10, "{\"sequence\":10,\"source\":\"primary\"}");
        stubRow(replica, 9, "{\"sequence\":9,\"source\":\"replica\"}");

        String state = service.getVehicleState("drone-1");

        assertTrue(state.contains("primary"));
    }

    @Test
    void missingReplicaRowFallsBackToPrimary() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        stubRow(primary, 3, "{\"sequence\":3,\"source\":\"primary\"}");
        when(replica.queryForObject(anyString(), any(RowMapper.class), eq("drone-1")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertTrue(service.getVehicleState("drone-1").contains("primary"));
    }

    @Test
    void replicaFailureFallsBackToPrimary() throws Exception {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        stubRow(primary, 3, "{\"sequence\":3,\"source\":\"primary\"}");
        when(replica.queryForObject(anyString(), any(RowMapper.class), eq("drone-1")))
                .thenThrow(new DataAccessResourceFailureException("replica unavailable"));

        assertTrue(service.getVehicleState("drone-1").contains("primary"));
    }

    @Test
    void missingPrimaryVehicleRaisesNotFound() {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        JdbcTemplate replica = mock(JdbcTemplate.class);
        FleetService service = new FleetService(primary, replica, objectMapper);

        when(primary.queryForObject(anyString(), any(RowMapper.class), eq("missing")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThrows(VehicleNotFoundException.class,
                () -> service.getVehicleState("missing"));
        verifyNoInteractions(replica);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubRow(JdbcTemplate db, long sequence, String stateData) throws Exception {
        when(db.queryForObject(anyString(), any(RowMapper.class), eq("drone-1")))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("sequence_number")).thenReturn(sequence);
                    when(rs.getString("state_data")).thenReturn(stateData);
                    return mapper.mapRow(rs, 0);
                });
    }
}
