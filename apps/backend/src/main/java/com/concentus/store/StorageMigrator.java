package com.concentus.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moving everything this installation holds into another PostgreSQL.
 *
 * <p>Pointing the application at a company database already worked; arriving there with an empty
 * one is what did not. A team that has been building flows for weeks is not going to retype them
 * because the storage setting changed, and telling them to run {@code pg_dump} makes the move a
 * database administrator's task rather than a button — on a desktop install, where the source
 * database is embedded inside the application, it is not even a task they can perform.
 *
 * <p>Table by table, over JDBC, rather than by dumping: the schema belongs to this application, so
 * the target is brought up to date with the same migrations everything else runs and the copy then
 * moves rows between two connections. No external tool, no version matching between a dump and a
 * server, and it works from an embedded database whose binaries the user never sees.
 *
 * <p><b>Nothing is ever deleted.</b> Rows are inserted with {@code on conflict do nothing}, so a
 * run that fails halfway can simply be repeated, and pointing at a database that already holds
 * some of this data merges instead of destroying it. A migration that could empty a company's
 * database on a mistyped URL would be a worse feature than no migration at all.
 */
public final class StorageMigrator {

    private static final Logger log = LoggerFactory.getLogger(StorageMigrator.class);

    /** Flyway's own bookkeeping: the target gets its own, written by its own migration run. */
    private static final String HISTORY = "flyway_schema_history";

    /**
     * Tables nobody wants carried to a new database.
     *
     * <p>Remember-me tokens name a browser and a machine, and a cookie minted against one
     * deployment means nothing to another — copying them would leave rows that can only ever be
     * dead. Everything else travels, because "what I have" means what the person built.
     */
    private static final Set<String> NEVER_COPIED = Set.of(HISTORY, "persistent_logins");

    /** How many rows are sent per round trip. Large enough to be quick, small enough to stream. */
    private static final int BATCH = 500;

    private StorageMigrator() {
    }

    /** What one table would contribute. */
    public record TableCount(String table, long rows) {
    }

    /** What a copy did, per table, plus anything it could not do. */
    public record Report(List<TableCount> copied, List<String> warnings, long totalRows) {
    }

    /**
     * What is here to move, biggest first.
     *
     * <p>Ordered by size because the two questions somebody asks before pressing the button are
     * "will this take a minute or an hour" and "what is all that", and both are answered by the
     * top of this list — run history and knowledge chunks are usually most of it.
     */
    public static List<TableCount> survey(DataSource source) {
        List<TableCount> counts = new ArrayList<>();
        try (Connection connection = source.getConnection()) {
            for (String table : tablesIn(connection)) {
                if (NEVER_COPIED.contains(table)) continue;
                counts.add(new TableCount(table, countRows(connection, table)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("This installation's database could not be read: "
                    + e.getMessage(), e);
        }
        counts.sort((a, b) -> Long.compare(b.rows(), a.rows()));
        return counts;
    }

    /**
     * Brings the target's schema up to date and copies every row this installation holds into it.
     *
     * @param skip tables to leave behind — the heavy ones, for somebody who wants their
     *             configuration on the company database without waiting for a year of run history
     *             to cross a VPN
     */
    public static Report copy(DataSource source, DataSource target, Set<String> skip) {
        // The same migrations everything else runs: the schema is this application's, so the target
        // is made to match rather than being expected to already.
        if (!SchemaMigrator.migrate(target)) {
            throw new IllegalStateException("The target database could not be prepared. It must be "
                    + "PostgreSQL, reachable, and the account must be allowed to create tables.");
        }

        List<TableCount> copied = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long total = 0;
        try (Connection from = source.getConnection(); Connection to = target.getConnection()) {
            Set<String> here = tablesIn(from);
            Set<String> there = tablesIn(to);
            for (String table : here) {
                if (NEVER_COPIED.contains(table)) continue;
                if (skip != null && skip.contains(table)) {
                    warnings.add("Skipped " + table + " because it was not selected.");
                    continue;
                }
                if (!there.contains(table)) {
                    // A table this build knows and the target's schema does not can only mean the
                    // two are on different versions, which is worth a sentence rather than silence.
                    warnings.add("Skipped " + table + ": the target database has no such table.");
                    continue;
                }
                long rows = copyTable(from, to, table, warnings);
                copied.add(new TableCount(table, rows));
                total += rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The copy stopped: " + e.getMessage()
                    + ". Nothing was deleted; repeating it will continue where it left off.", e);
        }
        copied.sort((a, b) -> Long.compare(b.rows(), a.rows()));
        return new Report(copied, warnings, total);
    }

    /**
     * One table, in batches.
     *
     * <p>Columns are the intersection of the two schemas, so a source that predates a migration —
     * or a target ahead of it — copies what both understand instead of failing on a column name.
     */
    private static long copyTable(Connection from, Connection to, String table,
                                  List<String> warnings) throws SQLException {
        List<String> columns = new ArrayList<>(columnsOf(from, table));
        columns.retainAll(columnsOf(to, table));
        if (columns.isEmpty()) {
            warnings.add("Skipped " + table + ": no columns in common with the target.");
            return 0;
        }

        String columnList = String.join(", ", columns.stream().map(c -> '"' + c + '"').toList());
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        String insert = "insert into \"" + table + "\" (" + columnList + ") values ("
                + placeholders + ") on conflict do nothing";

        long inserted = 0;
        boolean autoCommit = to.getAutoCommit();
        to.setAutoCommit(false);
        try (Statement read = from.createStatement();
             PreparedStatement write = to.prepareStatement(insert)) {
            // Streamed rather than materialised: a knowledge base with a hundred thousand chunks
            // must not have to fit in this process's heap on its way through.
            read.setFetchSize(BATCH);
            try (ResultSet rows = read.executeQuery("select " + columnList + " from \"" + table + "\"")) {
                int inBatch = 0;
                while (rows.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        write.setObject(i, rows.getObject(i));
                    }
                    write.addBatch();
                    if (++inBatch >= BATCH) {
                        inserted += countInserted(write.executeBatch());
                        to.commit();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    inserted += countInserted(write.executeBatch());
                    to.commit();
                }
            }
        } finally {
            to.setAutoCommit(autoCommit);
        }
        log.info("Copied {} rows into {}", inserted, table);
        return inserted;
    }

    /**
     * Rows the target actually took.
     *
     * <p>A row already there counts as 0, because {@code on conflict do nothing} is what makes
     * repeating a half-finished migration safe — and reporting skipped rows as copied would tell
     * somebody their second attempt moved everything twice.
     */
    private static long countInserted(int[] results) {
        long n = 0;
        for (int result : results) {
            if (result > 0) n += result;
            else if (result == Statement.SUCCESS_NO_INFO) n++;
        }
        return n;
    }

    private static Set<String> tablesIn(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), "public", "%",
                new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        return tables;
    }

    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), "public",
                table, "%")) {
            while (rs.next()) columns.add(rs.getString("COLUMN_NAME"));
        }
        if (!columns.isEmpty()) return columns;
        // Some drivers answer nothing for metadata on an empty catalog; asking the table itself
        // always works and costs one round trip.
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select * from \"" + table + "\" where false")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
        }
        return columns;
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from \"" + table + "\"")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** The survey as a map, for a caller that wants to look one table up rather than list them. */
    public static Map<String, Long> asMap(List<TableCount> counts) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (TableCount c : counts) out.put(c.table(), c.rows());
        return out;
    }
}
