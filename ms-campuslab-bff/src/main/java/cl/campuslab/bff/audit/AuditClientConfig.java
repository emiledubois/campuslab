package cl.campuslab.bff.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AuditClientConfig {

    /**
     * Fixed, operator-configured target (AUDIT_SERVICE_URL) - never derived from any
     * part of the incoming request, so there is no SSRF vector here (OWASP A10). audit
     * has no ports: mapping in compose, so this is the only network path to it.
     */
    @Bean
    public RestClient auditRestClient(@Value("${audit.service-url}") String auditServiceUrl) {
        return RestClient.builder().baseUrl(auditServiceUrl).build();
    }
}
