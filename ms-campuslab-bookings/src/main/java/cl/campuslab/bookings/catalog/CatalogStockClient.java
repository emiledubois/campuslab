package cl.campuslab.bookings.catalog;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The saga orchestrator's typed HTTP client to catalog's two new endpoints (design doc
 * §2/§7 A08) - unlike the BFF's raw-byte Facade forwarding, bookings makes real branching
 * decisions on the parsed status code and body, so it must actually deserialize the
 * response, never forward it onward. The caller's bearer token is forwarded unchanged
 * (no token minting, no service credential) and is never logged here or anywhere else
 * (design doc §7 A09), even though this class is the one place in bookings that literally
 * holds its value in a variable.
 */
@Component
public class CatalogStockClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogStockClient.class);

    private final RestClient catalogRestClient;

    public CatalogStockClient(RestClient catalogRestClient) {
        this.catalogRestClient = catalogRestClient;
    }

    public CatalogDecrementOutcome decrement(UUID resourceId, UUID bookingId, String bearerToken) {
        try {
            catalogRestClient.post()
                    .uri("/api/catalog/resources/{id}/decrement", resourceId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .body(new StockAdjustmentRequestBody(bookingId))
                    .retrieve()
                    .body(CatalogStockResponse.class);
            return CatalogDecrementOutcome.SUCCESS;
        } catch (HttpClientErrorException.Conflict ex) {
            return CatalogDecrementOutcome.INSUFFICIENT_STOCK;
        } catch (HttpClientErrorException.NotFound ex) {
            return CatalogDecrementOutcome.NOT_FOUND;
        } catch (ResourceAccessException ex) {
            log.warn("Catalog decrement unreachable resourceId=[{}] bookingId=[{}]", resourceId, bookingId);
            return CatalogDecrementOutcome.UNREACHABLE;
        } catch (RestClientException ex) {
            log.error("Catalog decrement returned an unexpected error resourceId=[{}] bookingId=[{}]", resourceId, bookingId);
            return CatalogDecrementOutcome.UNEXPECTED_ERROR;
        }
    }

    /**
     * The saga's compensating call (design doc §3/§4) - a boolean is all the caller needs
     * (success, or "log ERROR for manual reconciliation and respond 409 anyway" per §3 step 2).
     */
    public boolean increment(UUID resourceId, UUID bookingId, String bearerToken) {
        try {
            catalogRestClient.post()
                    .uri("/api/catalog/resources/{id}/increment", resourceId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .body(new StockAdjustmentRequestBody(bookingId))
                    .retrieve()
                    .body(CatalogStockResponse.class);
            return true;
        } catch (RestClientException ex) {
            return false;
        }
    }
}
