package com.videopilot.context.port;

import java.io.IOException;
import java.nio.file.Path;

public interface DerivedMediaStore {
    void put(String objectKey, Path source, String contentType, String sha256) throws IOException;
}
