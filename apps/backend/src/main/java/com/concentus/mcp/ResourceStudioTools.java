package com.concentus.mcp;

import com.concentus.model.DatabaseDef;
import com.concentus.model.KnowledgeDef;
import com.concentus.model.LibraryAgent;
import com.concentus.model.McpDef;
import com.concentus.model.Variable;
import com.concentus.web.AgentLibraryController;
import com.concentus.web.CredentialController;
import com.concentus.web.DatabaseController;
import com.concentus.web.KnowledgeController;
import com.concentus.web.McpDefController;
import com.concentus.web.SkillController;
import com.concentus.web.VariableController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The library a flow's nodes point at: reusable agents, MCP servers, databases, knowledge bases,
 * skills, prompt variables and credentials.
 *
 * <p>Three tools over seven kinds rather than twenty-one tools. The operations are genuinely the
 * same shape — list, save, delete over a small record — and a model choosing a {@code kind} from an
 * enum it can see in the schema makes no more mistakes than one choosing between
 * {@code save_database} and {@code save_knowledge_base}. What it does buy is a tool list short
 * enough to stay read.
 *
 * <p>Credentials are the exception that proves the rule, and they are handled with care:
 * {@code list_resources} returns their labels and hints because a flow node references a credential
 * by id, and it never returns a secret because no API in this application does. Writing one is
 * allowed — the user asked for a library they can build over MCP — but a written value is
 * immediately unreadable, here as everywhere else.
 */
// Fixes this group's place in tools/list — the library the other two point at.
@Order(3)
@Component
public class ResourceStudioTools implements StudioToolset {

    private static final String KIND_AGENTS = "agents";
    private static final String KIND_MCP = "mcp-servers";
    private static final String KIND_DATABASES = "databases";
    private static final String KIND_KNOWLEDGE = "knowledge";
    private static final String KIND_VARIABLES = "variables";
    private static final String KIND_SKILLS = "skills";
    private static final String KIND_CREDENTIALS = "credentials";

    private static final String[] KINDS = {KIND_AGENTS, KIND_MCP, KIND_DATABASES, KIND_KNOWLEDGE,
            KIND_VARIABLES, KIND_SKILLS, KIND_CREDENTIALS};

    private final AgentLibraryController agents;
    private final McpDefController mcpServers;
    private final DatabaseController databases;
    private final KnowledgeController knowledge;
    private final VariableController variables;
    private final SkillController skills;
    private final CredentialController credentials;
    private final ObjectMapper mapper;

    public ResourceStudioTools(AgentLibraryController agents, McpDefController mcpServers,
                               DatabaseController databases, KnowledgeController knowledge,
                               VariableController variables, SkillController skills,
                               CredentialController credentials, ObjectMapper mapper) {
        this.agents = agents;
        this.mcpServers = mcpServers;
        this.databases = databases;
        this.knowledge = knowledge;
        this.variables = variables;
        this.skills = skills;
        this.credentials = credentials;
        this.mapper = mapper;
    }

    @Override
    public List<StudioTool> tools() {
        return List.of(
                new StudioTool("list_resources",
                        "What the library already holds, as JSON. Call this before wiring a flow to "
                                + "anything: a knowledge node needs a real baseId, an MCP node is "
                                + "usually a saved server, and every credentialId in a flow must be one "
                                + "of these ids. Credential VALUES are never returned by anything.",
                        Schema.of(mapper)
                                .requiredEnumeration("kind", "Which part of the library to list.", KINDS)
                                .build(),
                        this::listResources),

                new StudioTool("save_resource",
                        """
                        Creates or updates one library entry. Omit id to create, give it to update. \
                        Fields per kind:
                          agents       {id, name, model, effort, maxTokens, systemPrompt, description} \
                        — every save of an existing agent bumps its version, which the blocks that link \
                        it (libraryAgentId on an agent node) are compared against
                          mcp-servers  {id, name, url, credentialId, authHeader} for a remote server, \
                        or {id, name, command, args[], env{}} for a local stdio one
                          databases    {id, label, jdbcUrl, username, credentialId}
                          knowledge    {id, name, description} — creates the base; documents are \
                        uploaded from the app, not here
                          variables    {id, name, value, description} — usable as {{NAME}} in any prompt
                          credentials  {label, kind: "api-token"|"mail-password", value} — the value is \
                        encrypted on write and can never be read back
                        Skills cannot be saved here: a skill is an uploaded bundle, so install it from \
                        Resources > Skills in the app.""",
                        Schema.of(mapper)
                                .requiredEnumeration("kind", "Which part of the library to write.", KINDS)
                                .object("resource", "The entry itself. See the field list above.", true)
                                .build(),
                        this::saveResource),

                new StudioTool("delete_resource",
                        "Removes one library entry. Flows referencing it are not repaired — they keep "
                                + "the dangling id, and validate_flow will report it. Ask the user first.",
                        Schema.of(mapper)
                                .requiredEnumeration("kind", "Which part of the library to delete from.", KINDS)
                                .requiredStr("id", "Id of the entry, from list_resources.")
                                .bool("confirm", "Must be true.")
                                .build(),
                        this::deleteResource));
    }

