package com.videopilot.infrastructure.web;

import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.answer.application.AnswerException;
import com.videopilot.context.application.VideoContextQueryException;
import com.videopilot.evaluation.application.EvaluationException;
import com.videopilot.identity.application.IdentityException;
import com.videopilot.infrastructure.error.ErrorCode;
import com.videopilot.infrastructure.error.VideoPilotException;
import com.videopilot.infrastructure.trace.TraceContext;
import com.videopilot.media.application.MediaUploadException;
import com.videopilot.retrieval.application.RetrievalException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

    @ExceptionHandler(IdentityException.class)
    ResponseEntity<ApiErrorResponse> handleIdentityFailure(IdentityException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_CREDENTIALS -> ErrorCode.INVALID_CREDENTIALS;
            case AUTHENTICATION_REQUIRED -> ErrorCode.AUTHENTICATION_REQUIRED;
            case CSRF_REJECTED -> ErrorCode.CSRF_REJECTED;
            case RATE_LIMITED -> ErrorCode.RATE_LIMITED;
            case DEPENDENCY_UNAVAILABLE -> ErrorCode.DEPENDENCY_UNAVAILABLE;
        };
        LOGGER.warn("Identity request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(AnalysisJobException.class)
    ResponseEntity<ApiErrorResponse> handleAnalysisJobFailure(AnalysisJobException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case RESOURCE_NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case MEDIA_NOT_READY -> ErrorCode.MEDIA_NOT_READY;
            case IDEMPOTENCY_KEY_REUSED -> ErrorCode.IDEMPOTENCY_KEY_REUSED;
            case JOB_NOT_CANCELABLE -> ErrorCode.JOB_NOT_CANCELABLE;
            case CONFLICT -> ErrorCode.CONFLICT;
        };
        LOGGER.warn("Analysis job request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(VideoContextQueryException.class)
    ResponseEntity<ApiErrorResponse> handleVideoContextFailure(VideoContextQueryException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case NOT_READY -> ErrorCode.CONFLICT;
            case DEPENDENCY_UNAVAILABLE -> ErrorCode.DEPENDENCY_UNAVAILABLE;
        };
        LOGGER.warn("Video context request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(RetrievalException.class)
    ResponseEntity<ApiErrorResponse> handleRetrievalFailure(RetrievalException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case NOT_READY -> ErrorCode.CONFLICT;
        };
        LOGGER.warn("Retrieval request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(AnswerException.class)
    ResponseEntity<ApiErrorResponse> handleAnswerFailure(AnswerException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case NOT_READY, IN_PROGRESS -> ErrorCode.CONFLICT;
            case BUDGET_EXHAUSTED -> ErrorCode.BUDGET_EXHAUSTED;
            case IDEMPOTENCY_KEY_REUSED -> ErrorCode.IDEMPOTENCY_KEY_REUSED;
            case DEPENDENCY_UNAVAILABLE -> ErrorCode.DEPENDENCY_UNAVAILABLE;
        };
        LOGGER.warn("Answer request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(EvaluationException.class)
    ResponseEntity<ApiErrorResponse> handleEvaluationFailure(EvaluationException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case IDEMPOTENCY_KEY_REUSED -> ErrorCode.IDEMPOTENCY_KEY_REUSED;
            case DATASET_NOT_READY -> ErrorCode.CONFLICT;
            case EXECUTION_FAILED -> ErrorCode.INTERNAL_ERROR;
        };
        LOGGER.warn("Evaluation request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler(MediaUploadException.class)
    ResponseEntity<ApiErrorResponse> handleMediaUploadFailure(MediaUploadException exception) {
        ErrorCode errorCode = switch (exception.reason()) {
            case INVALID_REQUEST -> ErrorCode.INVALID_REQUEST;
            case RESOURCE_NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case UPLOAD_EXPIRED -> ErrorCode.UPLOAD_EXPIRED;
            case CONFLICT -> ErrorCode.CONFLICT;
            case DEPENDENCY_UNAVAILABLE -> ErrorCode.DEPENDENCY_UNAVAILABLE;
        };
        LOGGER.warn("Media upload request failed code={}", errorCode);
        return response(errorCode, statusFor(errorCode));
    }

    @ExceptionHandler({
        BindException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class
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
            case AUTHENTICATION_REQUIRED, INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case CSRF_REJECTED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_REUSED, UPLOAD_EXPIRED, MEDIA_NOT_READY,
                    JOB_NOT_CANCELABLE, CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BUDGET_EXHAUSTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
