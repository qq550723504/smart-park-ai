package com.example.smartpark.web;

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

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<WebDtos.ApiError> notFound(NoSuchElementException exception) {
        return error(HttpStatus.NOT_FOUND, "Requested resource was not found");
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    ResponseEntity<WebDtos.ApiError> conflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, "Request conflicts with current resource state");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<WebDtos.ApiError> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Malformed or incomplete approval request");
    }

    private static ResponseEntity<WebDtos.ApiError> error(HttpStatus status, String message) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(new WebDtos.ApiError(
                status.value(),
                status.getReasonPhrase(),
                safeMessage,
                Instant.now()));
    }
}
