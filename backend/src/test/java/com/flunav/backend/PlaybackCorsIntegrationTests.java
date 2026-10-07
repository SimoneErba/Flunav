package com.flunav.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "springwolf.enabled=false", "stale-item-cleanup.enabled=false",
        "state-recovery.enabled=false", "graph-snapshot.enabled=false"
})
@AutoConfigureMockMvc
class PlaybackCorsIntegrationTests extends BaseIntegrationTest {
    @Autowired private MockMvc http;

    @Test
    void playbackPatchPreflightAllowsTheConfiguredBrowserOrigin() throws Exception {
        http.perform(options("/api/simulations/example/playback")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Simulation-ID"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }

    @Test
    void playbackPatchPreflightRejectsAnUnconfiguredOrigin() throws Exception {
        http.perform(options("/api/simulations/example/playback")
                .header("Origin", "https://unconfigured.example")
                .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowingPatchDoesNotRemoveAuthentication() throws Exception {
        http.perform(patch("/api/simulations/example/playback")
                .header("Origin", "http://localhost:5173")
                .contentType("application/json").content("2.0"))
                .andExpect(status().isUnauthorized());
    }
}
