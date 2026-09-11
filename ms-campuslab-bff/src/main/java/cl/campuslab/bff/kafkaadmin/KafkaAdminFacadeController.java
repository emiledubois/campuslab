package cl.campuslab.bff.kafkaadmin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Facade: structurally identical to MqAdminFacadeController (design doc §2/§6),
 * GET-only - no requeue endpoint exists for kafka-admin this slice (design doc §3's
 * explicit scope boundary). No new SecurityConfig rule needed: the existing {@code
 * .requestMatchers("/api/admin/**").hasRole("ADMIN")} rule already matches {@code
 * /api/admin/kafka/**} by prefix (verified against the current SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/kafka")
public class KafkaAdminFacadeController {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminFacadeController.class);
    private static final String KAFKA_ADMIN_PATH = "/api/admin/kafka";

    private final RestClient kafkaAdminRestClient;

    public KafkaAdminFacadeController(RestClient kafkaAdminRestClient) {
        this.kafkaAdminRestClient = kafkaAdminRestClient;
    }

    @GetMapping("/topics")
    public ResponseEntity<?> topics(HttpServletRequest request) {
        return forward(KAFKA_ADMIN_PATH + "/topics", request);
    }

    @GetMapping("/consumer-groups")
    public ResponseEntity<?> consumerGroups(HttpServletRequest request) {
        return forward(KAFKA_ADMIN_PATH + "/consumer-groups", request);
    }

    @GetMapping("/dlt")
    public ResponseEntity<?> dlt(HttpServletRequest request) {
        return forward(KAFKA_ADMIN_PATH + "/dlt", request);
    }

    private ResponseEntity<?> forward(String path, HttpServletRequest request) {
        try {
            return kafkaAdminRestClient.method(HttpMethod.GET)
                    .uri(path)
                    .headers(headers -> copyForwardedHeaders(request, headers))
                    .exchange((clientRequest, clientResponse) -> {
                        byte[] responseBody = clientResponse.getBody().readAllBytes();
                        HttpHeaders responseHeaders = new HttpHeaders();
                        MediaType contentType = clientResponse.getHeaders().getContentType();
                        if (contentType != null) {
                            responseHeaders.setContentType(contentType);
                        }
                        return ResponseEntity.status(clientResponse.getStatusCode())
                                .headers(responseHeaders)
                                .body(responseBody);
                    });
        } catch (ResourceAccessException ex) {
            log.warn("kafka-admin service unreachable: path=[{}]", path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The kafka-admin service is currently unavailable.");
            problem.setTitle("Service Unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }
    }

    private static void copyForwardedHeaders(HttpServletRequest request, HttpHeaders headers) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }
}
