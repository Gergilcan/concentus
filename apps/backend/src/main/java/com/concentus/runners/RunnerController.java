package com.concentus.runners;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Registered runners: the roster, registration (the token, once), rename, revoke, delete.
 *
 * <p>Reading is every signed-in role — where things run is worth seeing — and the backend
 * filters what each caller sees. Writes are MEMBER and above by the rule in {@code SecurityConfig},
 * and {@link RunnerService} then asks the real question per scope.
 */
@RestController
@RequestMapping("/api/runners")
public class RunnerController {

    public record CreateRequest(String name, String scope, String groupId) {
    }

    public record RenameRequest(String name) {
    }

    private final RunnerService service;

    public RunnerController(RunnerService service) {
        this.service = service;
    }

    @GetMapping
    public RunnerService.Listing list() {
        return service.list();
    }

    @GetMapping("/usable")
    public List<RunnerView> usable() {
        return service.usable();
    }

    @PostMapping
    public RunnerService.Created create(@RequestBody CreateRequest body) {
        return service.register(body.name(), body.scope(), body.groupId());
    }

    @PutMapping("/{id}")
    public RunnerView rename(@PathVariable String id, @RequestBody RenameRequest body) {
        return service.rename(id, body.name());
    }

    @PostMapping("/{id}/revoke")
    public RunnerView revoke(@PathVariable String id) {
        return service.revoke(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
