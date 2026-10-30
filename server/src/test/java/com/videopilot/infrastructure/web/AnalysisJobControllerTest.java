package com.videopilot.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.AnalysisJobPage;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentitySecurityConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AnalysisJobController.class, properties = "videopilot.identity.enabled=true")
@Import(IdentitySecurityConfiguration.class)
class AnalysisJobControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final AuthenticatedSession SESSION = new AuthenticatedSession(
            UUID.randomUUID(), new OwnerId(OWNER_ID), "learner", "csrf-token",
            Instant.parse("2026-10-30T18:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisJobApplicationService jobs;

    @MockitoBean
    private SessionApplicationService sessions;

    @BeforeEach
    void authenticate() {
        when(sessions.authenticate("opaque-session-token")).thenReturn(SESSION);
        when(sessions.verifyCsrf(SESSION, "csrf-token")).thenReturn(true);
    }

    @Test
    void submitsForTheAuthenticatedOwnerAndReturnsStableLinks() throws Exception {
        when(jobs.submit(any(), any())).thenReturn(view());

        mockMvc.perform(post("/api/v1/analysis-jobs")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-CSRF-Token", "csrf-token")
                        .header("Idempotency-Key", "intent-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":"40000000-0000-0000-0000-000000000001",
                                 "goal":"Explain chapter one","mode":"STANDARD"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/analysis-jobs/" + JOB_ID))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.links.self").value("/api/v1/analysis-jobs/" + JOB_ID))
                .andExpect(jsonPath("$.links.events").value("/api/v1/analysis-jobs/" + JOB_ID + "/events"));

        ArgumentCaptor<com.videopilot.identity.application.OwnerContext> owner =
                ArgumentCaptor.forClass(com.videopilot.identity.application.OwnerContext.class);
        verify(jobs).submit(owner.capture(), any());
        org.assertj.core.api.Assertions.assertThat(owner.getValue().ownerId().value()).isEqualTo(OWNER_ID);
    }

    @Test
    void rejectsInvalidIdempotencyKeysAtTheHttpBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/analysis-jobs")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-CSRF-Token", "csrf-token")
                        .header("Idempotency-Key", "contains spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":"40000000-0000-0000-0000-000000000001",
                                 "goal":"Explain","mode":"STANDARD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void exposesOwnerScopedDetailAndPagedQueries() throws Exception {
        when(jobs.get(any(), org.mockito.ArgumentMatchers.eq(JOB_ID))).thenReturn(view());
        when(jobs.list(any(), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(20), any()))
                .thenReturn(new AnalysisJobPage(List.of(view()), 1, 20, 1, 1));

        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()));
        mockMvc.perform(get("/api/v1/analysis-jobs")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.pagination.totalItems").value(1));
    }

    @Test
    void mapsOwnershipHidingAndIdempotencyFailuresToFrozenErrors() throws Exception {
        when(jobs.get(any(), org.mockito.ArgumentMatchers.eq(JOB_ID)))
                .thenThrow(new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));
        when(jobs.submit(any(), any()))
                .thenThrow(new AnalysisJobException(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED));

        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/analysis-jobs")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-CSRF-Token", "csrf-token")
                        .header("Idempotency-Key", "intent-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":"40000000-0000-0000-0000-000000000001",
                                 "goal":"Different","mode":"STANDARD"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private AnalysisJobView view() {
        Instant now = Instant.parse("2026-10-30T08:00:00Z");
        return new AnalysisJobView(
                JOB_ID, MEDIA_ID, "Explain chapter one", AnalysisMode.STANDARD,
                JobStatus.CREATED, Optional.empty(), 0, 0, Optional.empty(), now, now, Optional.empty());
    }
}
