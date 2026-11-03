package com.orderops.orders.config;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class SessionController {
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
