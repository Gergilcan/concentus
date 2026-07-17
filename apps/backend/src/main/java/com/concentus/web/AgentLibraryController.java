package com.concentus.web;

import com.concentus.model.LibraryAgent;
import com.concentus.store.AgentLibraryStore;
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

/** CRUD for reusable agent definitions in the YAML agent library. */
@RestController
@RequestMapping("/api/agents")
public class AgentLibraryController {

    private final AgentLibraryStore store;

    public AgentLibraryController(AgentLibraryStore store) {
        this.store = store;
    }

    @GetMapping
    public List<LibraryAgent> list() {
        return store.list();
    }

    @PostMapping
    public LibraryAgent save(@RequestBody LibraryAgent agent) {
        if (agent == null || agent.name() == null || agent.name().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        return store.save(agent);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
