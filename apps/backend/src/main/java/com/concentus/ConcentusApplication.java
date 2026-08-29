package com.concentus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the agent designer/runner API.
 *
 * <p>Anthropic credentials are resolved by {@code AnthropicClientProvider} — by default
 * from {@code ANTHROPIC_API_KEY} when set, otherwise from the {@code claude} CLI's own login.
 * The API client is built lazily, so the server starts without any credentials.
 *
 * <p>Scheduling is on for the one job that is the application's own rather than a flow's — the
 * nightly retention purge ({@code RetentionService}). Flow schedules run on their own
 * {@code ScheduleService}, which is rebuilt whenever a flow is saved; a fixed cron on a bean is
 * the right shape for a job that never changes.
 */
@SpringBootApplication
@EnableScheduling
public class ConcentusApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConcentusApplication.class, args);
    }
}
