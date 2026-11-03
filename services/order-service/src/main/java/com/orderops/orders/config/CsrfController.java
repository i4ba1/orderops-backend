package com.orderops.orders.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "csrf", description = "Cross-site request forgery token for the session")
public class CsrfController {
    @Operation(summary = "Session CSRF token", description = "Obtain a token before login and again after a successful login. Writes require this token.")
    @ApiResponse(responseCode = "200", description = "CSRF token", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"headerName\":\"X-CSRF-TOKEN\",\"parameterName\":\"_csrf\",\"token\":\"...\"}")))
    @GetMapping("/api/csrf")
    public Map<String, String> token(CsrfToken token) {
        return Map.of(
                "headerName",
                token.getHeaderName(),
                "parameterName",
                token.getParameterName(),
                "token",
                token.getToken());
    }
}
