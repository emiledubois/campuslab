package cl.campuslab.kafkaadmin.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cl.campuslab.kafkaadmin.kafka.ConsumerGroupInspectionService;
import cl.campuslab.kafkaadmin.kafka.DltInspectionService;
import cl.campuslab.kafkaadmin.kafka.KafkaReachabilityChecker;
import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * docs/designs/aws-deployment.md Part 7 / AC19 - the regression test that closes the
 * coverage gap AC17's runtime smoke test exposed: neither {@code TopologyHealthIndicatorTest}
 * (mocks {@link cl.campuslab.kafkaadmin.kafka.TopologyInspectionService} directly, never
 * touches the real {@code Admin} bean) nor {@code TopologyInspectionServiceApiTest} (extends
 * {@code AbstractIntegrationTest}, whose Testcontainers broker is already reachable before
 * the context ever refreshes) exercises "the broker is completely unresolvable at the moment
 * {@code ApplicationContext} refresh happens" - the exact condition
 * {@code KafkaAdminClientConfig}'s eager {@code Admin.create(...)} used to crash on. Points
 * {@code kafka.bootstrap-servers} at a hostname that does not resolve in DNS at all (the
 * strictly harder case than a mere connection-refused port, matching AC17's real scenario)
 * and asserts context refresh completes and {@link TopologyHealthIndicator} reports the
 * correct degraded-not-crashed state, never that it eventually recovers (no broker is ever
 * introduced in this test - that transition is already covered by
 * {@code TopologyHealthIndicatorTest#checkTopology_whenAllTopicsVerified_flipsHealthUpAndStaysUp}
 * and by AC17's own runtime smoke test). Deliberately does not extend
 * {@code AbstractIntegrationTest}: this test's whole premise is that no real broker is ever
 * reachable, which a Testcontainers-backed real Kafka would contradict.
 *
 * <p>Revision 4: additionally fetches all four {@code Admin}-consuming beans
 * ({@link TopologyInspectionService}, {@link KafkaReachabilityChecker},
 * {@link ConsumerGroupInspectionService}, {@link DltInspectionService}) from the context and
 * asserts each is constructed without throwing. Revision 3's single-annotation fix
 * ({@code @Lazy} on the producing {@code @Bean} method alone) passed the two assertions above
 * yet still crash-looped, because Spring only substitutes a lazy-resolving proxy at a
 * <em>consuming</em> injection point that is itself {@code @Lazy} - it does not follow from the
 * producing bean's own laziness. The developer's own failing trace went through
 * {@code ConsumerGroupInspectionService} specifically; these four {@code getBean(...)} calls are
 * what actually prove every injection point, not just "the context" in the abstract, is genuinely
 * lazy.
 *
 * <p>{@code webEnvironment = RANDOM_PORT}, not {@code NONE}: kafka-admin's own
 * {@code SecurityConfig#securityFilterChain} takes an {@code HttpSecurity} parameter, which
 * Spring Security's auto-configuration only registers for a servlet web application context
 * ({@code @ConditionalOnWebApplication(type = SERVLET)}) - under {@code NONE} that bean is
 * absent and context refresh fails for a reason unrelated to this test's actual subject
 * ({@code Admin} bean/consumer laziness). {@code RANDOM_PORT} is the same choice
 * {@code EntraJwtValidationTest}/{@code SecurityIntegrationTest} already make in this service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaAdminClientLazyInitTest {

    @DynamicPropertySource
    static void unresolvableBroker(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "this-host-does-not-exist.invalid:9092");
    }

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private TopologyHealthIndicator topologyHealthIndicator;

    // Avoids an unrelated real network call to a fake issuer URL during context refresh -
    // mirrors TopologyInspectionServiceApiTest's own rationale for the same mock; this test
    // is about Admin bean construction timing, not JWT validation (proven independently by
    // EntraJwtValidationTest/SecurityIntegrationTest).
    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void contextRefresh_withUnresolvableBootstrapServers_startsAndHealthStaysDown() {
        assertThat(applicationContext.isActive()).isTrue();

        assertThat(topologyHealthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        // TopologyHealthIndicator's @Scheduled(fixedDelay = 5000) retry must keep re-attempting
        // Admin.create() through the lazy proxy without ever throwing out of the scheduled
        // method or crashing the context - confirmed by staying DOWN, not by an exception here.
        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(topologyHealthIndicator.health().getStatus())
                        .isEqualTo(Status.DOWN));
    }

    @Test
    void contextRefresh_withUnresolvableBootstrapServers_allFourAdminConsumersConstructWithoutThrowing() {
        assertThat(applicationContext.getBean(TopologyInspectionService.class)).isNotNull();
        assertThat(applicationContext.getBean(KafkaReachabilityChecker.class)).isNotNull();
        assertThat(applicationContext.getBean(ConsumerGroupInspectionService.class)).isNotNull();
        assertThat(applicationContext.getBean(DltInspectionService.class)).isNotNull();
    }
}
