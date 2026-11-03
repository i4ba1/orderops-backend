package com.orderops.orders.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictIntegerInput() {
        return builder ->
                builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                        .postConfigurer(
                                mapper -> {
                                    mapper.coercionConfigFor(LogicalType.Integer)
                                            .setCoercion(
                                                    CoercionInputShape.String, CoercionAction.Fail);
                                    mapper.coercionConfigFor(LogicalType.Textual)
                                            .setCoercion(
                                                    CoercionInputShape.Integer, CoercionAction.Fail)
                                            .setCoercion(
                                                    CoercionInputShape.Float, CoercionAction.Fail)
                                            .setCoercion(
                                                    CoercionInputShape.Boolean,
                                                    CoercionAction.Fail);
                                });
    }
}
