package com.orderops.orders.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private static final String HEADER = "X-Correlation-ID";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = request.getHeaders(HEADER);
        String supplied = headers.hasMoreElements() ? headers.nextElement() : null;
        String correlationId =
                supplied != null
                                && !headers.hasMoreElements()
                                && SAFE_ID.matcher(supplied).matches()
                        ? supplied
                        : UUID.randomUUID().toString();
        response.setHeader(HEADER, correlationId);
        long started = System.nanoTime();
        boolean completed = false;
        try (var context = MDC.putCloseable("correlationId", correlationId)) {
            try {
                chain.doFilter(request, response);
                completed = true;
            } finally {
                // Never log arbitrary URLs, headers, bodies or exception messages.
                log.atInfo()
                        .addKeyValue("status", completed ? response.getStatus() : 500)
                        .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                        .log("HTTP request completed");
            }
        }
    }
}
