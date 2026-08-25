package com.example.smartpark.web;

import com.example.smartpark.workflow.CustomerServiceValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<WebDtos.ApiError> forbidden(ForbiddenOperationException exception) {
        return error(HttpStatus.FORBIDDEN, "Operation is not allowed for the current demo role");
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<WebDtos.ApiError> notFound(NoSuchElementException exception) {
        return error(HttpStatus.NOT_FOUND, "Requested resource was not found");
    }

    @ExceptionHandler(CustomerServiceValidationException.class)
    ResponseEntity<WebDtos.ApiError> customerValidation(CustomerServiceValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "Invalid customer service request");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<WebDtos.ApiError> conflict(RuntimeException exception) {
        String message = "Idempotency key was already used for another question".equals(exception.getMessage())
                ? "Idempotency-Key 已用于其他问题，请生成新的请求键"
                : "Request conflicts with current resource state";
        return error(HttpStatus.CONFLICT, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<WebDtos.ApiError> validation(IllegalArgumentException exception) {
        // Legacy contract: an idempotency-key reuse is a conflict, not a validation error.
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("idempotency") && message.contains("already used")) {
            return error(HttpStatus.CONFLICT, "Idempotency-Key 已用于其他决定，请生成新的请求键");
        }
        return error(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    ResponseEntity<WebDtos.ApiError> overloaded(java.util.concurrent.RejectedExecutionException exception) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "Too many collaboration runs; retry later");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<WebDtos.ApiError> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Malformed or incomplete request");
    }

    private static ResponseEntity<WebDtos.ApiError> error(HttpStatus status, String message) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(new WebDtos.ApiError(
                status.value(), status.getReasonPhrase(), safeMessage, Instant.now()));
    }
}
