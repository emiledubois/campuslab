package cl.campuslab.bff.report;

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
 * Facade: structurally identical to AuditClientConfig/AuditFacadeController (design
 * doc §2/§6), GET-only - report has no write endpoints (design doc §5's "solo
 * lectura" table entry). Role enforcement for this route happens twice: the new path
 * rule in SecurityConfig here (ADMIN only, fails fast, saves a network hop for an
 * obviously wrong-role call) and report's own, independent re-validation (the actual
 * last line of defense, design doc §7 A07).
 */
@RestController
@RequestMapping("/api/report")
public class ReportFacadeController {

    private static final Logger log = LoggerFactory.getLogger(ReportFacadeController.class);
    private static final String KPIS_PATH = "/api/report/kpis";
    private static final String TOP_RESOURCES_PATH = "/api/report/top-resources";

    private final RestClient reportRestClient;

    public ReportFacadeController(RestClient reportRestClient) {
        this.reportRestClient = reportRestClient;
    }

    @GetMapping("/kpis")
    public ResponseEntity<?> kpis(HttpServletRequest request) {
        return forward(withQuery(KPIS_PATH, request), request);
    }

    @GetMapping("/top-resources")
    public ResponseEntity<?> topResources(HttpServletRequest request) {
        return forward(withQuery(TOP_RESOURCES_PATH, request), request);
    }

    private static String withQuery(String path, HttpServletRequest request) {
        String query = request.getQueryString();
        return query != null ? path + "?" + query : path;
    }

    private ResponseEntity<?> forward(String path, HttpServletRequest request) {
        try {
            return reportRestClient.method(HttpMethod.GET)
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
            log.warn("report service unreachable: path=[{}]", path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The report service is currently unavailable.");
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
