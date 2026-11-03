package com.videopilot.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentitySecurityConfiguration;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaAssetPage;
import com.videopilot.media.application.MediaAssetQueryApplicationService;
import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.application.UploadPartView;
import com.videopilot.media.application.UploadSessionView;
import com.videopilot.media.domain.MediaStatus;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = {MediaUploadController.class, MediaAssetController.class},
        properties = "videopilot.identity.enabled=true")
@Import(IdentitySecurityConfiguration.class)
class MediaUploadControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000009");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
    private static final UUID UPLOAD_ID = UUID.fromString("30000000-0000-0000-0000-000000000009");
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final AuthenticatedSession SESSION = new AuthenticatedSession(
            UUID.randomUUID(), new OwnerId(OWNER_ID), "learner", "csrf-token", NOW.plusSeconds(3600));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaUploadApplicationService uploads;

    @MockitoBean
    private MediaAssetQueryApplicationService mediaAssets;

    @MockitoBean
    private SessionApplicationService sessions;

    @BeforeEach
    void authenticate() {
        when(sessions.authenticate("opaque-session-token")).thenReturn(SESSION);
        when(sessions.verifyCsrf(SESSION, "csrf-token")).thenReturn(true);
    }

    @Test
    void createsAnUploadSessionForTheAuthenticatedOwner() throws Exception {
        when(uploads.create(any(), any())).thenReturn(new UploadSessionView(
                UPLOAD_ID, MEDIA_ID, 8_388_608, 10_000, NOW.plusSeconds(3600)));

        mockMvc.perform(post("/api/v1/media-uploads")
                        .cookie(cookie())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalFilename":"lecture.mp4","contentLength":10000000,
                                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                 "contentType":"video/mp4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/media-uploads/" + UPLOAD_ID))
                .andExpect(jsonPath("$.mediaId").value(MEDIA_ID.toString()))
                .andExpect(jsonPath("$.partsUrl").value("/api/v1/media-uploads/" + UPLOAD_ID + "/parts"));
        verify(uploads).create(
                eq(new com.videopilot.identity.application.OwnerContext(SESSION.ownerId())), any());
    }

    @Test
    void returnsAPresignedUrlForOnePart() throws Exception {
        when(uploads.createPart(any(), eq(UPLOAD_ID), eq(1))).thenReturn(new UploadPartView(
                1, URI.create("http://localhost:9000/signed-part"), NOW.plusSeconds(900)));

        mockMvc.perform(post("/api/v1/media-uploads/{uploadId}/parts", UPLOAD_ID)
                        .cookie(cookie())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partNumber\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").value("http://localhost:9000/signed-part"));
    }

    @Test
    void completesAndReadsTheSameMediaContract() throws Exception {
        when(uploads.complete(any(), eq(UPLOAD_ID), any())).thenReturn(asset());
        when(mediaAssets.get(any(), eq(MEDIA_ID))).thenReturn(asset());

        mockMvc.perform(post("/api/v1/media-uploads/{uploadId}/completion", UPLOAD_ID)
                        .cookie(cookie())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parts\":[{\"partNumber\":1,\"etag\":\"etag-1\"}]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        mockMvc.perform(get("/api/v1/media-assets/{mediaId}", MEDIA_ID).cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value("a".repeat(64)));
    }

    @Test
    void listsPersistedMediaWithStablePaginationMetadata() throws Exception {
        when(mediaAssets.list(any(), eq(2), eq(10)))
                .thenReturn(new MediaAssetPage(java.util.List.of(asset()), 2, 10, 11, 2));

        mockMvc.perform(get("/api/v1/media-assets?page=2&pageSize=10").cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mediaId").value(MEDIA_ID.toString()))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.pagination.page").value(2))
                .andExpect(jsonPath("$.pagination.totalItems").value(11));
        verify(mediaAssets).list(
                eq(new com.videopilot.identity.application.OwnerContext(SESSION.ownerId())),
                eq(2), eq(10));
    }

    @Test
    void rejectsInvalidMediaPaginationAtTheHttpBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/media-assets?page=0&pageSize=101").cookie(cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void abortIsIdempotentAtTheHttpBoundary() throws Exception {
        mockMvc.perform(delete("/api/v1/media-uploads/{uploadId}", UPLOAD_ID)
                        .cookie(cookie())
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isNoContent());
        verify(uploads).abort(any(), eq(UPLOAD_ID));
    }

    private jakarta.servlet.http.Cookie cookie() {
        return new jakarta.servlet.http.Cookie("VP_SESSION", "opaque-session-token");
    }

    private MediaAssetView asset() {
        return new MediaAssetView(
                MEDIA_ID, "lecture.mp4", 10_000_000, "a".repeat(64), MediaStatus.UPLOADED,
                Optional.empty(), NOW, NOW);
    }
}
