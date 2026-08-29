package com.concentus.web;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.OrgContext;
import com.concentus.config.SettingDef;
import com.concentus.config.Settings;
import com.concentus.config.SettingsCatalog;
import com.concentus.config.SettingsStore;
import com.concentus.license.LicenseService;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings screen's half of the settings.
 *
 * <p>Answers with the catalogue and the current value of each entry, plus where that value came
 * from — because "20" means different things depending on whether somebody chose it, the
 * deployment was configured with it, or it is simply the default, and the only one of those you
 * may change from here is the first.
 *
 * <p>Admin only. These are limits on what the machine will do and lists of what it may reach; an
 * account that may run flows is not thereby an account that may raise the ceiling on them.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsStore store;
    private final Settings settings;
    private final Environment environment;
    private final OrgContext orgContext;
    /** Which key changed and by whom; the value stays out of the trail, secret or not. */
    private final AuditService audit;
    private final LicenseService license;

    public SettingsController(SettingsStore store, Settings settings, Environment environment,
                              OrgContext orgContext, AuditService audit, LicenseService license) {
        this.store = store;
        this.settings = settings;
        this.environment = environment;
        this.orgContext = orgContext;
        this.audit = audit;
        this.license = license;
    }

    /** Where a value came from, which decides what the screen says next to it. */
    private enum Source {
        /** Somebody set it here. The only one that can be cleared. */
        STORED,
        /** The deployment was started with it. Editable, but the environment wins back on a reset. */
        CONFIGURED,
        /** Nobody has said anything; this is what the code does. */
        DEFAULT
    }

    @GetMapping
    public Map<String, Object> list() {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();

        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingDef def : SettingsCatalog.all()) {
            String stored = store.get(organizationId, def.key()).orElse(null);
            String configured = environment.getProperty(def.key());
            Source source = stored != null ? Source.STORED
                    : (configured != null && !configured.isBlank() ? Source.CONFIGURED : Source.DEFAULT);
            boolean hasValue = source != Source.DEFAULT;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", def.key());
            entry.put("group", def.group());
            entry.put("label", def.label());
            entry.put("help", def.help());
            entry.put("type", def.type().name());
            entry.put("restartRequired", def.restartRequired());
            entry.put("options", def.options());
            entry.put("source", source.name());
            // Null for every ordinary setting, and for a gated one wherever it works — Enterprise,
            // and free, which the license never limits. Only a Team install gets the sentence,
            // and the screen turns it into a disabled row that says which tier has the feature.
            entry.put("refusal", def.enterpriseOnly() != null && license.withheld(def.enterpriseOnly())
                    ? license.refusal(def.enterpriseOnly()) : null);
            if (def.type() == SettingDef.Type.SECRET) {
                // Never read back. What the screen needs is whether there is one, so it can show a
                // filled field without the value ever being reachable through this API — and
                // whether the one stored is locked, so it can ask for it again instead of showing
                // a filled field for a value nothing here can use.
                entry.put("value", "");
                entry.put("hasValue", hasValue);
                entry.put("locked", store.isLocked(organizationId, def.key()));
            } else {
                entry.put("value", stored != null ? stored : (configured == null ? "" : configured));
                entry.put("hasValue", hasValue);
            }
            out.add(entry);
        }
        return Map.of("settings", out);
    }

    /**
     * @param values key to value. A blank value clears the override and lets the deployment's own
     *               configuration — or the built-in default — stand again.
     */
    public record SettingsUpdate(Map<String, String> values) {
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody SettingsUpdate body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        String who = orgContext.currentUser()
                .map(com.concentus.auth.ConcentusUserDetails::email).orElse(null);

        Map<String, String> values = body == null || body.values() == null ? Map.of() : body.values();
        for (Map.Entry<String, String> change : values.entrySet()) {
            SettingDef def = SettingsCatalog.byKey(change.getKey()).orElseThrow(() ->
                    // Not silently ignored: a key the catalogue does not know is a typo or a stale
                    // client, and writing it would leave a row nothing ever reads.
                    new IllegalArgumentException("Unknown setting '" + change.getKey() + "'."));
            store.put(organizationId, def.key(), change.getValue(),
                    def.type() == SettingDef.Type.SECRET, who);
            // The key and whether it was cleared or set — never the value. A secret's value is a
            // credential, and a plain one's is a number nobody needs the trail to remember when
            // the settings screen shows it.
            boolean cleared = change.getValue() == null || change.getValue().isBlank();
            audit.record(AuditKinds.SETTING_CHANGED, "setting", def.key(), def.label(),
                    Map.of("key", def.key(), "cleared", cleared));
        }
        return list();
    }

    /**
     * What a setting is right now, resolved the way the rest of the application sees it.
     *
     * <p>Exists so a panel can show the effect of a change without knowing the precedence rules,
     * which are this class's business rather than the browser's.
     */
    @GetMapping("/effective")
    public Map<String, String> effective() {
        orgContext.requireAdmin();
        Map<String, String> out = new LinkedHashMap<>();
        for (SettingDef def : SettingsCatalog.all()) {
            if (def.type() == SettingDef.Type.SECRET) continue;
            out.put(def.key(), settings.get(def.key(), ""));
        }
        return out;
    }
}
