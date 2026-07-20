package com.concentus.service;

import com.concentus.llm.ChatTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read/write/edit tools for the api backend, confined to an agent's context folders.
 *
 * <p>Every path an agent supplies is resolved and checked against the folders that agent was
 * granted — the same containment {@link ContextFolderResolver} applies to the folder list itself,
 * so a model cannot reach outside its workspace by asking for {@code ../../etc/passwd} or by
 * following a symlink planted inside an allowed folder.
 *
 * <p>Tools are only offered to agents that actually have context folders; an agent with none gets
 * no file tools at all rather than tools that always refuse.
 */
@Component
public class FileTools {

    /** Truncation guard: a whole repo pasted into the prompt would blow the context window. */
    private static final int MAX_READ_CHARS = 60_000;
    private static final int MAX_LISTED_ENTRIES = 400;

    public static final String READ = "read_file";
    public static final String WRITE = "write_file";
    public static final String EDIT = "edit_file";
    public static final String LIST = "list_files";

    private final ObjectMapper mapper;

    public FileTools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Tool definitions for an agent, or empty when it has no folders to work in. */
    public List<ChatTypes.ToolSpec> toolsFor(List<Path> roots) {
        if (roots == null || roots.isEmpty()) return List.of();
        String where = "Paths must be inside: "
                + roots.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("");

        List<ChatTypes.ToolSpec> tools = new ArrayList<>();
        tools.add(new ChatTypes.ToolSpec(LIST,
                "List files under a directory. " + where,
                schema(prop("path", "string", "Directory to list. Defaults to the first context folder."))));
        tools.add(new ChatTypes.ToolSpec(READ,
                "Read a file's contents. " + where,
                schema(required("path"), prop("path", "string", "Absolute path of the file to read."))));
        tools.add(new ChatTypes.ToolSpec(WRITE,
                "Create or overwrite a file. " + where,
                schema(required("path", "content"),
                        prop("path", "string", "Absolute path of the file to write."),
                        prop("content", "string", "Full new contents of the file."))));
        tools.add(new ChatTypes.ToolSpec(EDIT,
                "Replace an exact substring in a file. Fails if it does not appear exactly once. " + where,
                schema(required("path", "old_text", "new_text"),
                        prop("path", "string", "Absolute path of the file to edit."),
                        prop("old_text", "string", "Exact text to replace; must occur exactly once."),
                        prop("new_text", "string", "Replacement text."))));
        return tools;
    }

    public boolean isFileTool(String name) {
        return READ.equals(name) || WRITE.equals(name) || EDIT.equals(name) || LIST.equals(name);
    }

    /**
     * Runs a file tool. Failures come back as text for the model to read rather than exceptions —
     * a bad path should let it correct itself, not end the run.
     */
    public String execute(String toolName, String argumentsJson, List<Path> roots) {
        JsonNode args;
        try {
            args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return "Error: arguments were not valid JSON.";
        }
        try {
            return switch (toolName) {
                case LIST -> list(args.path("path").asText(""), roots);
                case READ -> read(args.path("path").asText(""), roots);
                case WRITE -> write(args.path("path").asText(""), args.path("content").asText(""), roots);
                case EDIT -> edit(args.path("path").asText(""),
                        args.path("old_text").asText(""), args.path("new_text").asText(""), roots);
                default -> "Error: unknown tool '" + toolName + "'.";
            };
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ operations

    private String list(String raw, List<Path> roots) throws IOException {
        Path dir = raw.isBlank() ? roots.get(0) : resolveWithin(raw, roots);
        if (dir == null) return outside(raw, roots);
        if (!Files.isDirectory(dir)) return "Error: not a directory: " + dir;

        try (Stream<Path> walk = Files.walk(dir, 8)) {
            List<String> entries = walk
                    .filter(Files::isRegularFile)
                    // Noise that would crowd out real files and leak build artefacts.
                    .filter(p -> !p.toString().contains(".git" + java.io.File.separator))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "target" + java.io.File.separator))
                    .map(Path::toString)
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_LISTED_ENTRIES + 1L)
                    .toList();
            if (entries.isEmpty()) return "(no files)";
            if (entries.size() > MAX_LISTED_ENTRIES) {
                return String.join("\n", entries.subList(0, MAX_LISTED_ENTRIES))
                        + "\n… more files not shown; list a subdirectory to narrow it down.";
            }
            return String.join("\n", entries);
        }
    }

    private String read(String raw, List<Path> roots) throws IOException {
        Path file = resolveWithin(raw, roots);
        if (file == null) return outside(raw, roots);
        if (!Files.isRegularFile(file)) return "Error: no such file: " + raw;

        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.length() > MAX_READ_CHARS) {
            return content.substring(0, MAX_READ_CHARS)
                    + "\n\n… truncated at " + MAX_READ_CHARS + " characters.";
        }
        return content;
    }

    private String write(String raw, String content, List<Path> roots) throws IOException {
        Path file = resolveWithin(raw, roots);
        if (file == null) return outside(raw, roots);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "Wrote " + content.length() + " characters to " + file;
    }

    private String edit(String raw, String oldText, String newText, List<Path> roots) throws IOException {
        Path file = resolveWithin(raw, roots);
        if (file == null) return outside(raw, roots);
        if (!Files.isRegularFile(file)) return "Error: no such file: " + raw;
        if (oldText.isEmpty()) return "Error: old_text must not be empty.";

        String content = Files.readString(file, StandardCharsets.UTF_8);
        int first = content.indexOf(oldText);
        if (first < 0) return "Error: old_text does not appear in " + raw + ".";
        // Ambiguity is reported rather than guessed at — replacing the wrong occurrence is a
        // silent corruption the model has no way to notice.
        if (content.indexOf(oldText, first + 1) >= 0) {
            return "Error: old_text appears more than once in " + raw + "; include more context.";
        }
        Files.writeString(file, content.replace(oldText, newText), StandardCharsets.UTF_8);
        return "Edited " + file;
    }

    // ------------------------------------------------------------------ containment

    /**
     * Resolves a path and returns it only if it really sits inside one of the roots.
     *
     * <p>Comparison is done on the real path where it exists, so {@code ..} and symlinks cannot
     * escape. For a file being created, the nearest existing ancestor is checked instead — the
     * file itself has no real path yet.
     */
    Path resolveWithin(String raw, List<Path> roots) {
        if (raw == null || raw.isBlank()) return null;
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
        Path probe = candidate;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) return null;
        Path real;
        try {
            real = probe.toRealPath();
        } catch (IOException e) {
            return null;
        }
        for (Path root : roots) {
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                realRoot = root.toAbsolutePath().normalize();
            }
            if (real.startsWith(realRoot)) return candidate;
        }
        return null;
    }

    private static String outside(String raw, List<Path> roots) {
        return "Error: '" + raw + "' is outside this agent's context folders ("
                + roots.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("none")
                + "). Work only inside those.";
    }

    // ------------------------------------------------------------------ schema helpers

    private record Prop(String name, String type, String description) {
    }

    private static Prop prop(String name, String type, String description) {
        return new Prop(name, type, description);
    }

    private static String[] required(String... names) {
        return names;
    }

    private ObjectNode schema(Object... parts) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        var requiredArray = schema.putArray("required");
        for (Object part : parts) {
            if (part instanceof String[] names) {
                for (String n : names) requiredArray.add(n);
            } else if (part instanceof Prop p) {
                props.putObject(p.name()).put("type", p.type()).put("description", p.description());
            }
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
