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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Facade: structurally identical to MqAdminFacadeController (design doc §2/§6). No new
 * SecurityConfig rule needed: the existing {@code .requestMatchers("/api/admin/**")
 * .hasRole("ADMIN")} rule already matches {@code /api/admin/kafka/**} by prefix (verified
 * against the current SecurityConfig). kafka-admin-write.md §7 agrega 6 rutas con
 * escritura; el BFF reenvia bytes crudos sin validar ni deserializar (A08).
 *
 * <p>Unica diferencia deliberada con mq-admin (§7.2): los path variables viajan como
 * variables de plantilla URI, para que RestClient los codifique y un '{' o '%' en un nombre
 * no se interprete como plantilla.
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
        return forward(HttpMethod.GET, KAFKA_ADMIN_PATH + "/topics", request, null);
    }

    @GetMapping("/consumer-groups")
    public ResponseEntity<?> consumerGroups(HttpServletRequest request) {
        return forward(HttpMethod.GET, KAFKA_ADMIN_PATH + "/consumer-groups", request, null);
    }

    @GetMapping("/dlt")
    public ResponseEntity<?> dlt(HttpServletRequest request) {
        return forward(HttpMethod.GET, KAFKA_ADMIN_PATH + "/dlt", request, null);
    }

    @PostMapping("/topics")
    public ResponseEntity<?> createTopic(@RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, KAFKA_ADMIN_PATH + "/topics", request, body);
    }

    @DeleteMapping("/topics/{name}")
    public ResponseEntity<?> deleteTopic(@PathVariable String name, HttpServletRequest request) {
        return forward(HttpMethod.DELETE, KAFKA_ADMIN_PATH + "/topics/{name}", request, null, name);
    }

    @PatchMapping("/topics/{name}/config")
    public ResponseEntity<?> updateTopicConfig(
            @PathVariable String name, @RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.PATCH, KAFKA_ADMIN_PATH + "/topics/{name}/config", request, body, name);
    }

    @PostMapping("/topics/{name}/partitions")
    public ResponseEntity<?> increasePartitions(
            @PathVariable String name, @RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, KAFKA_ADMIN_PATH + "/topics/{name}/partitions", request, body, name);
    }

    @DeleteMapping("/consumer-groups/{groupId}")
    public ResponseEntity<?> deleteConsumerGroup(@PathVariable String groupId, HttpServletRequest request) {
        return forward(HttpMethod.DELETE, KAFKA_ADMIN_PATH + "/consumer-groups/{groupId}", request, null, groupId);
    }

    @PostMapping("/consumer-groups/{groupId}/reset-offsets")
    public ResponseEntity<?> resetOffsets(
            @PathVariable String groupId, @RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, KAFKA_ADMIN_PATH + "/consumer-groups/{groupId}/reset-offsets",
                request, body, groupId);
    }

    private ResponseEntity<?> forward(
            HttpMethod method, String pathTemplate, HttpServletRequest request, byte[] body, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = kafkaAdminRestClient.method(method)
                    .uri(pathTemplate, uriVariables)
                    .headers(headers -> copyForwardedHeaders(request, headers));
            RestClient.RequestHeadersSpec<?> requestSpec = body != null ? spec.body(body) : spec;

            return requestSpec.exchange((clientRequest, clientResponse) -> {
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
            log.warn("kafka-admin service unreachable: method=[{}] path=[{}]", method, pathTemplate);
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
        String contentType = request.getContentType();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
    }
}
