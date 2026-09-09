package cl.campuslab.bff.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfig {

    /**
     * Fixed, operator-configured target (CATALOG_SERVICE_URL) - never derived from any
     * part of the incoming request, so there is no SSRF vector here (OWASP A10). Catalog
     * has no ports: mapping in compose, so this is the only network path to it.
     */
    @Bean
    public RestClient catalogRestClient(@Value("${catalog.service-url}") String catalogServiceUrl) {
        return RestClient.builder().baseUrl(catalogServiceUrl).build();
    }
}
