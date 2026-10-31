package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import java.util.concurrent.CompletionStage;

public interface AnalysisMessagePublisher {

    CompletionStage<PublishResult> publish(AnalysisDispatchMessage message);

    enum PublishResult {
        CONFIRMED,
        NEGATIVE_ACK,
        UNROUTABLE,
        CONFIRM_TIMEOUT,
        PUBLISH_ERROR
    }
}
