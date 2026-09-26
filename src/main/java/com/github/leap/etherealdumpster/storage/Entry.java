package com.github.leap.etherealdumpster.storage;

import java.util.UUID;

/** Immutable serialized stack. No Bukkit objects cross the storage boundary. */
public record Entry(UUID id, byte[] data, UUID depositor, long createdAt, boolean rejected) {
    public Entry { data = data.clone(); }
    @Override public byte[] data() { return data.clone(); }
}
