package com.videopilot.infrastructure.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videopilot.analysis.application.ReliabilityOperationsService;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentitySecurityConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = ReliabilityOperationsController.class, properties = "videopilot.identity.enabled=true")
@Import(IdentitySecurityConfiguration.class)
class ReliabilityOperationsControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mockMvc;
    @MockitoBean ReliabilityOperationsService reliability;
    @MockitoBean SessionApplicationService sessions;

    @BeforeEach
    void authenticate() {
        when(sessions.authenticate("session-token")).thenReturn(new AuthenticatedSession(
                UUID.randomUUID(), new OwnerId(OWNER_ID), "operator", "csrf",
                Instant.parse("2026-11-07T12:00:00Z")));
    }

    @Test
    void returnsOnlyOwnerScopedAggregatesAndExplicitDegradedSources() throws Exception {
        when(reliability.summary(OWNER_ID.toString())).thenReturn(new ReliabilityOperationsService.Summary(
                Instant.parse("2026-11-06T12:00:00Z"), Instant.parse("2026-11-07T12:00:00Z"),
                3L, null,
                List.of(new ReliabilityOperationsService.Count("RETRYABLE", 2)),
                List.of(new ReliabilityOperationsService.StageDuration("INDEXING", 100, 250)),
                List.of(new ReliabilityOperationsService.ProviderCalls(
                        "SiliconFlow", 4, 1, 500, 20, new BigDecimal("0.50"), "USD")),
                List.of("RABBITMQ"), Instant.parse("2026-11-07T12:00:01Z")));

        mockMvc.perform(get("/api/v1/operations/reliability")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOutbox").value(3))
                .andExpect(jsonPath("$.queueBacklog").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.failureCounts[0].code").value("RETRYABLE"))
                .andExpect(jsonPath("$.providerCalls[0].estimatedCost").value(0.50))
                .andExpect(jsonPath("$.degradedSources[0]").value("RABBITMQ"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(OWNER_ID.toString()))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("prompt"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("objectKey"))));
    }
}
