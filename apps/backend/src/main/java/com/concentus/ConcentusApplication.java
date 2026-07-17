package com.concentus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the agent designer/runner API.
 *
 * <p>Anthropic credentials are resolved by {@code AnthropicClientProvider} — by default
 * from your {@code ant auth login} Claude login, or from {@code ANTHROPIC_API_KEY} when set.
 * The client is built lazily, so the server starts without any credentials.
 */
@SpringBootApplication
public class ConcentusApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConcentusApplication.class, args);
    }
}
