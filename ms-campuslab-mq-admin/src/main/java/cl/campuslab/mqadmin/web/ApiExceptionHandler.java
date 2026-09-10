package cl.campuslab.mqadmin.web;

import cl.campuslab.mqadmin.mq.InvalidRequeueRequestException;
import cl.campuslab.mqadmin.mq.RabbitUnavailableException;
import cl.campuslab.mqadmin.mq.UnknownDlqException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every branch here ends in a ProblemDetail with a status this design doc names
 * explicitly (400/503) - never a 500, and never a stack trace or claim contents in the
 * body (OWASP A09), mirroring catalog's/bookings'/the BFF's own convention.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownDlqException.class)
    public ResponseEntity<ProblemDetail> handleUnknownDlq(UnknownDlqException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(InvalidRequeueRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequeueRequest(InvalidRequeueRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(RabbitUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleRabbitUnavailable(RabbitUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
