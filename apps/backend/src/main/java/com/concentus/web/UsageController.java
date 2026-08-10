package com.concentus.web;

import com.concentus.service.ClaudeUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** This machine's measured Claude consumption, for the Usage page. */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final ClaudeUsageService usage;

    public UsageController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    public Map<String, Object> summary() {
        return usage.summary();
    }
}
