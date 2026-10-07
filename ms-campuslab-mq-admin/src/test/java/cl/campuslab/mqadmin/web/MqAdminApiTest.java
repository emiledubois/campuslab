package cl.campuslab.mqadmin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    // --- Slice A: imperative create/delete/purge endpoints (design doc mq-admin-endpoints.md) ---

    @Test
    void createQueue_thenDeleteIt_bothSucceedAgainstTheRealBroker() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q.demo.ops\",\"durable\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("q.demo.ops"))
                .andExpect(jsonPath("$.durable").value(true))
                .andExpect(jsonPath("$.exclusive").value(false))
                .andExpect(jsonPath("$.autoDelete").value(false))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.consumerCount").value(0));
        assertThat(rabbitAdmin.getQueueProperties("q.demo.ops")).isNotNull();

        mockMvc.perform(delete("/api/admin/mq/queues/q.demo.ops").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
        assertThat(rabbitAdmin.getQueueProperties("q.demo.ops")).isNull();

        mockMvc.perform(delete("/api/admin/mq/queues/q.demo.ops").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createQueue_withEmptyName_returns400WithClearMessage() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"durable\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("must not be blank")));
    }

    @Test
    void createQueue_withWhitespaceOnlyName_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createQueue_withMalformedJsonBody_returns400NotAStackTrace() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json-at-all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void createExchange_withInvalidType_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/mq/exchanges")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo.x\",\"type\":\"not-a-type\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createExchangeAndBinding_thenListedByRabbitmqctlEquivalent() throws Exception {
        mockMvc.perform(post("/api/admin/mq/exchanges")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo.ops.exchange\",\"type\":\"direct\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("demo.ops.exchange"))
                .andExpect(jsonPath("$.type").value("direct"));

        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q.demo.bind\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/mq/bindings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"demo.ops.exchange\",\"destination\":\"q.demo.bind\",\"destinationType\":\"QUEUE\",\"routingKey\":\"demo.key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("demo.ops.exchange"))
                .andExpect(jsonPath("$.destination").value("q.demo.bind"))
                .andExpect(jsonPath("$.routingKey").value("demo.key"));

        rabbitTemplate.send("demo.ops.exchange", "demo.key", new Message("hi".getBytes(), new MessageProperties()));
        Message onQueue = rabbitTemplate.receive("q.demo.bind", 5000);
        assertThat(onQueue).isNotNull();

        mockMvc.perform(delete("/api/admin/mq/bindings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"demo.ops.exchange\",\"destination\":\"q.demo.bind\",\"destinationType\":\"QUEUE\",\"routingKey\":\"demo.key\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/admin/mq/queues/q.demo.bind").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/mq/exchanges/demo.ops.exchange").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteBinding_thatNeverExisted_stillReturns204() throws Exception {
        mockMvc.perform(post("/api/admin/mq/exchanges")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo.never.bound.exchange\",\"type\":\"direct\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/admin/mq/bindings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"demo.never.bound.exchange\",\"destination\":\"q.demo.never.bound\",\"destinationType\":\"QUEUE\",\"routingKey\":\"x\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/admin/mq/exchanges/demo.never.bound.exchange")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void createBinding_withNonexistentQueueDestination_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/mq/exchanges")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo.ops.exchange2\",\"type\":\"direct\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/mq/bindings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"demo.ops.exchange2\",\"destination\":\"q.does.not.exist\",\"destinationType\":\"QUEUE\",\"routingKey\":\"x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/mq/exchanges/demo.ops.exchange2")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void createBinding_withManagedSourceExchange_returns409() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q.demo.ops2\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/mq/bindings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"cmd.direct\",\"destination\":\"q.demo.ops2\",\"destinationType\":\"QUEUE\",\"routingKey\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cmd.direct")));

        mockMvc.perform(delete("/api/admin/mq/queues/q.demo.ops2").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteQueue_withManagedName_returns409AndLeavesItIntact() throws Exception {
        mockMvc.perform(delete("/api/admin/mq/queues/q.cmd.email").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("q.cmd.email")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RabbitTopologyConfig")));

        assertThat(rabbitAdmin.getQueueProperties("q.cmd.email")).isNotNull();
    }

    @Test
    void deleteExchange_withManagedName_returns409AndLeavesItIntact() throws Exception {
        mockMvc.perform(delete("/api/admin/mq/exchanges/cmd.direct").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());

        // still bound/declared: a direct publish through it still reaches a work queue
        rabbitAdmin.purgeQueue("q.cmd.email");
        rabbitTemplate.send("cmd.direct", "email.send", new Message("still-wired".getBytes(), new MessageProperties()));
        Message onWorkQueue = rabbitTemplate.receive("q.cmd.email", 5000);
        assertThat(onWorkQueue).isNotNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "q.cmd.email", "q.cmd.email.dlq", "q.cmd.prep", "q.cmd.prep.dlq", "q.cmd.voucher", "q.cmd.voucher.dlq"})
    void deleteQueue_withEveryManagedQueueName_returns409(String managedQueueName) throws Exception {
        mockMvc.perform(delete("/api/admin/mq/queues/" + managedQueueName).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cmd.direct", "cmd.topic", "cmd.dead.dlx"})
    void deleteExchange_withEveryManagedExchangeName_returns409(String managedExchangeName) throws Exception {
        mockMvc.perform(delete("/api/admin/mq/exchanges/" + managedExchangeName).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());
    }

    @Test
    void purgeQueue_withManagedName_returns409AndDoesNotPurge() throws Exception {
        rabbitTemplate.send("", "q.cmd.email", new Message("keep-me".getBytes(), new MessageProperties()));

        mockMvc.perform(post("/api/admin/mq/queues/q.cmd.email/purge").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());

        assertThat(rabbitAdmin.getQueueProperties("q.cmd.email")
                .get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).isEqualTo(1);
        rabbitAdmin.purgeQueue("q.cmd.email");
    }

    @Test
    void purgeQueue_withNonManagedExistingQueue_purgesAndReportsCount() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q.demo.purge\"}"))
                .andExpect(status().isCreated());
        rabbitTemplate.send("", "q.demo.purge", new Message("1".getBytes(), new MessageProperties()));
        rabbitTemplate.send("", "q.demo.purge", new Message("2".getBytes(), new MessageProperties()));

        mockMvc.perform(post("/api/admin/mq/queues/q.demo.purge/purge").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueName").value("q.demo.purge"))
                .andExpect(jsonPath("$.purgedMessageCount").value(2));

        mockMvc.perform(delete("/api/admin/mq/queues/q.demo.purge").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void purgeQueue_withNonexistentQueue_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/mq/queues/q.does.not.exist/purge").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
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
