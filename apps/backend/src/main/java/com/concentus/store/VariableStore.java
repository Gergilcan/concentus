package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.Variable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Organization-level prompt variables, in the database. */
@Component
public class VariableStore extends JsonStore<Variable> {

    public VariableStore(JdbcTemplate jdbc, @Value("${app.data-dir}") String dataDir, ObjectMapper mapper,
                         OrgContext orgContext) {
        super(jdbc, mapper, Variable.class, "variable", "var_", Path.of(dataDir, "variables"), orgContext);
    }

    /**
     * Organization values with the flow's own laid on top — the one precedence rule the feature
     * has. The flow map may be null (flows saved before variables existed).
     */
    public Map<String, String> merged(Map<String, String> flowVariables) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Variable v : list()) {
            if (v.name() != null && !v.name().isBlank()) {
                values.put(v.name().trim(), v.value() == null ? "" : v.value());
            }
        }
        if (flowVariables != null) {
            flowVariables.forEach((name, value) -> {
                if (name != null && !name.isBlank()) {
                    values.put(name.trim(), value == null ? "" : value);
                }
            });
        }
        return values;
    }

    @Override
    protected String idOf(Variable v) {
        return v.id();
    }

    @Override
    protected Variable withId(Variable v, String id) {
        return v.withId(id);
    }

    @Override
    protected String sortKey(Variable v) {
        return v.name();
    }
}
