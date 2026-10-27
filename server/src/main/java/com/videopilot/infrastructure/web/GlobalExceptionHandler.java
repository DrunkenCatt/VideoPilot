package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.error.ErrorCode;
import com.videopilot.infrastructure.error.VideoPilotException;
import com.videopilot.infrastructure.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VideoPilotException.class)
    ResponseEntity<ApiErrorResponse> handleKnownFailure(VideoPilotException exception) {
        ErrorCode errorCode = exception.errorCode();
        LOGGER.warn("Request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler({
        BindException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        LOGGER.warn("Invalid request type={}", exception.getClass().getName());
        return response(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpectedFailure(Exception exception) {
        // Exception messages may contain credentials, transcripts, or raw provider output.
        LOGGER.error("Unexpected request failure type={}", exception.getClass().getName());
        return response(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> response(ErrorCode errorCode, HttpStatus status) {
        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.name(),
                errorCode.publicMessage(),
                errorCode.retryable(),
                MDC.get(TraceContext.REQUEST_ID));
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
