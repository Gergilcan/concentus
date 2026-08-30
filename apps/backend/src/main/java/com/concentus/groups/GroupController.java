package com.concentus.groups;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Groups inside an organization, over HTTP. The rules are all {@link GroupService}'s; this class
 * only names the routes and the shapes.
 *
 * <p>Reading is every signed-in role (the security configuration lets any session GET under
 * {@code /api}), which is right: a member has to see the groups they are in and the settings a
 * flow of theirs runs under. Writing is MEMBER and above at the door, and the service then asks
 * the real question — an administrator, or a manager of this group — so an OPERATOR cannot
 * reshape a group and a MEMBER cannot reshape one they do not manage.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    public record GroupRequest(String name, String description) {
    }

    public record MemberRequest(String userId, boolean manager) {
    }

    public record SettingsRequest(Map<String, String> values) {
    }

    public record AssignRequest(String kind, String resourceId, String groupId) {
    }

    /**
     * @param allowed whether groups may be created or changed on this license
     * @param refusal the feature's sentence when they may not; null when they may
     */
    public record Listing(List<Group> groups, boolean allowed, String refusal) {
    }

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public Listing list() {
        return new Listing(groups.visible(), groups.allowed(), groups.refusal());
    }

    @PostMapping
    public Group create(@RequestBody GroupRequest body) {
        if (body == null) throw new IllegalArgumentException("A body is required.");
        return groups.create(body.name(), body.description());
    }

    @PutMapping("/{id}")
    public Group update(@PathVariable String id, @RequestBody GroupRequest body) {
        if (body == null) throw new IllegalArgumentException("A body is required.");
        return groups.update(id, body.name(), body.description());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        GroupStore.Deleted deleted = groups.delete(id);
        return Map.of("deleted", deleted.deleted(), "unscoped", deleted.unscoped());
    }

    @GetMapping("/{id}/members")
    public List<GroupMember> members(@PathVariable String id) {
        return groups.members(id);
    }

    @PostMapping("/{id}/members")
    public GroupMember addMember(@PathVariable String id, @RequestBody MemberRequest body) {
        if (body == null) throw new IllegalArgumentException("A body is required.");
        return groups.addMember(id, body.userId(), body.manager());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String id, @PathVariable String userId) {
        groups.removeMember(id, userId);
    }

    @GetMapping("/{id}/settings")
    public GroupService.SettingsView settings(@PathVariable String id) {
        return groups.settings(id);
    }

    @PutMapping("/{id}/settings")
    public GroupService.SettingsView saveSettings(@PathVariable String id, @RequestBody SettingsRequest body) {
        return groups.replaceSettings(id, body == null ? Map.of() : body.values());
    }

    @GetMapping("/{id}/policy")
    public GroupService.PolicyView policy(@PathVariable String id) {
        return groups.policy(id);
    }

    @PutMapping("/{id}/policy")
    public GroupService.PolicyView savePolicy(@PathVariable String id, @RequestBody GroupPolicy draft) {
        return groups.savePolicy(id, draft);
    }

    @PostMapping("/assign")
    public GroupService.Assignment assign(@RequestBody AssignRequest body) {
        if (body == null) throw new IllegalArgumentException("A body is required.");
        return groups.assign(body.kind(), body.resourceId(), body.groupId());
    }

    @GetMapping("/status")
    public GroupService.Status status() {
        return groups.status();
    }
}
