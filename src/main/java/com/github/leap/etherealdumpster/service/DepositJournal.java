package com.github.leap.etherealdumpster.service;

import com.github.leap.etherealdumpster.storage.Entry;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** PREPARED is quarantined after restart; READY is safe to replay using stable entry IDs. */
public final class DepositJournal {
    public record Batch(UUID id, List<Entry> entries) { }
    private final Path directory;
    public DepositJournal(Path directory) throws IOException { this.directory = directory; Files.createDirectories(directory); }
    public Batch prepare(List<Entry> entries) throws IOException {
        Batch batch = new Batch(UUID.randomUUID(), List.copyOf(entries));
        Path path = path(batch.id(), "prepared");
        try (FileOutputStream file = new FileOutputStream(path.toFile()); DataOutputStream out = new DataOutputStream(file)) {
            out.writeInt(1); out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeUTF(e.id().toString()); out.writeUTF(e.depositor().toString());
                out.writeLong(e.createdAt()); out.writeBoolean(e.rejected());
                byte[] data = e.data(); out.writeInt(data.length); out.write(data);
            }
            out.flush(); file.getFD().sync();
        }
        syncDirectory();
        return batch;
    }
    public void ready(Batch batch) throws IOException {
        Files.move(path(batch.id(), "prepared"), path(batch.id(), "ready"), StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
    }
    public void complete(Batch batch) throws IOException {
        Files.deleteIfExists(path(batch.id(), "ready")); syncDirectory();
    }
    public List<Path> prepared() throws IOException { return files(".prepared"); }
    public List<Batch> recoverable() throws IOException {
        List<Batch> result = new ArrayList<>();
        for (Path file : files(".ready")) {
            UUID id = UUID.fromString(file.getFileName().toString().replace(".ready", ""));
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != 1) throw new IOException("Unknown journal format: " + file);
                int count = in.readInt();
                if (count < 1 || count > 54) throw new IOException("Invalid journal stack count: " + file);
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    UUID entryId = UUID.fromString(in.readUTF()), player = UUID.fromString(in.readUTF());
                    long created = in.readLong(); boolean rejected = in.readBoolean(); int size = in.readInt();
                    if (size < 1 || size > 16 * 1024 * 1024) throw new IOException("Invalid item size: " + file);
                    byte[] data = in.readNBytes(size);
                    if (data.length != size) throw new EOFException("Truncated journal: " + file);
                    entries.add(new Entry(entryId, data, player, created, rejected));
                }
                result.add(new Batch(id, List.copyOf(entries)));
            }
        }
        return result;
    }
    private List<Path> files(String suffix) throws IOException {
        try (var stream = Files.list(directory)) { return stream.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList(); }
    }
    private Path path(UUID id, String phase) { return directory.resolve(id + "." + phase); }
    private void syncDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
