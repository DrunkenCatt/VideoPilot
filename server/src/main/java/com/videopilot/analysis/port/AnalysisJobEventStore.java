package com.videopilot.analysis.port;

import com.videopilot.analysis.application.AnalysisJobEventView;
import com.videopilot.identity.domain.OwnerId;
import java.util.List;
import java.util.UUID;

public interface AnalysisJobEventStore {

    List<AnalysisJobEventView> findAfter(OwnerId ownerId, UUID jobId, long afterSequence, int limit);
}
