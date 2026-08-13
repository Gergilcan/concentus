package com.concentus.service;

import com.concentus.model.DatabaseDef;
import com.concentus.model.FacadeProfile;
import com.concentus.model.FlowGraph;
import com.concentus.model.KnowledgeDef;
import com.concentus.model.LibraryAgent;
import com.concentus.model.McpDef;
import com.concentus.model.SkillDef;
import com.concentus.model.Variable;
import com.concentus.secrets.CredentialStore;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.DatabaseStore;
import com.concentus.store.FacadeProfileStore;
import com.concentus.store.FlowStore;
import com.concentus.store.KnowledgeStore;
import com.concentus.store.McpDefStore;
import com.concentus.store.SkillStore;
import com.concentus.store.VariableStore;
import com.concentus.auth.OrgContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything-as-one-file: export the machine's whole configuration, import it on another.
 *
 * <p>What travels: flows (with their variables), the agent library, MCP server definitions,
 * facade profiles, database connections, knowledge base definitions, skills (files included —
 * they are bounded small), and organization variables. What deliberately does not: knowledge
 * DOCUMENTS (embeddings run to hundreds of megabytes and re-upload cleanly), run history (it is
 * history, not configuration), storage settings (machine-specific by definition) — and above all
 * SECRETS. Credentials export as metadata only (id, label, kind); the import re-creates them as
 * placeholders under the SAME ids, which is the part that matters: every flow that referenced
 * {@code cred_x} still points at a credential called by its old name, and the person re-enters
 * each value once. A secret exported in a file is a secret leaked eventually.
 *
 * <p>Import is an upsert by id, never a wipe: what the file carries overwrites same-id records
 * and adds the rest, and what the file does not mention is left untouched. Ids are preserved on
 * purpose — cross-references (flow→skill, flow→facade, node→credential) are by id, and breaking
 * them would import a library of flows that all point at nothing.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final int FORMAT_VERSION = 1;

    private final FlowStore flows;
    private final AgentLibraryStore agents;
    private final McpDefStore mcpDefs;
    private final FacadeProfileStore facades;
    private final DatabaseStore databases;
    private final KnowledgeStore knowledge;
    private final SkillStore skills;
    private final VariableStore variables;
    private final CredentialStore credentials;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;

    public BackupService(FlowStore flows, AgentLibraryStore agents, McpDefStore mcpDefs,
                         FacadeProfileStore facades, DatabaseStore databases,
                         KnowledgeStore knowledge, SkillStore skills, VariableStore variables,
                         CredentialStore credentials, OrgContext orgContext, ObjectMapper mapper) {
        this.flows = flows;
        this.agents = agents;
        this.mcpDefs = mcpDefs;
        this.facades = facades;
        this.databases = databases;
        this.knowledge = knowledge;
        this.skills = skills;
        this.variables = variables;
        this.credentials = credentials;
        this.orgContext = orgContext;
        this.mapper = mapper;
    }

    /** The whole configuration as one JSON document. */
    public ObjectNode export() {
        ObjectNode root = mapper.createObjectNode();
        root.put("concentusExport", FORMAT_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.set("flows", mapper.valueToTree(flows.list()));
        root.set("agents", mapper.valueToTree(agents.list()));
        root.set("mcpServers", mapper.valueToTree(mcpDefs.list()));
        root.set("facadeProfiles", mapper.valueToTree(facades.list()));
        root.set("databases", mapper.valueToTree(databases.list()));
        root.set("knowledgeBases", mapper.valueToTree(knowledge.list()));
        root.set("skills", mapper.valueToTree(skills.list()));
        root.set("variables", mapper.valueToTree(variables.list()));

        // Metadata only — the secret itself never leaves the machine. The ids are what make the
        // import able to keep every reference pointing at something re-fillable.
        ArrayNode credentialMeta = root.putArray("credentials");
        if (credentials.isAvailable()) {
            for (CredentialStore.Credential c : credentials.list(orgContext.defaultOrganizationId())) {
                ObjectNode meta = credentialMeta.addObject();
                meta.put("id", c.id());
                meta.put("label", c.label());
                meta.put("kind", c.kind());
            }
        }
        return root;
    }

    /** What an import did, category by category, plus what the person still has to do by hand. */
    public record ImportReport(Map<String, Integer> imported, List<String> credentialsToReenter,
                               List<String> warnings) {
    }

    public ImportReport importBundle(JsonNode bundle) {
        if (bundle == null || !bundle.path("concentusExport").isInt()) {
            throw new IllegalArgumentException(
                    "Not a Concentus export — expected a file created by Export everything.");
        }
        int version = bundle.path("concentusExport").asInt();
        if (version > FORMAT_VERSION) {
            throw new IllegalArgumentException("This export is from a newer Concentus (format "
                    + version + "); update this installation first.");
        }

        Map<String, Integer> imported = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        imported.put("flows", importList(bundle, "flows", FlowGraph.class, flows::save, warnings));
        imported.put("agents", importList(bundle, "agents", LibraryAgent.class, agents::save, warnings));
        imported.put("mcpServers", importList(bundle, "mcpServers", McpDef.class, mcpDefs::save, warnings));
        imported.put("facadeProfiles",
                importList(bundle, "facadeProfiles", FacadeProfile.class, facades::save, warnings));
        imported.put("databases", importList(bundle, "databases", DatabaseDef.class, databases::save, warnings));
        imported.put("knowledgeBases",
                importList(bundle, "knowledgeBases", KnowledgeDef.class, knowledge::save, warnings));
        imported.put("skills", importList(bundle, "skills", SkillDef.class, skills::save, warnings));
        imported.put("variables", importList(bundle, "variables", Variable.class, variables::save, warnings));

        List<String> reenter = new ArrayList<>();
        imported.put("credentials", importCredentials(bundle, reenter, warnings));

        log.info("Imported a configuration bundle: {}{}", imported,
                reenter.isEmpty() ? "" : " — " + reenter.size() + " credential value(s) to re-enter");
        return new ImportReport(imported, reenter, warnings);
    }

    /**
     * Re-creates the file's credentials as placeholders under their original ids, and reports how
     * many are now waiting for a value.
     *
     * <p>The id is the whole point: every flow that referenced {@code cred_x} still points at a
     * credential of that name, so the person re-enters each secret once instead of re-wiring every
     * node. {@code reenter} collects the labels to put in front of them.
     */
    private int importCredentials(JsonNode bundle, List<String> reenter, List<String> warnings) {
        JsonNode metas = bundle.path("credentials");
        if (!metas.isArray()) return 0;
        if (!credentials.isAvailable()) {
            if (!metas.isEmpty()) {
                warnings.add("Credential storage is unavailable here — the file's credentials "
                        + "were skipped; flows referencing them need new ones.");
            }
            return 0;
        }

        int placeholders = 0;
        for (JsonNode meta : metas) {
            String id = meta.path("id").asText("");
            String label = meta.path("label").asText("");
            if (id.isBlank() || label.isBlank()) continue;
            try {
                boolean created = credentials.importPlaceholder(
                        orgContext.defaultOrganizationId(), id, label,
                        meta.path("kind").asText("api-token"));
                if (created) {
                    reenter.add(label);
                    placeholders++;
                }
            } catch (RuntimeException e) {
                warnings.add("Credential '" + label + "': " + e.getMessage());
            }
        }
        return placeholders;
    }

    private <T> int importList(JsonNode bundle, String key, Class<T> type,
                               Function<T, T> save, List<String> warnings) {
        int count = 0;
        for (JsonNode node : bundle.path(key)) {
            try {
                save.apply(mapper.convertValue(node, type));
                count++;
            } catch (RuntimeException e) {
                // One broken record must not sink the rest of the bundle; name it and continue.
                warnings.add(key + ": one record was skipped (" + e.getMessage() + ")");
            }
        }
        return count;
    }
}
