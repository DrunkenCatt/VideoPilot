package com.videopilot.media.port;

import com.videopilot.media.application.MediaProbeResult;
import java.nio.file.Path;

public interface MediaProbe {

    MediaProbeResult probe(Path mediaFile);
}
