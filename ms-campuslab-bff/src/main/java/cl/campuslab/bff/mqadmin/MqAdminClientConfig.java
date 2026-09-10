package cl.campuslab.bff.mqadmin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MqAdminClientConfig {

    /**
     * Fixed, operator-configured target (MQ_ADMIN_SERVICE_URL) - never derived from any
     * part of the incoming request, so there is no SSRF vector here (OWASP A10). mq-admin
     * has no ports: mapping in compose, so this is the only network path to it.
     */
    @Bean
    public RestClient mqAdminRestClient(@Value("${mq-admin.service-url}") String mqAdminServiceUrl) {
        return RestClient.builder().baseUrl(mqAdminServiceUrl).build();
    }
}
