package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;

public interface AnalysisMessagePublisher {

    PublishResult publish(AnalysisDispatchMessage message);

    enum PublishResult {
        CONFIRMED,
        NEGATIVE_ACK,
        UNROUTABLE,
        CONFIRM_TIMEOUT,
        PUBLISH_ERROR
    }
}
