package com.videopilot.infrastructure.web;

public record ApiErrorResponse(
        String code,
        String message,
        boolean retryable,
        String requestId) {
}
