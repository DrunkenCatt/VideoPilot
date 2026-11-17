package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PlaybackProgressRequest;
import com.videopilot.media.application.PlaybackProgressApplicationService;
import com.videopilot.media.application.PlaybackProgressView;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.PlaybackProgress;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.PlaybackProgressStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaybackProgressControllerTest {

    private static final OwnerId OWNER_ID = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000120"));
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000120");
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    @Test
    void mapsGetAndPutToTheOwnerScopedApplicationService() {
        Store store = new Store();
        PlaybackProgressController controller = new PlaybackProgressController(
                new PlaybackProgressApplicationService(store, store, () -> NOW));
        IdentityPrincipal principal = new IdentityPrincipal(new AuthenticatedSession(
                UUID.randomUUID(), OWNER_ID, "learner", "csrf", NOW.plusSeconds(3_600)));

        assertThat(controller.get(principal, MEDIA_ID).positionMs()).isZero();
        assertThat(controller.save(principal, MEDIA_ID, new PlaybackProgressRequest(4_200)))
                .satisfies(response -> {
                    assertThat(response.positionMs()).isEqualTo(4_200);
                    assertThat(response.updatedAt()).isEqualTo(NOW);
                });
    }

    private static final class Store implements MediaAssetQueryStore, PlaybackProgressStore {
        private Optional<PlaybackProgress> progress = Optional.empty();

        @Override
        public AssetPage findPage(OwnerId ownerId, int page, int pageSize) {
            return new AssetPage(List.of(), 0);
        }

        @Override
        public Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId) {
            return Optional.of(new MediaAsset(
                    MEDIA_ID, OWNER_ID, "a".repeat(64), "media/owner/source", "lesson.mp4",
                    1024, "video/mp4", MediaStatus.READY, Optional.of(10_000L), 1, NOW, NOW));
        }

        @Override
        public StorageUsage summarizeStorage(OwnerId ownerId) {
            return new StorageUsage(0, 0);
        }

        @Override
        public Optional<PlaybackProgress> find(OwnerId ownerId, UUID mediaId) {
            return progress;
        }

        @Override
        public PlaybackProgress save(PlaybackProgress value) {
            progress = Optional.of(value);
            return value;
        }
    }
}
