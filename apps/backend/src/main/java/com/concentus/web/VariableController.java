package com.concentus.web;

import com.concentus.model.Variable;
import com.concentus.store.VariableStore;
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

/** CRUD for organization-level prompt variables ({@code {{NAME}}} in any flow's prompts). */
@RestController
@RequestMapping("/api/variables")
public class VariableController {

    private final VariableStore store;

    public VariableController(VariableStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Variable> list() {
        return store.list();
    }

    @PostMapping
    public Variable save(@RequestBody Variable variable) {
        if (variable == null || variable.name() == null || variable.name().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        if (!variable.name().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Variable names are letters, digits, _ . - (they appear as {{NAME}} in prompts).");
        }
        return store.save(variable);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
