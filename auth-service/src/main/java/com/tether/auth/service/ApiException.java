package com.tether.auth.service;

import org.springframework.http.HttpStatus;

/** Expected client errors; ApiExceptionHandler turns these into {"error": message} with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

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
}
