package cl.campuslab.bookings.catalog;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfig {

    /**
     * Fixed, operator-configured target (CATALOG_SERVICE_URL - the same variable name the
     * BFF already uses for the same downstream, design doc §7 A05/A10) - never derived from
     * any part of the incoming request, so there is no SSRF vector here. Explicit connect/
     * read timeouts (unlike the BFF's own still-untimed client, a separate open backlog
     * item) make "catalog unreachable/timeout" a bounded, deterministic saga failure mode
     * (design doc §3/§9 AC5) instead of an indefinite hang.
     */
    @Bean
    public RestClient catalogRestClient(
            @Value("${catalog.service-url}") String catalogServiceUrl,
            @Value("${catalog.client.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${catalog.client.read-timeout-ms}") long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder().baseUrl(catalogServiceUrl).requestFactory(requestFactory).build();
    }
}
