package com.instapay.payment.adapters.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 7807 problems with a stable machine-readable {@code code}. */
final class Problems {

  private Problems() {}

  static ProblemDetail of(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://instapay.example/problems/" + code));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    return problem;
  }
}
