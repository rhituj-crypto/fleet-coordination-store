package com.fleetstore.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
class DataSourceConfig {
    @Bean(name = "primaryDataSource")
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() { return DataSourceBuilder.create().build(); }

    @Bean(name = "replicaDataSource")
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "spring.datasource.replica")
    public DataSource replicaDataSource() { return DataSourceBuilder.create().build(); }
}

@Service
public class FleetService {

    private final JdbcTemplate primaryDb;
    private final JdbcTemplate replicaDb;
    private final ConcurrentHashMap<String, Long> recentWritesCache = new ConcurrentHashMap<>();
    private static final long REPLICATION_LAG_BUFFER_MS = 2000;

    public FleetService(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource) {
        this.primaryDb = new JdbcTemplate(primaryDataSource);
        this.replicaDb = new JdbcTemplate(replicaDataSource);
    }

    public void updateVehicleState(String vehicleId, String jsonData) {
        String sql = "INSERT INTO fleet_state (vehicle_id, state_data) " +
                     "VALUES (?, ?) ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";
                     
        primaryDb.update(sql, vehicleId, jsonData);
        recentWritesCache.put(vehicleId, System.currentTimeMillis());
    }

    public String getVehicleState(String vehicleId) {
        String sql = "SELECT state_data FROM fleet_state WHERE vehicle_id = ?";
        Long lastWriteTime = recentWritesCache.get(vehicleId);
        
        boolean wasRecentlyWritten = lastWriteTime != null && 
                                    (System.currentTimeMillis() - lastWriteTime < REPLICATION_LAG_BUFFER_MS);

        if (wasRecentlyWritten) {
            return primaryDb.queryForObject(sql, String.class, vehicleId);
        } else {
            return replicaDb.queryForObject(sql, String.class, vehicleId);
        }
    }
}