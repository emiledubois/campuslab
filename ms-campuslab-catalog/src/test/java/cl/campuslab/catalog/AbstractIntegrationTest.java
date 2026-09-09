package cl.campuslab.catalog;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres via Testcontainers, not H2 - this service's Flyway migration uses
 * Postgres-specific TIMESTAMPTZ/CHECK-constraint syntax, and the optimistic-locking
 * concurrency test (AC15) needs a real transactional engine actually serializing
 * concurrent row updates, not an approximation.
 *
 * Deliberately NOT annotated with @Testcontainers/@Container: that JUnit5 extension
 * ties container start/stop to each individual test class's own beforeAll/afterAll,
 * so a container shared (by static-field reference) across several test classes would
 * get stopped by the first class's afterAll while later classes still need it. This is
 * Testcontainers' documented "singleton container" pattern instead - started once in a
 * static initializer, never explicitly stopped (the Ryuk reaper cleans it up at JVM exit).
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
