CREATE DATABASE IF NOT EXISTS fleet_db;
USE fleet_db;

CREATE TABLE IF NOT EXISTS fleet_state (
    vehicle_id VARCHAR(50) PRIMARY KEY,
    sequence_number BIGINT UNSIGNED NOT NULL,
    state_data JSON NOT NULL,
    last_updated TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_last_active (last_updated)
);
