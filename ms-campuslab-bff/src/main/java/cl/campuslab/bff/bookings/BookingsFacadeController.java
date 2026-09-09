package cl.campuslab.bff.bookings;

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
 * that ms-campuslab-bookings exists as a separate network address. The request/response
 * bodies (and, for GET /api/bookings, the query string) are forwarded unparsed (raw
 * bytes) both ways (OWASP A08) - bookings is the single source of truth for shape/
 * validation/ownership of this data, the BFF never deserializes it. Role enforcement
 * for these four routes happens twice: the path rules in SecurityConfig here (fails
 * fast, saves a network hop for an obviously wrong-role call) and bookings' own,
 * independent re-validation plus ownership check (the actual last line of defense,
 * see design doc §2) - the BFF has no ownership logic of its own, since it never sees
 * a validated principal's `sub` as anything but an opaque bearer token to forward.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingsFacadeController {

    private static final Logger log = LoggerFactory.getLogger(BookingsFacadeController.class);
    private static final String BOOKINGS_PATH = "/api/bookings";

    private final RestClient bookingsRestClient;

    public BookingsFacadeController(RestClient bookingsRestClient) {
        this.bookingsRestClient = bookingsRestClient;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.POST, BOOKINGS_PATH, request, body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, HttpServletRequest request) {
        return forward(HttpMethod.GET, BOOKINGS_PATH + "/" + id, request, null);
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return forward(HttpMethod.GET, BOOKINGS_PATH + queryStringOf(request), request, null);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable String id, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return forward(HttpMethod.PUT, BOOKINGS_PATH + "/" + id + "/status", request, body);
    }

    private ResponseEntity<?> forward(HttpMethod method, String path, HttpServletRequest request, byte[] body) {
        try {
            RestClient.RequestBodySpec spec = bookingsRestClient.method(method)
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
            log.warn("Bookings service unreachable: method=[{}] path=[{}]", method, path);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.SERVICE_UNAVAILABLE, "The bookings service is currently unavailable.");
            problem.setTitle("Service Unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }
    }

    private static String queryStringOf(HttpServletRequest request) {
        String queryString = request.getQueryString();
        return queryString != null ? "?" + queryString : "";
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
