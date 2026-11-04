package cl.campuslab.kafkaadmin.web;

import cl.campuslab.kafkaadmin.kafka.AdminResourceConflictException;
import cl.campuslab.kafkaadmin.kafka.AdminResourceNotFoundException;
import cl.campuslab.kafkaadmin.kafka.InvalidKafkaAdminRequestException;
import cl.campuslab.kafkaadmin.kafka.KafkaUnavailableException;
import cl.campuslab.kafkaadmin.kafka.ProtectedTopologyResourceException;
import java.util.stream.Collectors;
import org.apache.kafka.common.KafkaException;
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
 * in the body (OWASP A09), mirroring mq-admin's own convention.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(KafkaUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleKafkaUnavailable(KafkaUnavailableException ex) {
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

    @ExceptionHandler(AdminResourceConflictException.class)
    public ResponseEntity<ProblemDetail> handleResourceConflict(AdminResourceConflictException ex) {
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

    @ExceptionHandler(InvalidKafkaAdminRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidKafkaAdminRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
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
                HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud falta o no es JSON valido.");
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /** Respaldo para cualquier {@code KafkaException} no traducida (analogo al
     * {@code AmqpException} de mq-admin): 503, nunca un 500. */
    @ExceptionHandler(KafkaException.class)
    public ResponseEntity<ProblemDetail> handleKafkaException(KafkaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Kafka is currently unreachable.");
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
