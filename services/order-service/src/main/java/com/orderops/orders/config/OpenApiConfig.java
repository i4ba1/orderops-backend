package com.orderops.orders.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI orderOpsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OrderOps Order API")
                        .version("1.0.0")
                        .description("Order operations, durable fulfillment and recovery. "
                                + "Session authentication: fetch a CSRF token, POST form credentials to /login, "
                                + "then fetch a fresh token. Writes require X-CSRF-TOKEN and Idempotency-Key."))
                .addServersItem(new Server().url("http://localhost:8088"))
                .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")));
    }
}
