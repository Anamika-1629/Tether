package com.tether.auth.service;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** Expected client errors; ApiExceptionHandler turns these into {"error": message} with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** Sent as Retry-After on 429 responses; null otherwise. */
    private final Duration retryAfter;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    private ApiException(HttpStatus status, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public HttpStatus getStatus() { return status; }
    public Duration getRetryAfter() { return retryAfter; }

    /** Same message for unknown email and wrong password, so callers cannot probe which emails exist. */
    public static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    public static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
    }

    public static ApiException invalidJoinCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Join code is not valid");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException tooManyAttempts(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.", retryAfter);
    }
}
