package com.concentus.web;

import com.concentus.model.DatabaseDef;
import com.concentus.store.DatabaseStore;
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

/** CRUD for reusable database connection definitions. */
@RestController
@RequestMapping("/api/databases")
public class DatabaseController {

    private final DatabaseStore store;

    public DatabaseController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping
    public List<DatabaseDef> list() {
        return store.list();
    }

    @PostMapping
    public DatabaseDef save(@RequestBody DatabaseDef def) {
        if (def == null || def.label() == null || def.label().isBlank()
                || def.jdbcUrl() == null || def.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException("label and jdbcUrl are required.");
        }
        return store.save(def);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
