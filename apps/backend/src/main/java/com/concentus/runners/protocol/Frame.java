package com.concentus.runners.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * One message on the runner socket, either direction. JSON text, a {@code type} each.
 *
 * <p>Records, so every frame is data and the whole protocol fits on one screen. Unknown properties
 * are skipped rather than refused: a hub and a runner a version apart must keep talking, and the
 * one that is behind simply does not see the field it does not know.
 *
 * <p>Requests from the hub carry a {@code reqId} and are answered by an {@link Ack} carrying the
 * same one; frames about a process carry its {@code procId}. Nothing else correlates.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Frame.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Frame.Heartbeat.class, name = "heartbeat"),
        @JsonSubTypes.Type(value = Frame.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = Frame.Stdout.class, name = "stdout"),
        @JsonSubTypes.Type(value = Frame.Log.class, name = "log"),
        @JsonSubTypes.Type(value = Frame.Exit.class, name = "exit"),
        @JsonSubTypes.Type(value = Frame.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Frame.WorkspaceSync.class, name = "workspace.sync"),
        @JsonSubTypes.Type(value = Frame.GitClone.class, name = "git.clone"),
        @JsonSubTypes.Type(value = Frame.GitHead.class, name = "git.head"),
        @JsonSubTypes.Type(value = Frame.GitPatchOf.class, name = "git.patchOf"),
        @JsonSubTypes.Type(value = Frame.GitPatchSince.class, name = "git.patchSince"),
        @JsonSubTypes.Type(value = Frame.ContextResolve.class, name = "context.resolve"),
        @JsonSubTypes.Type(value = Frame.FsRead.class, name = "fs.read"),
        @JsonSubTypes.Type(value = Frame.ProcStart.class, name = "proc.start"),
        @JsonSubTypes.Type(value = Frame.ProcStdin.class, name = "proc.stdin"),
        @JsonSubTypes.Type(value = Frame.ProcStop.class, name = "proc.stop"),
        @JsonSubTypes.Type(value = Frame.WorkspaceDelete.class, name = "workspace.delete"),
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface Frame {

    // ------------------------------------------------------------------ runner → hub

    /**
     * What the runner is, said once after the handshake.
     *
     * @param authKind      {@code subscription}, {@code api-key} or {@code none} — how its CLI
     *                      authenticates, so the roster can show it and nobody has to guess
     * @param capacity      how many CLI processes it runs at once
     * @param fileSeparator its path separator, so the hub can rewrite mirror paths for it
     * @param workdirRoot   where it keeps run workspaces: {@code <root>/<runId>}
     * @param hubUrl        the http(s) address it dialed — where its processes reach this backend
     * @param name          the name it was started with, or null to keep the registered one
     */
    record Hello(String version, String os, String arch, String hostname, String javaVersion,
                 String claudeCommand, boolean claudeLoggedIn, String claudeVersion, String authKind,
                 int capacity, String fileSeparator, String workdirRoot, String hubUrl,
                 List<String> contextRoots, String name) implements Frame {
    }

    /** Every fifteen seconds; {@code busy} is how many processes it is running for this hub. */
    record Heartbeat(int busy) implements Frame {
    }

    /** The answer to a request. {@code result} is the request's own shape (see the *Result records). */
    record Ack(String reqId, boolean ok, String error, JsonNode result) implements Frame {
        /** A JSON {@code null} on the wire reads back as a NullNode; "no result" is one thing, not two. */
        public Ack {
            if (result != null && result.isNull()) result = null;
        }
    }

    /** One line of a process's output. */
    record Stdout(String procId, String line) implements Frame {
    }

    /** A line for the run's console that is not the CLI's — a slot wait, a clone in progress. */
    record Log(String procId, String text) implements Frame {
    }

    record Exit(String procId, int code) implements Frame {
    }

    // ------------------------------------------------------------------ hub → runner

    record Welcome(String runnerId, String name) implements Frame {
    }

    /** Files of the run's mirror, relative to its workdir; directories are created on the way. */
    record WorkspaceSync(String reqId, String runId, List<FileEntry> files) implements Frame {
    }

    record FileEntry(String path, String content) {
    }

    /** Clone these into the run's workdir (or {@code subdir} of it). Tokens travel here, once. */
    record GitClone(String reqId, String runId, String subdir, List<RepoEntry> repos) implements Frame {
    }

    record RepoEntry(String url, String branch, String token, String envVar) {
    }

    record GitHead(String reqId, String directory) implements Frame {
    }

    record GitPatchOf(String reqId, String directory) implements Frame {
    }

    record GitPatchSince(String reqId, String directory, String base) implements Frame {
    }

    record ContextResolve(String reqId, List<String> folders) implements Frame {
    }

    /** A CLAUDE.md reference (a file, or a folder holding one), resolved under the runner's allowlist. */
    record FsRead(String reqId, String path) implements Frame {
    }

    /** Spawn the CLI. Acked once it is running — after the runner's own slot, however long that takes. */
    record ProcStart(String reqId, String procId, String runId, List<String> args, String workdir,
                     Map<String, String> env) implements Frame {
    }

    /** Bytes for the process's stdin, base64; {@code close} ends its input. */
    record ProcStdin(String procId, String data, boolean close) implements Frame {
    }

    record ProcStop(String procId) implements Frame {
    }

    record WorkspaceDelete(String reqId, String runId) implements Frame {
    }

    // ------------------------------------------------------------------ results, inside Ack.result

    record SyncResult(String workdir) {
    }

    record CloneResult(List<CheckoutEntry> checkouts) {
    }

    /** One clone, in the order of the request's repos. {@code head} is the commit it starts from. */
    record CheckoutEntry(String url, String folder, String directory, String envVar, String head,
                         boolean ok, String error) {
    }

    record HeadResult(String head) {
    }

    /** {@code patch} null means nothing changed. */
    record PatchResult(String patch) {
    }

    record ContextResult(List<String> accepted, List<Rejection> rejected) {
    }

    record Rejection(String path, String reason) {
    }

    record ReadResult(String content, String error) {
    }
}
