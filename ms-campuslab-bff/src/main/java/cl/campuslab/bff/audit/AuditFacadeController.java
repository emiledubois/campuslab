package cl.campuslab.bff.audit;

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
 * Facade: structurally identical to CatalogFacadeController/MqAdminFacadeController
 * (design doc §2/§6), GET-only - audit has no write endpoints (design doc §5's "solo
 * lectura" table entry). Role enforcement for this route happens twice: the new path
 * rule in SecurityConfig here (fails fast, saves a network hop for an obviously
 * wrong-role call) and audit's own, independent re-validation (the actual last line of
 * defense, design doc §7 A07).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditFacadeController {

    private static final Logger log = LoggerFactory.getLogger(AuditFacadeController.class);
    private static final String TIMELINE_PATH = "/api/audit/timeline";

    private final RestClient auditRestClient;

    public AuditFacadeController(RestClient auditRestClient) {
        this.auditRestClient = auditRestClient;
    }

    @GetMapping("/timeline")
    public ResponseEntity<?> timeline(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = query != null ? TIMELINE_PATH + "?" + query : TIMELINE_PATH;
        return forward(path, request);
    }

    private ResponseEntity<?> forward(String path, HttpServletRequest request) {
        try {
            return auditRestClient.method(HttpMethod.GET)
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
            log.warn("audit service unreachable: path=[{}]", path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The audit service is currently unavailable.");
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
