package com.videopilot.infrastructure.retrieval;

import com.videopilot.answer.port.AnswerStore;

final class CitationLocalizationRule {

    private CitationLocalizationRule() {
    }

    static boolean isLocalized(AnswerStore.StoredCitation citation, long videoDurationMs) {
        return citation.startMs() >= 0
                && citation.endMs() > citation.startMs()
                && citation.endMs() <= videoDurationMs;
    }
}
