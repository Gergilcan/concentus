package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A Claude Code Agent Skill, stored as a resource: the SKILL.md plus whatever files ride with it.
 *
 * <p>Files are held base64-encoded inside the JSON row. Skills are documents-plus-scripts measured
 * in kilobytes; a store of their own (blobs, filesystems) would be infrastructure for a size class
 * they do not occupy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDef(String id, String name, String description, List<SkillFile> files, String groupId) {

    /** The pre-groups shape: a skill the whole organization sees. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public SkillDef(String id, String name, String description, List<SkillFile> files) {
        this(id, name, description, files, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillFile(String path, String contentBase64) {
    }
}
