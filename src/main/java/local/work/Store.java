package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A single local owner, with durable records and an external-event inbox. */
final class Store implements AutoCloseable {
    private final Connection connection;
    private long revision;
    synchronized long revision() { return revision; }
    Store(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS records(kind TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
            s.execute("CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL)");
        }
    }
    synchronized ObjectNode get(String kind, String id) {
        try (PreparedStatement p = connection.prepareStatement("SELECT payload FROM records WHERE kind=? AND id=?")) {
            p.setString(1, kind); p.setString(2, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? (ObjectNode) Json.read(r.getString(1)) : null; }
        } catch (SQLException e) { throw new IllegalStateException("저장된 기록을 읽지 못했습니다.", e); }
    }
    synchronized List<ObjectNode> list(String kind) {
        List<ObjectNode> rows = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("SELECT payload FROM records WHERE kind=? ORDER BY rowid")) {
            p.setString(1, kind);
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add((ObjectNode) Json.read(r.getString(1))); }
            return rows;
        } catch (SQLException e) { throw new IllegalStateException("저장된 목록을 읽지 못했습니다.", e); }
    }
    synchronized ObjectNode put(String kind, ObjectNode row) {
        if (Json.text(row, "id").isBlank()) row.put("id", UUID.randomUUID().toString());
        try (PreparedStatement p = connection.prepareStatement("INSERT INTO records(kind,id,payload) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET payload=excluded.payload")) {
            p.setString(1, kind); p.setString(2, row.path("id").asText()); p.setString(3, Json.write(row)); p.executeUpdate();
            revision++;
            return row;
        } catch (SQLException e) { throw new IllegalStateException("기록을 저장하지 못했습니다.", e); }
    }
    synchronized List<ObjectNode> between(String kind, String from, String to) {
        List<ObjectNode> rows = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("SELECT payload FROM records WHERE kind=? AND json_extract(payload,'$.date') BETWEEN ? AND ? ORDER BY rowid")) {
            p.setString(1, kind); p.setString(2, from); p.setString(3, to);
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add((ObjectNode) Json.read(r.getString(1))); }
            return rows;
        } catch (SQLException e) { throw new IllegalStateException("기간별 기록을 읽지 못했습니다.", e); }
    }
    synchronized void delete(String kind, String id) {
        try (PreparedStatement p = connection.prepareStatement("DELETE FROM records WHERE kind=? AND id=?")) {
            p.setString(1, kind); p.setString(2, id); p.executeUpdate();
            revision++;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    synchronized void transaction(Runnable work) {
        try {
            connection.setAutoCommit(false);
            try { work.run(); connection.commit(); }
            catch (RuntimeException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("저장 중 문제가 발생했습니다.", e); }
    }
    synchronized boolean enqueue(String id, ObjectNode payload) {
        try (PreparedStatement p = connection.prepareStatement("INSERT OR IGNORE INTO inbox VALUES(?,?,'pending')")) {
            p.setString(1, id); p.setString(2, Json.write(payload)); return p.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    synchronized List<ObjectNode> pending() {
        List<ObjectNode> result = new ArrayList<>();
        try (Statement p = connection.createStatement(); ResultSet r = p.executeQuery("SELECT id,payload FROM inbox WHERE state='pending' ORDER BY rowid")) {
            while (r.next()) result.add(Json.obj("id", r.getString(1), "payload", Json.read(r.getString(2))));
            return result;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    synchronized void finish(String id) {
        try (PreparedStatement p = connection.prepareStatement("UPDATE inbox SET state='done' WHERE id=?")) { p.setString(1, id); p.executeUpdate(); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
