package com.videopilot.infrastructure.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.answer.port.AnswerStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationLocalizationRuleTest {

    @Test
    void acceptsARealSupplementaryCitationOutsideTheReviewedCoreAnswerWindow() {
        var citation = new AnswerStore.StoredCitation(
                UUID.randomUUID(), UUID.randomUUID(), 420_000, 434_747,
                "ASR", "我们接下来会按照顺序逐个学习每一组命令", "NORMALIZED");

        assertThat(CitationLocalizationRule.isLocalized(citation, 434_747)).isTrue();
    }

    @Test
    void rejectsCitationWindowsOutsideTheVideo() {
        var citation = new AnswerStore.StoredCitation(
                UUID.randomUUID(), UUID.randomUUID(), 420_000, 434_748,
                "ASR", "越过视频结尾", "EXACT");

        assertThat(CitationLocalizationRule.isLocalized(citation, 434_747)).isFalse();
    }
}
