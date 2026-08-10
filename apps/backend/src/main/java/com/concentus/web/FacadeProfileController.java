package com.concentus.web;

import com.concentus.model.FacadeProfile;
import com.concentus.store.FacadeProfileStore;
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

/** CRUD for facade profiles — what an independent worker may reach through its MCP facade. */
@RestController
@RequestMapping("/api/facade-profiles")
public class FacadeProfileController {

    private final FacadeProfileStore store;

    public FacadeProfileController(FacadeProfileStore store) {
        this.store = store;
    }

    @GetMapping
    public List<FacadeProfile> list() {
        return store.list();
    }

    @PostMapping
    public FacadeProfile save(@RequestBody FacadeProfile profile) {
        if (profile == null || profile.name() == null || profile.name().isBlank()) {
            throw new IllegalArgumentException("A profile needs a name.");
        }
        return store.save(profile);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }
}
