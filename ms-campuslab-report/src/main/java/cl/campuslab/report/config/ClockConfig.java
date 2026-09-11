package cl.campuslab.report.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every KPI's notion of "now" (range boundaries, {@code generatedAt}, {@code
 * equiposOcupados}' {@code asOf}) runs through this single injectable {@link Clock}
 * rather than scattered {@code Instant.now()} calls - the standard JDK seam for
 * testable time, needed here so the deterministic KPI scenario (design doc §9 AC6) can
 * fix "now" precisely instead of racing the real wall clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
