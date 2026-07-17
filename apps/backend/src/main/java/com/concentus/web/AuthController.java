package com.concentus.web;

import com.concentus.model.AuthStatus;
import com.concentus.support.AnthropicClientProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports which credential the backend will use (Claude login vs API key). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AnthropicClientProvider provider;

    public AuthController(AnthropicClientProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/status")
    public AuthStatus status() {
        return provider.status();
    }
}
