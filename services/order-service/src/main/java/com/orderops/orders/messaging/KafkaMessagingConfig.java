package com.orderops.orders.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "orderops.messaging.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaMessagingConfig {
    @Bean
    NewTopic orderCommands(
            @Value("${orderops.messaging.orders-topic:orderops.orders.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic fulfillmentResults(
            @Value("${orderops.messaging.fulfillment-topic:orderops.fulfillment.v1}")
                    String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic fulfillmentDeadLetters(
            @Value("${orderops.messaging.fulfillment-topic:orderops.fulfillment.v1}")
                    String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler resultErrorHandler(KafkaTemplate<String, String> kafka) {
        var handler =
                new DefaultErrorHandler(
                        (record, error) -> {
                            try {
                                // Recovery returns only after the broker durably acknowledges the
                                // poison record.
                                var deadLetter =
                                        new ProducerRecord<String, String>(
                                                record.topic() + ".DLT",
                                                record.partition(),
                                                (String) record.key(),
                                                (String) record.value());
                                deadLetter
                                        .headers()
                                        .add(
                                                "orderops-error-reason",
                                                "Invalid fulfillment event; schema, payload, key or order reference failed validation"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                                deadLetter
                                        .headers()
                                        .add(
                                                "orderops-source-offset",
                                                Long.toString(record.offset())
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                                kafka.send(deadLetter).get(2, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "Dead letter publication interrupted");
                            } catch (Exception unavailable) {
                                throw new IllegalStateException(
                                        "Dead letter publication was not acknowledged");
                            }
                        },
                        new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler resultErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setCommonErrorHandler(resultErrorHandler);
        return factory;
    }
}
