package cl.campuslab.bff.catalog;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Facade: the browser only ever talks to the BFF at this same path - it never learns
 * that ms-campuslab-catalog exists as a separate network address. The request/response
 * bodies are forwarded unparsed (raw bytes) both ways (OWASP A08) - catalog is the single
 * source of truth for shape/validation of this data, the BFF never deserializes it. Role
 * enforcement for these three routes happens twice: the path rules in SecurityConfig here
 * (fails fast, saves a network hop for an obviously wrong-role call) and catalog's own,
 * independent re-validation (the actual last line of defense, see design doc §2).
 */
@RestController
@RequestMapping("/api/catalog/resources")
public class CatalogFacadeController {

    private static final Logger log = LoggerFactory.getLogger(CatalogFacadeController.class);
    private static final String RESOURCES_PATH = "/api/catalog/resources";

    private final RestClient catalogRestClient;

    public CatalogFacadeController(RestClient catalogRestClient) {
        this.catalogRestClient = catalogRestClient;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return forward(HttpMethod.GET, RESOURCES_PATH, request, null);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, RESOURCES_PATH, request, body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable String id, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.PUT, RESOURCES_PATH + "/" + id, request, body);
    }

    private ResponseEntity<?> forward(HttpMethod method, String path, HttpServletRequest request, byte[] body) {
        try {
            RestClient.RequestBodySpec spec = catalogRestClient.method(method)
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
                if (clientResponse.getHeaders().getLocation() != null) {
                    responseHeaders.setLocation(clientResponse.getHeaders().getLocation());
                }
                return ResponseEntity.status(clientResponse.getStatusCode())
                        .headers(responseHeaders)
                        .body(responseBody);
            });
        } catch (ResourceAccessException ex) {
            log.warn("Catalog service unreachable: method=[{}] path=[{}]", method, path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The catalog service is currently unavailable.");
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
