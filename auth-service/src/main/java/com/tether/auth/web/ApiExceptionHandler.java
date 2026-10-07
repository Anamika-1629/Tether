package com.tether.auth.web;

import com.tether.auth.service.ApiException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatus());
        if (e.getRetryAfter() != null) {
            // Round up so clients never retry a moment too early
            long seconds = Math.max(1, e.getRetryAfter().toSeconds() + (e.getRetryAfter().toNanosPart() > 0 ? 1 : 0));
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        return response.body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return Map.of("error", "Validation failed", "fields", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> malformed(HttpMessageNotReadableException e) {
        return Map.of("error", "Malformed JSON request body");
    }

    /**
     * A request lost a race at a unique constraint. Usually two registrations with the same email;
     * rarely two new tenants drawing the same slug or join code, which is safe to retry.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        return Map.of("error", cause.contains("uq_users_email")
                ? "An account with this email already exists"
                : "Conflicting update, please retry");
    }
}
