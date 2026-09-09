package com.fleetstore.controller;

import com.fleetstore.exception.ApiExceptionHandler;
import com.fleetstore.exception.VehicleNotFoundException;
import com.fleetstore.service.FleetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FleetController.class)
@Import(ApiExceptionHandler.class)
class FleetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FleetService fleetService;

    @Test
    void missingVehicleReturns404() throws Exception {
        when(fleetService.getVehicleState("missing"))
                .thenThrow(new VehicleNotFoundException("missing"));

        mockMvc.perform(get("/api/telemetry/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
