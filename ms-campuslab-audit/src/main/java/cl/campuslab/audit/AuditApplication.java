package cl.campuslab.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} backs {@code KafkaListenerStartupRetryTask}'s
 * retry-until-broker-reachable check (docs/designs/aws-deployment.md Part 9) - the only
 * scheduled task in this service. */
@SpringBootApplication
@EnableScheduling
public class AuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
