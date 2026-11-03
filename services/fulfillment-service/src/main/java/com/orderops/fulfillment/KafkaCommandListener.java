package com.orderops.fulfillment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
class KafkaCommandListener {
    private final CommandCodec codec;
    private final FulfillmentStore store;

    KafkaCommandListener(CommandCodec codec, FulfillmentStore store) {
        this.codec = codec;
        this.store = store;
    }

    @KafkaListener(topics = KafkaConfiguration.ORDERS)
    void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Command command = codec.decode(record.value());
        if (!command.orderId().toString().equals(record.key())) throw new InvalidCommandException();
        try (var orderContext = MDC.putCloseable("orderId", command.orderId().toString());
                var eventContext = MDC.putCloseable("eventId", command.eventId().toString())) {
            store.consume(command);
            // TransactionTemplate completed its commit before the source offset is acknowledged.
            acknowledgment.acknowledge();
            LoggerFactory.getLogger(KafkaCommandListener.class)
                    .atInfo()
                    .addKeyValue("generation", command.generation())
                    .log("Fulfillment command consumed");
        }
    }
}
