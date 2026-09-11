package cl.campuslab.bff.kafkaadmin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class KafkaAdminClientConfig {

    /**
     * Fixed, operator-configured target (KAFKA_ADMIN_SERVICE_URL) - never derived from
     * any part of the incoming request, so there is no SSRF vector here (OWASP A10).
     * kafka-admin has no ports: mapping in compose, so this is the only network path.
     */
    @Bean
    public RestClient kafkaAdminRestClient(@Value("${kafka-admin.service-url}") String kafkaAdminServiceUrl) {
        return RestClient.builder().baseUrl(kafkaAdminServiceUrl).build();
    }
}
