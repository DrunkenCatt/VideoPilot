package com.videopilot.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videopilot.identity.application.IdentityException;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.application.SessionApplicationService.LoginResult;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentitySecurityConfiguration;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@WebMvcTest(value = SessionController.class, properties = "videopilot.identity.enabled=true")
@Import({IdentitySecurityConfiguration.class, SessionSecurityContractTest.AsyncSecurityProbe.class})
class SessionSecurityContractTest {

    private static final UUID SESSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final AuthenticatedSession SESSION = new AuthenticatedSession(
            SESSION_ID,
            new OwnerId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
            "learner",
            "csrf-token",
            Instant.parse("2026-10-29T16:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionApplicationService sessions;

    @BeforeEach
    void authenticateCookie() {
        when(sessions.authenticate("opaque-session-token")).thenReturn(SESSION);
    }

    @Test
    void createsAnHttpOnlyStrictSessionCookieWithoutExposingItsValueInJson() throws Exception {
        when(sessions.login(any())).thenReturn(new LoginResult("opaque-session-token", SESSION));

        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"learner","password":"correct password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("VP_SESSION=opaque-session-token")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.userId").value(SESSION.ownerId().value().toString()))
                .andExpect(jsonPath("$.csrfToken").value("csrf-token"))
                .andExpect(content().string(not(containsString("opaque-session-token"))));
    }

    @Test
    void rejectsUnknownJsonFieldsThatCouldAttemptToForgeOwnership() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"learner","password":"correct","ownerId":"forged"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(sessions, never()).login(any());
    }

    @Test
    void usesTheSameUnauthorizedResponseForBadCredentialsAndMissingSessions() throws Exception {
        when(sessions.login(any())).thenThrow(new IdentityException(IdentityException.Reason.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"missing","password":"guess"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(get("/api/v1/sessions/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void restoresTheOwnerOnlyFromTheAuthenticatedServerSession() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/current")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-Owner-Id", "20000000-0000-0000-0000-000000000002")
                        .queryParam("ownerId", "20000000-0000-0000-0000-000000000002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(SESSION.ownerId().value().toString()))
                .andExpect(jsonPath("$.username").value("learner"));
    }

    @Test
    void reportsSessionStoreOutagesAsRetryableDependencyFailures() throws Exception {
        when(sessions.authenticate("opaque-session-token"))
                .thenThrow(new IdentityException(IdentityException.Reason.DEPENDENCY_UNAVAILABLE));

        mockMvc.perform(get("/api/v1/sessions/current")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void requiresCsrfForLogoutAndRevokesOnlyTheAuthenticatedSession() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/current")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        verify(sessions, never()).logout(any());

        when(sessions.verifyCsrf(SESSION, "csrf-token")).thenReturn(true);
        mockMvc.perform(delete("/api/v1/sessions/current")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token"))
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(sessions).logout(SESSION_ID);

        mockMvc.perform(delete("/api/v1/sessions/current"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void authenticatesTheAsyncDispatchUsedByPersistedEventStreams() throws Exception {
        MvcResult initial = mockMvc.perform(get("/test/async-owner")
                        .cookie(new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string(SESSION.ownerId().value().toString()));
    }

    @RestController
    static class AsyncSecurityProbe {

        @GetMapping("/test/async-owner")
        Callable<String> owner(@AuthenticationPrincipal IdentityPrincipal principal) {
            return () -> principal.ownerContext().ownerId().value().toString();
        }
    }
}
