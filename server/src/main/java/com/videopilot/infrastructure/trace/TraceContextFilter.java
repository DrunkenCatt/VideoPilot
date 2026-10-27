package com.videopilot.infrastructure.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = TraceContext.requestIdOrNew(request.getHeader(TraceContext.REQUEST_ID_HEADER));
        String jobId = TraceContext.optionalId(request.getHeader(TraceContext.JOB_ID_HEADER));
        String runId = TraceContext.optionalId(request.getHeader(TraceContext.RUN_ID_HEADER));

        response.setHeader(TraceContext.REQUEST_ID_HEADER, requestId);
        try (TraceContext.Scope ignored = TraceContext.open(requestId, jobId, runId)) {
            filterChain.doFilter(request, response);
        }
    }
}
