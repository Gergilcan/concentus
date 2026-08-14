package com.concentus.model;

/**
 * Whether a runtime an MCP command needs is installed on this machine.
 *
 * <p>{@code version} is the tool's own first line of {@code --version} output, kept verbatim: it
 * is proof the binary ran, not just that a file exists with the right name. Empty when the probe
 * found nothing.
 *
 * <p>The install COMMANDS deliberately live in the desktop shell, not here: only the shell runs
 * them, and a backend that also runs on a server has no business executing installers. What this
 * carries instead is {@code docsUrl}, so a UI outside the shell (a browser during development)
 * can still point at the official instructions rather than at nothing.
 *
 * @param id       stable identifier used by the installer ("node", "npm", "pnpm", "python", "pipx", "uv")
 * @param label    what to call it in the UI
 * @param found    the command answered {@code --version}
 * @param version  its answer, trimmed to one line; empty when not found
 * @param neededFor the MCP commands that need it, for the UI to explain why it is being asked about
 * @param docsUrl  the tool's own installation instructions
 */
public record RuntimeStatus(String id, String label, boolean found, String version, String neededFor,
                            String docsUrl) {
}
