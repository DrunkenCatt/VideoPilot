package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.error.ErrorCode;
import com.videopilot.infrastructure.error.VideoPilotException;
import com.videopilot.infrastructure.trace.TraceContext;
import com.videopilot.infrastructure.trace.TraceContextFilter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class WebBoundaryContractTest {

    private final AtomicReference<Map<String, String>> observedTrace = new AtomicReference<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController(observedTrace))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceContextFilter())
                .build();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesSafeTraceFieldsAndClearsThemAfterTheRequest() throws Exception {
        mockMvc.perform(get("/test/trace")
                        .header(TraceContext.REQUEST_ID_HEADER, "request-123")
                        .header(TraceContext.JOB_ID_HEADER, "job-456")
                        .header(TraceContext.RUN_ID_HEADER, "run-789"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.REQUEST_ID_HEADER, "request-123"));

        assertThat(observedTrace.get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                TraceContext.REQUEST_ID, "request-123",
                TraceContext.JOB_ID, "job-456",
                TraceContext.RUN_ID, "run-789"));
        assertThat(MDC.getCopyOfContextMap()).isNull();
    }

    @Test
    void traceScopePreservesUnrelatedContextAndRestoresThePreviousValues() {
        MDC.put("tenantId", "tenant-1");
        MDC.put(TraceContext.JOB_ID, "previous-job");

        try (TraceContext.Scope ignored = TraceContext.open("request-1", null, "run-1")) {
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "tenantId", "tenant-1",
                    TraceContext.REQUEST_ID, "request-1",
                    TraceContext.RUN_ID, "run-1"));
        }

        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenantId", "tenant-1",
                TraceContext.JOB_ID, "previous-job"));
    }

    @Test
    void replacesUnsafeRequestIdAndDoesNotLeakExceptionDetails(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/failure")
                        .header(TraceContext.REQUEST_ID_HEADER, "unsafe request id"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(
                        TraceContext.REQUEST_ID_HEADER,
                        matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("[0-9a-f]{32}")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("raw-model-response-secret"))));

        assertThat(output).doesNotContain("raw-model-response-secret");
    }

    @Test
    void exposesAStableRetryHintForKnownDependencyFailures() throws Exception {
        mockMvc.perform(get("/test/dependency-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("A required dependency is unavailable."))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void convertsFrameworkValidationFailuresToTheSameErrorContract() throws Exception {
        mockMvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("[0-9a-f]{32}")));
    }

    @RestController
    private static class ProbeController {

        private final AtomicReference<Map<String, String>> observedTrace;

        private ProbeController(AtomicReference<Map<String, String>> observedTrace) {
            this.observedTrace = observedTrace;
        }

        @GetMapping("/test/trace")
        String trace() {
            observedTrace.set(MDC.getCopyOfContextMap());
            return "ok";
        }

        @GetMapping("/test/failure")
        String failure() {
            throw new IllegalStateException("raw-model-response-secret");
        }

        @GetMapping("/test/dependency-failure")
        String dependencyFailure() {
            throw new VideoPilotException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        @GetMapping("/test/required")
        String required(@RequestParam String value) {
            return value;
        }
    }
}
