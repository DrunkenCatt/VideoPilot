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
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventBatch;
import com.videopilot.analysis.application.AnalysisJobEventView;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.AnalysisJobPage;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentitySecurityConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
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
    private AnalysisJobEventApplicationService jobEvents;

    @MockitoBean(name = "analysisJobSseExecutor")
    private Executor analysisJobSseExecutor;

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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"code\":\"INVALID_REQUEST\"")));
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

    @Test
    void cancelsThroughTheStateMachineAndReturnsTheLatestOwnedView() throws Exception {
        when(jobEvents.cancel(any(), org.mockito.ArgumentMatchers.eq(JOB_ID), any()))
                .thenReturn(null);
        when(jobs.get(any(), org.mockito.ArgumentMatchers.eq(JOB_ID))).thenReturn(view());

        mockMvc.perform(post("/api/v1/analysis-jobs/{jobId}/cancellations", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()));

        verify(jobEvents).cancel(any(), org.mockito.ArgumentMatchers.eq(JOB_ID), any());
    }

    @Test
    void replaysSseEventsStrictlyAfterLastEventIdAndClosesAtTerminalState() throws Exception {
        AnalysisJobEventView terminal = new AnalysisJobEventView(
                JOB_ID,
                5,
                JobEventType.JOB_CANCELED,
                JobStatus.CANCELED,
                Optional.empty(),
                "Analysis job canceled.",
                Instant.parse("2026-11-02T08:00:00Z"),
                true);
        when(jobEvents.readAfter(any(), org.mockito.ArgumentMatchers.eq(JOB_ID),
                org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq(100)))
                .thenReturn(new AnalysisJobEventBatch(5, true, List.of(terminal)));

        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}/events", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("Last-Event-ID", "4")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("id:5")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"sequence\":5")));
    }

    @Test
    void rejectsMalformedLastEventIdBeforeOpeningAStream() throws Exception {
        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}/events", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("Last-Event-ID", "not-a-sequence")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"code\":\"INVALID_REQUEST\"")));
    }

    @Test
    void hidesForeignEventStreamsWithTheSameNotFoundSemantic() throws Exception {
        when(jobEvents.readAfter(any(), org.mockito.ArgumentMatchers.eq(JOB_ID),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(100)))
                .thenThrow(new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}/events", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"code\":\"RESOURCE_NOT_FOUND\"")));
    }

    @Test
    void exposesOnlyStableFailureFields() throws Exception {
        AnalysisJobView failed = new AnalysisJobView(
                JOB_ID, MEDIA_ID, "Explain chapter one", AnalysisMode.STANDARD,
                JobStatus.FAILED, Optional.empty(), 1, 5,
                Optional.of(new JobFailure(
                        JobErrorCategory.NON_RETRYABLE,
                        "MEDIA_PROBE_INVALID",
                        "The uploaded video could not be analyzed.",
                        false)),
                Instant.parse("2026-10-30T08:00:00Z"),
                Instant.parse("2026-10-30T08:01:00Z"),
                Optional.empty());
        when(jobs.get(any(), org.mockito.ArgumentMatchers.eq(JOB_ID))).thenReturn(failed);

        mockMvc.perform(get("/api/v1/analysis-jobs/{jobId}", JOB_ID)
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.code").value("MEDIA_PROBE_INVALID"))
                .andExpect(jsonPath("$.failure.retryable").value(false))
                .andExpect(jsonPath("$.failure.summary")
                        .value("The uploaded video could not be analyzed."));
    }

    private AnalysisJobView view() {
        Instant now = Instant.parse("2026-10-30T08:00:00Z");
        return new AnalysisJobView(
                JOB_ID, MEDIA_ID, "Explain chapter one", AnalysisMode.STANDARD,
                JobStatus.CREATED, Optional.empty(), 0, 0, Optional.empty(), now, now, Optional.empty());
    }
}
