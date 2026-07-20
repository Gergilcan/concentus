package com.concentus.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The execution backends this deployment actually has.
 *
 * <p>Spring injects whichever are on the classpath and enabled; an operator turns one off by not
 * configuring it, and it then simply reports itself unavailable. That is what lets a deployment
 * run only the pieces it needs — no Claude CLI in an image that only ever calls OpenAI — and what
 * lets the designer offer only models that can actually run here.
 */
@Component
public class ExecutionBackends {

    private static final Logger log = LoggerFactory.getLogger(ExecutionBackends.class);

    private final List<ExecutionBackend> all;

    public ExecutionBackends(List<ExecutionBackend> all) {
        this.all = List.copyOf(all);
    }

    /** Every backend, including ones that are currently unavailable. */
    public List<ExecutionBackend> all() {
        return all;
    }

    /** Backends that can execute right now. */
    public List<ExecutionBackend> available() {
        return all.stream().filter(ExecutionBackend::isAvailable).toList();
    }

    public Optional<ExecutionBackend> byId(String id) {
        return all.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    /**
     * The backend that will run a model.
     *
     * <p>Availability is part of the match: an unavailable backend claiming a model would send the
     * run somewhere that cannot execute it. A model claimed by more than one available backend is
     * a configuration problem, so it is logged rather than resolved by declaration order — silently
     * picking one would make the choice depend on classpath ordering.
     */
    public Optional<ExecutionBackend> forModel(String model) {
        List<ExecutionBackend> matches = new ArrayList<>();
        for (ExecutionBackend b : all) {
            if (b.isAvailable() && b.supportsModel(model)) matches.add(b);
        }
        if (matches.size() > 1) {
            log.warn("Model '{}' is claimed by more than one backend ({}); using '{}'. "
                            + "Narrow the model-prefix configuration so exactly one claims it.",
                    model, matches.stream().map(ExecutionBackend::id).toList(), matches.get(0).id());
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
