CREATE TABLE fleet_state (
    vehicle_id VARCHAR(50) PRIMARY KEY,
    state_data JSON NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE INDEX idx_last_active ON fleet_state(last_updated);