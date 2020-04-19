package de.gessnerfl.rabbitmq.queue.management.controller;

import de.gessnerfl.rabbitmq.queue.management.service.rabbitmq.RabbitMqFacade;
import de.gessnerfl.rabbitmq.queue.management.util.RabbitMqTestEnvironment;
import de.gessnerfl.rabbitmq.queue.management.util.RabbitMqTestEnvironmentBuilder;
import de.gessnerfl.rabbitmq.queue.management.util.RabbitMqTestEnvironmentBuilderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class RequeueAllMessagesControllerIntegrationTest extends AbstractControllerIntegrationTest {
    private static final String VHOST_NAME = "/";
    private static final String EXCHANGE_NAME = "test.ex";
    private static final String QUEUE_1_NAME = "test1.controller.in";
    private static final String QUEUE_1_DLX_NAME = "test1-dlx.controller.in";
    private static final int MESSAGE_TTL_OF_QUEUE1 = 100;
    private static final String QUEUE_2_NAME = "test2.controller.in";

    @Autowired
    private RabbitMqTestEnvironmentBuilderFactory testEnvironmentBuilderFactor;
    private RabbitMqTestEnvironment testEnvironment;

    @Autowired
    private RabbitMqFacade facade;

    @Before
    public void init() throws Exception {
        RabbitMqTestEnvironmentBuilder builder = testEnvironmentBuilderFactor.create();
        testEnvironment = builder.withExchange(EXCHANGE_NAME)
                .withQueue(QUEUE_1_DLX_NAME)
                    .exchange(EXCHANGE_NAME)
                    .build()
                .withQueue(QUEUE_1_NAME)
                    .exchange(EXCHANGE_NAME)
                    .deadLetterExchange(EXCHANGE_NAME)
                    .deadLetterRoutingKey(QUEUE_1_DLX_NAME)
                    .ttl(MESSAGE_TTL_OF_QUEUE1)
                    .build()
                .withQueue(QUEUE_2_NAME)
                    .exchange(EXCHANGE_NAME)
                    .build()
                .build();
        testEnvironment.setup();
    }

    @After
    public void cleanup() {
        testEnvironment.cleanup();
    }

    @Test
    public void shouldReturnPageOnGet() throws Exception {
        testEnvironment.publishMessages(EXCHANGE_NAME, QUEUE_1_NAME, 2);

        //wait until message is dead lettered
        TimeUnit.MILLISECONDS.sleep(MESSAGE_TTL_OF_QUEUE1 + 50);

        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_NAME, 10), empty());
        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_DLX_NAME, 10), hasSize(2));
        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_2_NAME, 10), empty());

        mockMvc.perform(get("/messages/requeue-all")
                    .param(Parameters.VHOST, VHOST_NAME)
                    .param(Parameters.QUEUE, QUEUE_1_DLX_NAME))
                .andExpect(status().isOk())
                .andExpect(view().name(RequeueAllMessagesController.VIEW_NAME))
                .andExpect(model().attribute(Parameters.VHOST, VHOST_NAME))
                .andExpect(model().attribute(Parameters.QUEUE, QUEUE_1_DLX_NAME));
    }

    @Test
    public void shouldReturnToMessagePageWhenNoMessagesAreAvailableOnQueue() throws Exception {
        mockMvc.perform(get("/messages/requeue-all")
                    .param(Parameters.VHOST, VHOST_NAME)
                    .param(Parameters.QUEUE, QUEUE_1_NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages?vhost=%2F&queue=test1.controller.in"));
    }

    @Test
    public void shouldReturnToMessagePageWhenFirstMessageInQueueDoesNotSupportRequeuing() throws Exception {
        testEnvironment.publishMessages(EXCHANGE_NAME, QUEUE_2_NAME, 2);

        mockMvc.perform(get("/messages/requeue-all")
                    .param(Parameters.VHOST, VHOST_NAME)
                    .param(Parameters.QUEUE, QUEUE_2_NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages?vhost=%2F&queue=test2.controller.in"));
    }

    @Test
    public void shouldRequeueAllMessagesFromSourceToTargetQueueOnPostWhenTargetExchangeAndRoutingKeyAreProvidedOnPost() throws Exception {
        testEnvironment.publishMessages(EXCHANGE_NAME, QUEUE_1_NAME, 2);

        //wait until message is dead lettered
        TimeUnit.MILLISECONDS.sleep(MESSAGE_TTL_OF_QUEUE1 + 50);

        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_NAME, 10), empty());
        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_DLX_NAME, 10), hasSize(2));

        mockMvc.perform(post("/messages/requeue-all")
                    .param(Parameters.VHOST, VHOST_NAME)
                    .param(Parameters.QUEUE, QUEUE_1_DLX_NAME)
                    .param(Parameters.TARGET_EXCHANGE, EXCHANGE_NAME)
                    .param(Parameters.TARGET_ROUTING_KEY, QUEUE_1_NAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages?vhost=%2F&queue=test1-dlx.controller.in"));

        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_NAME, 10), hasSize(2));
        assertThat(facade.getMessagesOfQueue(VHOST_NAME, QUEUE_1_DLX_NAME, 10), empty());
    }
}
