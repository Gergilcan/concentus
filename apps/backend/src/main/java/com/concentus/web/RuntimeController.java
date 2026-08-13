package com.concentus.web;

import com.concentus.model.RuntimeCheck;
import com.concentus.model.RuntimeStatus;
import com.concentus.service.RuntimeProbe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What stdio MCP servers need in order to launch: node/npm/pnpm, python/pipx/uv.
 *
 * <p>Read-only by design. Installing a runtime is the desktop shell's action, not an API call —
 * see {@link RuntimeProbe}.
 */
@RestController
@RequestMapping("/api/runtimes")
public class RuntimeController {

    private final RuntimeProbe probe;

    public RuntimeController(RuntimeProbe probe) {
        this.probe = probe;
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
}
