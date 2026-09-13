package cl.campuslab.kafkaadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} backs {@code TopologyHealthIndicator}'s retry-until-verified
 * check (demo-readiness.md §2/Decision 1) - the only scheduled task in this service. */
@SpringBootApplication
@EnableScheduling
public class KafkaAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaAdminApplication.class, args);
    }
}
