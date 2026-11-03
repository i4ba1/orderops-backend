package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

class CorrelationFilterTest {
    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void acceptsSafeBoundedIdentifierAndCleansRequestContext() throws Exception {
        var request = new MockHttpServletRequest("POST", "/login");
        request.addHeader("X-Correlation-ID", "a".repeat(64));
        var response = new MockHttpServletResponse();
        MDC.put("traceId", "upstream-trace");
        new CorrelationFilter()
                .doFilter(
                        request,
                        response,
                        (req, res) ->
                                assertThat(MDC.get("correlationId")).isEqualTo("a".repeat(64)));
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("a".repeat(64));
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("upstream-trace");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "bad\r\nInjected: yes",
                "with spaces",
                "private@example.com",
                "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"
            })
    void replacesMissingOrUnsafeIdentifierWithUuid(String supplied) throws Exception {
        var request = new MockHttpServletRequest("GET", "/internal/health");
        if (supplied != null) request.addHeader("X-Correlation-ID", supplied);
        var response = new MockHttpServletResponse();
        new CorrelationFilter()
                .doFilter(
                        request,
                        response,
                        (req, res) ->
                                assertThat(MDC.get("correlationId"))
                                        .isEqualTo(response.getHeader("X-Correlation-ID")));
        assertThatCode(() -> UUID.fromString(response.getHeader("X-Correlation-ID")))
                .doesNotThrowAnyException();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesAmbiguousRepeatedHeaders() throws Exception {
        var request = new MockHttpServletRequest("GET", "/internal/health");
        request.addHeader("X-Correlation-ID", "first");
        request.addHeader("X-Correlation-ID", "second");
        var response = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(request, response, (req, res) -> {});
        assertThatCode(() -> UUID.fromString(response.getHeader("X-Correlation-ID")))
                .doesNotThrowAnyException();
    }

    @Test
    void logsOnlySafeMetadataAndCleansMdcWhenRequestFails() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(CorrelationFilter.class);
        var appender =
                new ListAppender<ILoggingEvent>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("POST", "/secret-path");
            request.setQueryString("password=secret-query");
            request.addHeader("Authorization", "Bearer secret-token");
            request.addHeader("X-Correlation-ID", "safe-request-1");
            request.setContent("secret-address-and-password".getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            assertThatThrownBy(
                            () ->
                                    new CorrelationFilter()
                                            .doFilter(
                                                    request,
                                                    response,
                                                    (req, res) -> {
                                                        throw new ServletException(
                                                                "secret-exception");
                                                    }))
                    .isInstanceOf(ServletException.class);
            assertThat(MDC.get("correlationId")).isNull();
            assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("safe-request-1");
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "safe-request-1");
            assertThat(event.getKeyValuePairs())
                    .anySatisfy(
                            pair -> {
                                assertThat(pair.key).isEqualTo("status");
                                assertThat(pair.value).isEqualTo(500);
                            });
            assertThat(
                            event.getFormattedMessage()
                                    + event.getKeyValuePairs()
                                    + event.getMDCPropertyMap())
                    .doesNotContain("secret-");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
