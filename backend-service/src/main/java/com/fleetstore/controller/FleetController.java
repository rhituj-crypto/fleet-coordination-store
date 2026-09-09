package com.fleetstore.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetstore.service.FleetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/telemetry")
public class FleetController {

    private final FleetService fleetService;

    public FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @PostMapping("/{vehicleId}")
    public ResponseEntity<Void> updateState(
            @PathVariable String vehicleId,
            @RequestBody JsonNode payload) {
        fleetService.updateVehicleState(vehicleId, payload);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{vehicleId}")
    public ResponseEntity<String> getState(@PathVariable String vehicleId) {
        return ResponseEntity.ok(fleetService.getVehicleState(vehicleId));
    }
}
