package cl.campuslab.mqadmin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.mqadmin.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises mq-admin's topology/inspection/requeue surface against a real, running
 * RabbitMQ broker (Testcontainers) - AC1 (topology exists, restart is proven live per
 * the task's own instructions against infra/mq/compose.yml, not repeated here), AC2
 * (queue-depth accuracy), AC3 (consumer-count accuracy), AC5/AC6/AC7 (requeue).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MqAdminApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @MockBean
    private JwtDecoder jwtDecoder;

    private SimpleMessageListenerContainer consumerContainer;

    @BeforeEach
    void stubAdminToken() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        rabbitAdmin.purgeQueue("q.cmd.email");
        rabbitAdmin.purgeQueue("q.cmd.prep");
        rabbitAdmin.purgeQueue("q.cmd.email.dlq");
        rabbitAdmin.purgeQueue("q.cmd.prep.dlq");
    }

    @AfterEach
    void stopConsumer() {
        if (consumerContainer != null) {
            consumerContainer.stop();
        }
    }

    @Test
    void getQueues_returnsAllSixDeclaredQueuesWithExpectedShape() throws Exception {
        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(6)))
                .andExpect(jsonPath("$[0].name").value("q.cmd.email"))
                .andExpect(jsonPath("$[0].isDlq").value(false))
                .andExpect(jsonPath("$[0].dlqRatePerMinute").doesNotExist())
                .andExpect(jsonPath("$[1].name").value("q.cmd.email.dlq"))
                .andExpect(jsonPath("$[1].isDlq").value(true))
                .andExpect(jsonPath("$[1].dlqRatePerMinute").value(0.0));
    }

    @Test
    void getQueues_reflectsRealCurrentDepthOfAWorkQueueWithNoConsumer() throws Exception {
        rabbitTemplate.send("", "q.cmd.prep", new Message("hello".getBytes(), new MessageProperties()));
        rabbitTemplate.send("", "q.cmd.prep", new Message("hello2".getBytes(), new MessageProperties()));

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].name").value("q.cmd.prep"))
                .andExpect(jsonPath("$[2].messageCount").value(2))
                .andExpect(jsonPath("$[2].consumerCount").value(0));
    }

    @Test
    void getQueues_reportsRealConsumerCountAndVoucherStaysZero() throws Exception {
        consumerContainer = new SimpleMessageListenerContainer(rabbitTemplate.getConnectionFactory());
        consumerContainer.setQueueNames("q.cmd.email");
        consumerContainer.setMessageListener(message -> { });
        consumerContainer.start();
        awaitConsumerRegistered();

        mockMvc.perform(get("/api/admin/mq/queues").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("q.cmd.email"))
                .andExpect(jsonPath("$[0].consumerCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[4].name").value("q.cmd.voucher"))
                .andExpect(jsonPath("$[4].consumerCount").value(0));
    }

    @Test
    void requeue_withExactCount_movesExactlyThatManyAndReportsRemaining() throws Exception {
        seedDlq("q.cmd.email.dlq", 5);

        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dlqName").value("q.cmd.email.dlq"))
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.actualRequeuedCount").value(2))
                .andExpect(jsonPath("$.remainingInDlq").value(3));
    }

    @Test
    void requeue_withAllTrue_movesEveryMessageAndDrainsTheDlq() throws Exception {
        seedDlq("q.cmd.prep.dlq", 3);

        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.prep.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualRequeuedCount").value(3))
                .andExpect(jsonPath("$.remainingInDlq").value(0));
    }

    @Test
    void requeue_preservesOriginalEventIdInTheRequeuedMessage() throws Exception {
        String eventId = "3fa1c9e2-11aa-4b0a-9c2f-aaaaaaaaaaaa";
        rabbitTemplate.send("", "q.cmd.email.dlq",
                new Message(("{\"eventId\":\"" + eventId + "\"}").getBytes(), new MessageProperties()));

        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualRequeuedCount").value(1));

        Message onWorkQueue = rabbitTemplate.receive("q.cmd.email", 5000);
        assertThat(onWorkQueue).isNotNull();
        assertThat(new String(onWorkQueue.getBody())).contains(eventId);
    }

    @Test
    void requeue_withNeitherCountNorAll_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requeue_withZeroCount_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requeue_withBothCountAndAll_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/q.cmd.email.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2,\"all\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requeue_withUnrecognizedDlqName_returns400NeverReachingRabbit() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/not.a.real.dlq/requeue")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isBadRequest());
    }

    private void seedDlq(String dlqName, int count) {
        for (int i = 0; i < count; i++) {
            rabbitTemplate.send("", dlqName, new Message(("msg-" + i).getBytes(), new MessageProperties()));
        }
    }

    private static void awaitConsumerRegistered() throws InterruptedException {
        Thread.sleep(500);
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", subject + "-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
