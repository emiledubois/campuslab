package cl.campuslab.report;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres via Testcontainers, not H2 - this service's Flyway migration
 * (V2__report_event.sql) uses a real UNIQUE constraint that the idempotency
 * acceptance criteria (AC6) need to actually enforce, not approximate.
 *
 * Deliberately NOT annotated with @Testcontainers/@Container - same singleton-
 * container rationale as bookings'/mq-admin's own AbstractIntegrationTest.
 */
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
