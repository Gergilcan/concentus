package com.concentus.execution;

import com.concentus.runners.RunnerRegistry;
import com.concentus.service.AgentRun;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Which host a run's processes are on: this machine, or the runner the run was launched for.
 *
 * <p>Asked per turn rather than fixed at launch, because a runner's connection is not: a run
 * restored after a restart names its runner and finds it connected again, or not. A run whose
 * runner is not connected cannot take a turn, and says so — the one answer that would be worse is
 * running it here, on a machine that may have no login and certainly has none of the runner's
 * folders.
 */
@Component
public class RunHosts {

    private final LocalRunHost local;
    private final RunnerRegistry runners;

    public RunHosts(LocalRunHost local, @Lazy RunnerRegistry runners) {
        this.local = local;
        this.runners = runners;
    }

    public RunHost local() {
        return local;
    }

    /**
     * The host of a run.
     *
     * @throws IllegalStateException when the run's runner is not connected right now
     */
    public RunHost hostOf(AgentRun run) {
        if (run == null || run.runnerId == null || run.runnerId.isBlank()) return local;
        return runners.hostFor(run.runnerId).orElseThrow(() -> new IllegalStateException(
                "Runner " + (run.runnerName == null ? run.runnerId : "'" + run.runnerName + "'")
                        + " is offline. Start it again, or set the flow to run somewhere else."));
    }
}
