package com.github.leap.etherealdumpster.storage;

import java.nio.file.Path;
import com.github.leap.etherealdumpster.progression.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** One serialized JDBC connection per instance; conditional writes also arbitrate other connections. */
public final class SQLiteStorage implements DumpsterStorage {
    private final Connection db;

    public SQLiteStorage(Path file) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) { version = r.getInt(1); }
            if (version > 2) throw new SQLException("Unsupported database schema " + version);
            s.execute("""
                CREATE TABLE IF NOT EXISTS dumpster_items (
                  id TEXT PRIMARY KEY, item_data BLOB NOT NULL, depositor_uuid TEXT NOT NULL,
                  created_at INTEGER NOT NULL, state TEXT NOT NULL,
                  sample_key REAL NOT NULL, claimant TEXT, origin TEXT,
                  CHECK(state IN ('AVAILABLE','RETURN_PENDING','RESERVED','DELIVERING','CLAIMED','PURGED')))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_sample ON dumpster_items(state,sample_key,id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_age ON dumpster_items(state,created_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_returns ON dumpster_items(state,depositor_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_rescues ON dumpster_items(claimant,state,origin,depositor_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_contributions ON dumpster_items(depositor_uuid,state,origin,claimant)");
            s.execute("CREATE TABLE IF NOT EXISTS player_profiles (uuid TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT 'Player', active_title TEXT, anonymous INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS player_titles (uuid TEXT NOT NULL, title_id TEXT NOT NULL, notified INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,title_id))");
            s.execute("PRAGMA user_version=2");
        } catch (SQLException e) { db.close(); throw e; }
    }

    /** Only on exclusive plugin startup: delivery has never started for RESERVED rows. */
    public synchronized void recoverReservations() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.executeUpdate("UPDATE dumpster_items SET state=origin, claimant=NULL, origin=NULL WHERE state='RESERVED'");
        }
    }

    @Override public synchronized void deposit(List<Entry> entries, int maxEntries) throws SQLException {
        // BEGIN IMMEDIATE makes capacity check + insert one write transaction across connections.
        try (Statement tx = db.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                long count = scalar("SELECT COUNT(*) FROM dumpster_items WHERE state IN ('AVAILABLE','RESERVED','DELIVERING')");
                for (Entry e : entries) {
                    boolean returned = e.rejected() || (maxEntries > 0 && count >= maxEntries);
                    try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO dumpster_items(id,item_data,depositor_uuid,created_at,state,sample_key) VALUES(?,?,?,?,?,?)")) {
                        p.setString(1, e.id().toString()); p.setBytes(2, e.data());
                        p.setString(3, e.depositor().toString()); p.setLong(4, e.createdAt());
                        p.setString(5, returned ? "RETURN_PENDING" : "AVAILABLE");
                        p.setDouble(6, ThreadLocalRandom.current().nextDouble());
                        if (p.executeUpdate() == 1 && !returned) count++;
                    }
                }
                tx.execute("COMMIT");
            } catch (SQLException | RuntimeException e) {
                try { tx.execute("ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    @Override public synchronized List<Entry> sample(int limit) throws SQLException {
        if (limit < 1 || limit > 45) throw new IllegalArgumentException("Sample limit must be 1..45");
        double pivot = ThreadLocalRandom.current().nextDouble();
        List<Entry> result = new ArrayList<>();
        sampleRange(result, ">=", pivot, limit);
        if (result.size() < limit) sampleRange(result, "<", pivot, limit - result.size());
        Collections.shuffle(result);
        return result;
    }
    private void sampleRange(List<Entry> result, String comparison, double pivot, int limit) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM dumpster_items WHERE state='AVAILABLE' AND sample_key " + comparison + " ? ORDER BY sample_key,id LIMIT ?")) {
            p.setDouble(1, pivot); p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(entry(r)); }
        }
    }
    @Override public synchronized List<Entry> returns(UUID player) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM dumpster_items WHERE state='RETURN_PENDING' AND depositor_uuid=? LIMIT 54")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) {
                List<Entry> entries = new ArrayList<>(); while (r.next()) entries.add(entry(r)); return entries;
            }
        }
    }
    @Override public synchronized Claim reserve(UUID id, UUID player, boolean returned) throws SQLException {
        String source = returned ? "RETURN_PENDING" : "AVAILABLE";
        try (PreparedStatement p = db.prepareStatement("UPDATE dumpster_items SET state='RESERVED',origin=?,claimant=? WHERE id=? AND state=?" + (returned ? " AND depositor_uuid=?" : ""))) {
            p.setString(1, source); p.setString(2, player.toString()); p.setString(3, id.toString()); p.setString(4, source);
            if (returned) p.setString(5, player.toString());
            if (p.executeUpdate() == 0) {
                try (PreparedStatement q = db.prepareStatement("SELECT 1 FROM dumpster_items WHERE id=?")) {
                    q.setString(1, id.toString());
                    try (ResultSet r = q.executeQuery()) { return new Claim(r.next() ? ClaimStatus.ALREADY_CLAIMED : ClaimStatus.NOT_FOUND, null); }
                }
            }
        }
        try (PreparedStatement p = db.prepareStatement("SELECT * FROM dumpster_items WHERE id=?")) {
            p.setString(1, id.toString());
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Reserved entry disappeared: " + id);
                return new Claim(ClaimStatus.SUCCESS, entry(r));
            }
        }
    }
    @Override public synchronized boolean beginDelivery(UUID id, UUID player) throws SQLException {
        return update("UPDATE dumpster_items SET state='DELIVERING' WHERE id=? AND claimant=? AND state='RESERVED'", id, player) == 1;
    }
    @Override public synchronized void release(UUID id, UUID player) throws SQLException {
        update("UPDATE dumpster_items SET state=origin,claimant=NULL,origin=NULL WHERE id=? AND claimant=? AND state IN ('RESERVED','DELIVERING')", id, player);
    }
    @Override public synchronized void complete(UUID id, UUID player) throws SQLException {
        if (update("UPDATE dumpster_items SET state='CLAIMED',item_data=X'' WHERE id=? AND claimant=? AND state='DELIVERING'", id, player) != 1)
            throw new SQLException("Delivery completion rejected: " + id);
    }
    private int update(String sql, UUID id, UUID player) throws SQLException {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            p.setString(1, id.toString()); p.setString(2, player.toString()); return p.executeUpdate();
        }
    }
    @Override public synchronized Stats stats() throws SQLException {
        return new Stats(scalar("SELECT COUNT(*) FROM dumpster_items WHERE state='AVAILABLE'"),
                scalar("SELECT COUNT(*) FROM dumpster_items WHERE state='CLAIMED' AND origin='AVAILABLE'"),
                scalar("SELECT COUNT(*) FROM dumpster_items WHERE state IN ('RETURN_PENDING','RESERVED','DELIVERING')"),
                scalar("SELECT COALESCE(MIN(created_at),0) FROM dumpster_items WHERE state='AVAILABLE'"));
    }
    @Override public synchronized int purge(long before) throws SQLException {
        // Keep IDs as tombstones: replaying a durable deposit journal can never resurrect a purged entry.
        try (PreparedStatement p = db.prepareStatement("UPDATE dumpster_items SET state='PURGED',item_data=X'' WHERE state='AVAILABLE' AND created_at<?")) {
            p.setLong(1, before); return p.executeUpdate();
        }
    }
    public synchronized void rememberPlayer(UUID player, String name) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO player_profiles(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name")) {
            p.setString(1, player.toString()); p.setString(2, name); p.executeUpdate();
        }
    }
    /** Progress comes from authoritative completed claims; no second counter can drift or double-increment. */
    public synchronized Profile profile(UUID player, Milestones catalog) throws SQLException {
        long rescues = playerCount(player, "claimant"), contributions = playerCount(player, "depositor_uuid");
        try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO player_profiles(uuid,name) VALUES(?,'Player')")) {
            p.setString(1, player.toString()); p.executeUpdate();
        }
        for (Milestones.Tier tier : catalog.tiers()) {
            long count = tier.track() == Milestones.Track.RESCUE ? rescues : contributions;
            if (count >= tier.target()) {
                try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO player_titles(uuid,title_id) VALUES(?,?)")) {
                    p.setString(1, player.toString()); p.setString(2, tier.id()); p.executeUpdate();
                }
            }
        }
        Set<String> unlocked = new HashSet<>(), notices = new HashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT title_id,notified FROM player_titles WHERE uuid=?")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) { while (r.next()) { unlocked.add(r.getString(1)); if (r.getInt(2) == 0) notices.add(r.getString(1)); } }
        }
        try (PreparedStatement p = db.prepareStatement("SELECT name,active_title,anonymous FROM player_profiles WHERE uuid=?")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Missing profile " + player);
                return new Profile(player, r.getString(1), rescues, contributions, r.getString(2), r.getInt(3) != 0, unlocked, notices);
            }
        }
    }
    private long playerCount(UUID player, String column) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT COUNT(*) FROM dumpster_items WHERE " + column + "=? AND state='CLAIMED' AND origin='AVAILABLE' AND claimant<>depositor_uuid")) {
            p.setString(1, player.toString()); try (ResultSet r = p.executeQuery()) { return r.getLong(1); }
        }
    }
    public synchronized boolean selectTitle(UUID player, String title) throws SQLException {
        String sql = "UPDATE player_profiles SET active_title=? WHERE uuid=?" +
                (title == null ? "" : " AND EXISTS(SELECT 1 FROM player_titles WHERE uuid=? AND title_id=?)");
        try (PreparedStatement p = db.prepareStatement(sql)) {
            p.setString(1, title); p.setString(2, player.toString());
            if (title != null) { p.setString(3, player.toString()); p.setString(4, title); }
            return p.executeUpdate() == 1;
        }
    }
    public synchronized void anonymous(UUID player, boolean anonymous) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE player_profiles SET anonymous=? WHERE uuid=?")) {
            p.setBoolean(1, anonymous); p.setString(2, player.toString()); p.executeUpdate();
        }
    }
    public synchronized void acknowledgeTitles(UUID player, Set<String> titles) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE player_titles SET notified=1 WHERE uuid=? AND title_id=?")) {
            for (String title : titles) { p.setString(1, player.toString()); p.setString(2, title); p.addBatch(); }
            p.executeBatch();
        }
    }
    private long scalar(String sql) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    private Entry entry(ResultSet r) throws SQLException {
        return new Entry(UUID.fromString(r.getString("id")), r.getBytes("item_data"), UUID.fromString(r.getString("depositor_uuid")), r.getLong("created_at"), false);
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
