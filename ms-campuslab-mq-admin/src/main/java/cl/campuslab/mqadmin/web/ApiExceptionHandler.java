package cl.campuslab.mqadmin.web;

import cl.campuslab.mqadmin.mq.AdminResourceNotFoundException;
import cl.campuslab.mqadmin.mq.InvalidRequeueRequestException;
import cl.campuslab.mqadmin.mq.ProtectedTopologyResourceException;
import cl.campuslab.mqadmin.mq.RabbitUnavailableException;
import cl.campuslab.mqadmin.mq.UnknownDlqException;
import java.util.stream.Collectors;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every branch here ends in a ProblemDetail with a status this design doc names
 * explicitly (400/404/409/503) - never a 500, and never a stack trace or claim contents
 * in the body (OWASP A09), mirroring catalog's/bookings'/the BFF's own convention.
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

    @ExceptionHandler(ProtectedTopologyResourceException.class)
    public ResponseEntity<ProblemDetail> handleProtectedResource(ProtectedTopologyResourceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(AdminResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(AdminResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or malformed JSON.");
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Catches any raw {@code AmqpException} not already translated to a more specific
     * exception above (e.g. the broker becoming unreachable mid-call in createQueue/
     * deleteQueue/createExchange/deleteExchange/createBinding/deleteBinding) - maps to 503
     * per the API contract's "RabbitMQ unreachable" case for every new endpoint (design
     * doc §3.2-§3.8), never an unhandled 500. {@link RabbitUnavailableException} is
     * handled separately above and does not extend this type, so there is no overlap.
     */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<ProblemDetail> handleAmqpException(AmqpException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ is currently unavailable.");
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
