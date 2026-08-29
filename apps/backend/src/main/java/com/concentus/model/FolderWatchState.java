package com.concentus.model;

/**
 * What a folder-watch trigger remembers across restarts: when it last took stock of its folder.
 *
 * <p>Keyed by the flow's id, because the watch belongs to the flow and a flow has one Input node.
 * Only the timestamp is kept, not the listing: after a restart, a file whose modification time is
 * later than this is new, and one that is earlier was already seen — which is enough to avoid the
 * failure this record exists for, a restarted backend firing a run for every file it has ever
 * watched. The listing itself is rebuilt from disk on the first poll, where it is cheap and
 * cannot be stale.
 *
 * @param id             the flow id
 * @param lastSnapshotAt when the folder was last taken stock of, epoch millis
 * @param lastRunId      the run the last batch of changes started, for tracing
 */
public record FolderWatchState(String id, long lastSnapshotAt, String lastRunId) {

    public FolderWatchState withId(String newId) {
        return new FolderWatchState(newId, lastSnapshotAt, lastRunId);
    }
}
