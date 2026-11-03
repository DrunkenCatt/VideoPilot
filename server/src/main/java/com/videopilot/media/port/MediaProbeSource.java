package com.videopilot.media.port;

import com.videopilot.media.domain.MediaAsset;
import java.io.IOException;
import java.nio.file.Path;

public interface MediaProbeSource {

    void copyTo(MediaAsset asset, Path target) throws IOException;
}
