package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.model.RuntimeCheck;
import com.concentus.model.RuntimeInstallPlan;
import com.concentus.model.RuntimeStatus;
import com.concentus.service.RuntimeInstaller;
import com.concentus.service.RuntimeProbe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What stdio MCP servers need in order to launch: node/npm/pnpm, python/pipx/uv — and, on a
 * desktop install, installing the one that is missing.
 *
 * <p>The install endpoint is the only thing here that changes the MACHINE rather than the app's
 * own data, so it is refused wherever accounts are switched on: a server's authenticated user must
 * not be able to install software on the host. On the desktop there are no accounts, the socket is
 * loopback-only, and the person clicking the button is the person who owns the machine.
 */
@RestController
@RequestMapping("/api/runtimes")
public class RuntimeController {

    private final RuntimeProbe probe;
    private final RuntimeInstaller installer;
    private final OrgContext orgContext;
    /** Installing changes the host, which only the person sitting at it may do. */
    private final boolean desktop;

    public RuntimeController(RuntimeProbe probe, RuntimeInstaller installer, OrgContext orgContext,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${app.desktop:false}") boolean desktop) {
        this.probe = probe;
        this.installer = installer;
        this.orgContext = orgContext;
        this.desktop = desktop;
    }

    /** Every runtime this app knows about. {@code refresh=true} after an install. */
    @GetMapping
    public List<RuntimeStatus> list(@RequestParam(defaultValue = "false") boolean refresh) {
        return probe.all(refresh);
    }

    /** What one configured MCP command needs, and whether this machine has it. */
    @GetMapping("/check")
    public RuntimeCheck check(@RequestParam(defaultValue = "") String command,
                              @RequestParam(defaultValue = "false") boolean refresh) {
        return probe.check(command, refresh);
    }

    /**
     * What installing this runtime would run here — fetched so the command can be shown next to
     * the button, before anything happens. Available even where installing is not, so the UI can
     * still tell someone what to run by hand.
     */
    @GetMapping("/{id}/install-plan")
    public RuntimeInstallPlan installPlan(@PathVariable String id) {
        return installer.plan(id);
    }

    /**
     * Installs a runtime and answers with the installer's own output.
     *
     * <p>Synchronous: a desktop install takes a minute at most, and the alternative — a job id to
     * poll — buys nothing for a button someone is watching. The probe cache is bypassed on the
     * next check so a freshly installed runtime is seen immediately.
     */
    @PostMapping("/{id}/install")
    public RuntimeInstaller.RuntimeInstallResult install(@PathVariable String id) {
        requireDesktop();
        return installer.install(id);
    }

    /**
     * Installing changes the machine, not the app's data. That is fine on a desktop install —
     * no accounts, loopback only, and the person clicking owns the machine — and is not something
     * a server's authenticated user should be able to do to its host.
     */
    private void requireDesktop() {
        if (!desktop) {
            throw new IllegalStateException(
                    "Installing runtimes is only available in the desktop app. On a server, install "
                            + "them on the host yourself.");
        }
    }
}
