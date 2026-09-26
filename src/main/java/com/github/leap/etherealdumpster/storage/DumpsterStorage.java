package com.github.leap.etherealdumpster.storage;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface DumpsterStorage extends AutoCloseable {
    enum ClaimStatus { SUCCESS, ALREADY_CLAIMED, NOT_FOUND }
    record Claim(ClaimStatus status, Entry entry) { }
    record Stats(long available, long rescued, long pending, long oldest) { }
    void deposit(List<Entry> entries, int maxEntries) throws SQLException;
    List<Entry> sample(int limit) throws SQLException;
    List<Entry> returns(UUID player) throws SQLException;
    Claim reserve(UUID id, UUID player, boolean returned) throws SQLException;
    boolean beginDelivery(UUID id, UUID player) throws SQLException;
    void release(UUID id, UUID player) throws SQLException;
    void complete(UUID id, UUID player) throws SQLException;
    Stats stats() throws SQLException;
    int purge(long before) throws SQLException;
    @Override void close() throws SQLException;
}
