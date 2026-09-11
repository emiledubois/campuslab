package cl.campuslab.bff.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ReportClientConfig {

    /**
     * Fixed, operator-configured target (REPORT_SERVICE_URL) - never derived from any
     * part of the incoming request, so there is no SSRF vector here (OWASP A10). report
     * has no ports: mapping in compose, so this is the only network path to it.
     */
    @Bean
    public RestClient reportRestClient(@Value("${report.service-url}") String reportServiceUrl) {
        return RestClient.builder().baseUrl(reportServiceUrl).build();
    }
}
