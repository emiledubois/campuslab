package cl.campuslab.audit.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every branch here ends in a ProblemDetail with a status this design doc names
 * explicitly (400) - never a 500, and never a stack trace or claim contents in the
 * body (OWASP A09), mirroring bookings'/mq-admin's own convention.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidTimelineQueryException.class)
    public ResponseEntity<ProblemDetail> handleInvalidQuery(InvalidTimelineQueryException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
