package cl.campuslab.kafkaadmin.web;

import cl.campuslab.kafkaadmin.kafka.KafkaUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every branch here ends in a ProblemDetail with a status this design doc names
 * explicitly (503) - never a 500, and never a stack trace or claim contents in the
 * body (OWASP A09), mirroring mq-admin's own convention.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(KafkaUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleKafkaUnavailable(KafkaUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
