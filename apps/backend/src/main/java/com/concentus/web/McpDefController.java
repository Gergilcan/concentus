package com.concentus.web;

import com.concentus.model.McpDef;
import com.concentus.store.McpDefStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD for reusable MCP server definitions (distinct from the live Claude Code list at /api/mcp). */
@RestController
@RequestMapping("/api/mcp-defs")
public class McpDefController {

    private final McpDefStore store;

    public McpDefController(McpDefStore store) {
        this.store = store;
    }

    @GetMapping
    public List<McpDef> list() {
        return store.list();
    }

    @PostMapping
    public McpDef save(@RequestBody McpDef def) {
        if (def == null || def.name() == null || def.name().isBlank()
                || def.url() == null || def.url().isBlank()) {
            throw new IllegalArgumentException("name and url are required.");
        }
        return store.save(def);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
