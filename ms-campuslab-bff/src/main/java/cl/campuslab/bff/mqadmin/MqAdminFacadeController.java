package cl.campuslab.bff.mqadmin;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Facade: structurally identical to CatalogFacadeController/BookingsFacadeController
 * (design doc §6) - the browser only ever talks to the BFF at this same path, raw-bytes-
 * forward-both-ways (OWASP A08), same {@code ResourceAccessException -> 503 ProblemDetail}
 * normalization. No new SecurityConfig rule needed: the existing {@code
 * .requestMatchers("/api/admin/**").hasRole("ADMIN")} rule already matches {@code
 * /api/admin/mq/**} by prefix (verified against the current SecurityConfig, not assumed).
 */
@RestController
@RequestMapping("/api/admin/mq")
public class MqAdminFacadeController {

    private static final Logger log = LoggerFactory.getLogger(MqAdminFacadeController.class);
    private static final String MQ_ADMIN_PATH = "/api/admin/mq";

    private final RestClient mqAdminRestClient;

    public MqAdminFacadeController(RestClient mqAdminRestClient) {
        this.mqAdminRestClient = mqAdminRestClient;
    }

    @GetMapping("/queues")
    public ResponseEntity<?> queues(HttpServletRequest request) {
        return forward(HttpMethod.GET, MQ_ADMIN_PATH + "/queues", request, null);
    }

    @PostMapping("/dlq/{dlqName}/requeue")
    public ResponseEntity<?> requeue(
            @PathVariable String dlqName, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, MQ_ADMIN_PATH + "/dlq/" + dlqName + "/requeue", request, body);
    }

    // --- Slice A: imperative create/delete/purge facade routes (mq-admin-endpoints.md §7) ---

    @PostMapping("/queues")
    public ResponseEntity<?> createQueue(@RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, MQ_ADMIN_PATH + "/queues", request, body);
    }

    @DeleteMapping("/queues/{name}")
    public ResponseEntity<?> deleteQueue(@PathVariable String name, HttpServletRequest request) {
        return forward(HttpMethod.DELETE, MQ_ADMIN_PATH + "/queues/" + name, request, null);
    }

    @PostMapping("/queues/{name}/purge")
    public ResponseEntity<?> purgeQueue(@PathVariable String name, HttpServletRequest request) {
        return forward(HttpMethod.POST, MQ_ADMIN_PATH + "/queues/" + name + "/purge", request, null);
    }

    @PostMapping("/exchanges")
    public ResponseEntity<?> createExchange(@RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, MQ_ADMIN_PATH + "/exchanges", request, body);
    }

    @DeleteMapping("/exchanges/{name}")
    public ResponseEntity<?> deleteExchange(@PathVariable String name, HttpServletRequest request) {
        return forward(HttpMethod.DELETE, MQ_ADMIN_PATH + "/exchanges/" + name, request, null);
    }

    @PostMapping("/bindings")
    public ResponseEntity<?> createBinding(@RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, MQ_ADMIN_PATH + "/bindings", request, body);
    }

    @DeleteMapping("/bindings")
    public ResponseEntity<?> deleteBinding(@RequestBody byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.DELETE, MQ_ADMIN_PATH + "/bindings", request, body);
    }

    private ResponseEntity<?> forward(HttpMethod method, String path, HttpServletRequest request, byte[] body) {
        try {
            RestClient.RequestBodySpec spec = mqAdminRestClient.method(method)
                    .uri(path)
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
            log.warn("mq-admin service unreachable: method=[{}] path=[{}]", method, path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The mq-admin service is currently unavailable.");
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
