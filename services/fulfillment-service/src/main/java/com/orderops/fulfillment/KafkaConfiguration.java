package com.orderops.fulfillment;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
@EnableKafka
class KafkaConfiguration {
    static final String ORDERS = "orderops.orders.v1";
    static final String RESULTS = "orderops.fulfillment.v1";

    @Bean
    NewTopic fulfillmentCommandsTopic() {
        return new NewTopic(ORDERS, 1, (short) 1);
    }

    @Bean
    NewTopic fulfillmentResultsTopic() {
        return new NewTopic(RESULTS, 1, (short) 1);
    }

    @Bean
    NewTopic fulfillmentDeadLetterTopic() {
        return new NewTopic(ORDERS + ".DLT", 1, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> template) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, failure) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(12));
        var errors =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Only invalid data can be discarded to DLT. DB/broker outages retry indefinitely.
        errors.addNotRetryableExceptions(InvalidCommandException.class);
        errors.setCommitRecovered(true);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
