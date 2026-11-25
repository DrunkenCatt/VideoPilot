package com.videopilot.infrastructure.media.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.PlaybackProgress;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaybackProgressPersistenceAdapterTest {

    private static final OwnerId OWNER = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000099"));
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000099");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-25T08:00:00Z");

    private final PlaybackProgressMapper mapper = mock(PlaybackProgressMapper.class);
    private final PlaybackProgressPersistenceAdapter adapter = new PlaybackProgressPersistenceAdapter(mapper);

    @Test
    void mapsOwnerScopedPersistenceFactsToPlaybackProgress() {
        PlaybackProgressRow row = new PlaybackProgressRow();
        row.setOwnerId(OWNER.value().toString());
        row.setMediaId(MEDIA_ID.toString());
        row.setPositionMs(42_000);
        row.setUpdatedAt(UPDATED_AT);
        when(mapper.selectOwned(OWNER.value().toString(), MEDIA_ID.toString())).thenReturn(row);

        var progress = adapter.find(OWNER, MEDIA_ID);

        assertThat(progress).contains(new PlaybackProgress(MEDIA_ID, OWNER, 42_000, UPDATED_AT));
    }

    @Test
    void returnsEmptyWhenTheOwnerHasNoSavedProgress() {
        assertThat(adapter.find(OWNER, MEDIA_ID)).isEmpty();
        verify(mapper).selectOwned(OWNER.value().toString(), MEDIA_ID.toString());
    }

    @Test
    void upsertsAndReturnsTheCommittedProgressValue() {
        PlaybackProgress progress = new PlaybackProgress(MEDIA_ID, OWNER, 84_000, UPDATED_AT);

        assertThat(adapter.save(progress)).isSameAs(progress);
        verify(mapper).upsert(
                OWNER.value().toString(), MEDIA_ID.toString(), 84_000, UPDATED_AT);
    }
}
