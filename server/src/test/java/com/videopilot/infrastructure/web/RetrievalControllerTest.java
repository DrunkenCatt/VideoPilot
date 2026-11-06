package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalControllerTest {

    @Test
    void delegatesOwnerScopedQueryAndMapsWeightedScoresWithoutVectors() {
        RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
        IdentityPrincipal principal = mock(IdentityPrincipal.class);
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(principal.ownerContext()).thenReturn(owner);
        EvidenceHit hit = new EvidenceHit(
                chunkId, List.of(UUID.randomUUID()), 0, 60_000, "Redis string evidence",
                List.of(EvidenceHit.Channel.ASR),
                new EvidenceHit.Scores(0.9, 1.0, 0.6, 0.5, 1.0, 0.25, 0, 0, 0, 0.85));
        when(retrieval.retrieve(owner, jobId, "Redis string", 3)).thenReturn(
                new RetrievalApplicationService.RetrievalResult(
                        "Redis string", RetrievalApplicationService.FORMULA_VERSION,
                        "embedding-v1", "chunk-v1", List.of(EvidenceHit.DegradedChannel.VECTOR_STORE),
                        List.of(hit)));

        var response = new RetrievalController(retrieval).retrieve(principal, jobId, "Redis string", 3);

        verify(retrieval).retrieve(owner, jobId, "Redis string", 3);
        assertThat(response.degradedChannels()).containsExactly(
                com.videopilot.infrastructure.web.contract.M3ApiContracts.DegradedChannel.VECTOR_STORE);
        assertThat(response.hits()).singleElement().satisfies(value -> {
            assertThat(value.chunkId()).isEqualTo(chunkId);
            assertThat(value.scores().semanticWeighted()).isEqualTo(0.6);
            assertThat(value.scores().keywordWeighted()).isEqualTo(0.25);
        });
    }
}
