package com.instapay.payment.adapters.web;

import com.instapay.payment.application.ResourceNotFoundException;
import com.instapay.payment.application.port.out.ConcurrencyConflictException;
import com.instapay.payment.domain.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

  /** Malformed values ("*.invalid") are 400; well-formed requests that break a rule are 422. */
  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException e) {
    HttpStatus status =
        e.code().endsWith(".invalid") ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_ENTITY;
    return Problems.of(status, e.code(), e.getMessage());
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail notFound(ResourceNotFoundException e) {
    return Problems.of(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
  }

  @ExceptionHandler(ConcurrencyConflictException.class)
  ProblemDetail conflict(ConcurrencyConflictException e) {
    return Problems.of(HttpStatus.CONFLICT, "concurrency.conflict", "Concurrent update, retry");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("Unexpected error", e);
    return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Unexpected error");
  }
}