    // ------------------------------------------------------------------- read

    private StudioTool.Result listResources(JsonNode args) {
        String kind = kind(args);
        Object listing = switch (kind) {
            case KIND_AGENTS -> agents.list();
            case KIND_MCP -> mcpServers.list();
            case KIND_DATABASES -> databases.list();
            case KIND_KNOWLEDGE -> knowledge.list();
            case KIND_VARIABLES -> variables.list();
            case KIND_SKILLS -> skills.list();
            case KIND_CREDENTIALS -> credentials.list();
            default -> throw unknown(kind);
        };
        if (listing instanceof List<?> items && items.isEmpty()) {
            return StudioTool.Result.ok("The library holds no " + kind + " yet.");
        }
        return StudioTool.Result.ok(Answers.json(mapper, listing));
    }

    // ------------------------------------------------------------------ write

    private StudioTool.Result saveResource(JsonNode args) {
        String kind = kind(args);
        JsonNode body = Args.object(args, "resource");
        Object saved = switch (kind) {
            case KIND_AGENTS -> agents.save(read(body, LibraryAgent.class, kind));
            case KIND_MCP -> mcpServers.save(read(body, McpDef.class, kind));
            case KIND_DATABASES -> databases.save(read(body, DatabaseDef.class, kind));
            case KIND_KNOWLEDGE -> knowledge.save(read(body, KnowledgeDef.class, kind));
            case KIND_VARIABLES -> variables.save(read(body, Variable.class, kind));
            case KIND_CREDENTIALS -> saveCredential(body);
            case KIND_SKILLS -> throw new IllegalArgumentException(
                    "Skills cannot be saved over MCP: a skill is an uploaded bundle of files, and "
                            + "there is nothing here to upload it from. Install it in the app under "
                            + "Resources > Skills, then reference its id from an agent node.");
            default -> throw unknown(kind);
        };
        return StudioTool.Result.ok("Saved. " + Answers.json(mapper, saved));
    }

    /**
     * A credential, created or relabelled.
     *
     * <p>Split from the others because it is the one kind whose update is not "save the record
     * again": the record that comes back has no value in it, so a naive round-trip would blank the
     * secret. Update therefore takes label and (optionally) a new value, and leaves the stored
     * secret alone when none is given.
     */
    private Object saveCredential(JsonNode body) {
        String id = Args.str(body, "id", null);
        String label = Args.str(body, "label", null);
        String value = Args.str(body, "value", null);
        if (id == null) {
            if (label == null || value == null) {
                throw new IllegalArgumentException(
                        "A new credential needs 'label' and 'value' (and 'kind': \"api-token\" or "
                                + "\"mail-password\").");
            }
            return credentials.create(new CredentialController.NewCredential(
                    label, Args.str(body, "kind", "api-token"), value));
        }
        return credentials.update(id, new CredentialController.UpdateCredential(label, value));
    }

    private StudioTool.Result deleteResource(JsonNode args) {
        String kind = kind(args);
        String id = Args.require(args, "id");
        if (!Args.bool(args, "confirm", false)) {
            return StudioTool.Result.failed("Not deleted: pass confirm=true to remove " + kind
                    + " '" + id + "'. Flows still pointing at it will break.");
        }
        switch (kind) {
            case KIND_AGENTS -> agents.delete(id);
            case KIND_MCP -> mcpServers.delete(id);
            case KIND_DATABASES -> databases.delete(id);
            case KIND_KNOWLEDGE -> knowledge.delete(id);
            case KIND_VARIABLES -> variables.delete(id);
            case KIND_SKILLS -> skills.delete(id);
            case KIND_CREDENTIALS -> credentials.delete(id);
            default -> throw unknown(kind);
        }
        return StudioTool.Result.ok("Deleted " + kind + " '" + id + "'.");
    }

    // ----------------------------------------------------------------- shared

    private static String kind(JsonNode args) {
        return Args.require(args, "kind").toLowerCase(java.util.Locale.ROOT);
    }

    private static IllegalArgumentException unknown(String kind) {
        return new IllegalArgumentException("Unknown kind '" + kind + "'. One of: "
                + String.join(", ", KINDS) + ".");
    }

    private <T> T read(JsonNode body, Class<T> type, String kind) {
        try {
            return mapper.treeToValue(body, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("That is not a valid " + kind + " entry: "
                    + e.getMessage() + " See save_resource's description for the fields.");
        }
    }
}
