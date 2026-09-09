package cl.campuslab.bff.bookings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class BookingsClientConfig {

    /**
     * Fixed, operator-configured target (BOOKINGS_SERVICE_URL) - never derived from any
     * part of the incoming request, so there is no SSRF vector here (OWASP A10). Bookings
     * has no ports: mapping in compose, so this is the only network path to it.
     */
    @Bean
    public RestClient bookingsRestClient(@Value("${bookings.service-url}") String bookingsServiceUrl) {
        return RestClient.builder().baseUrl(bookingsServiceUrl).build();
    }
}
