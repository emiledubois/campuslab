package cl.campuslab.mqadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} backs the DLQ-rate sampler (design doc §7 A06/§10 open
 * question 1) - the only scheduled task in this service. */
@SpringBootApplication
@EnableScheduling
public class MqAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqAdminApplication.class, args);
    }
}
