package com.orderops.orders.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "session", description = "Authentication and current identity")
public class SessionController {
    @Operation(summary = "Current identity", description = "Returns the authenticated username and role.")
    @ApiResponse(responseCode = "200", description = "Session", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"username\":\"operator\",\"role\":\"OPERATOR\"}")))
    @GetMapping("/api/session")
    public SessionResponse session(Authentication authentication) {
        var role =
                authentication.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .filter(a -> a.equals("ROLE_OPERATOR") || a.equals("ROLE_VIEWER"))
                        .findFirst()
                        .orElseThrow();
        return new SessionResponse(authentication.getName(), role.substring(5));
    }

    public record SessionResponse(String username, String role) {}
}
