package com.videopilot.infrastructure.trace;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

public final class TraceContext {

    public static final String REQUEST_ID = "requestId";
    public static final String JOB_ID = "jobId";
    public static final String RUN_ID = "runId";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String JOB_ID_HEADER = "X-Job-Id";
    public static final String RUN_ID_HEADER = "X-Run-Id";

    private static final Pattern SAFE_TRACE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    private TraceContext() {
    }

    public static String requestIdOrNew(String candidate) {
        return isSafe(candidate) ? candidate : UUID.randomUUID().toString().replace("-", "");
    }

    public static String optionalId(String candidate) {
        return isSafe(candidate) ? candidate : null;
    }

    public static Scope open(String requestId, String jobId, String runId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        putOrRemove(REQUEST_ID, requestId);
        putOrRemove(JOB_ID, jobId);
        putOrRemove(RUN_ID, runId);
        return () -> restore(previous);
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE_TRACE_VALUE.matcher(value).matches();
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static void restore(Map<String, String> previous) {
        MDC.clear();
        if (previous != null) {
            MDC.setContextMap(previous);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
