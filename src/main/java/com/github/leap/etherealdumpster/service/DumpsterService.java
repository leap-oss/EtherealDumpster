package com.github.leap.etherealdumpster.service;

import com.github.leap.etherealdumpster.EtherealDumpster;
import com.github.leap.etherealdumpster.storage.*;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
import com.github.leap.etherealdumpster.progression.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** JDBC runs on one worker. All player/inventory callbacks run through the server scheduler. */
public final class DumpsterService {
    private final EtherealDumpster plugin;
    private final SQLiteStorage storage;
    private final DepositJournal journal;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "EtherealDumpster-storage"));
    private final Set<UUID> claiming = new HashSet<>(); // server thread only
    private volatile boolean stopping;

    public DumpsterService(EtherealDumpster plugin, SQLiteStorage storage, DepositJournal journal) {
        this.plugin = plugin; this.storage = storage; this.journal = journal;
    }
    public boolean stopping() { return stopping; }
    public <T> void query(Callable<T> action, Consumer<T> success, Runnable failure) {
        if (stopping) { failure.run(); return; }
        worker.execute(() -> {
            try { T result = action.call(); main(() -> success.accept(result)); }
            catch (Exception e) { log("Storage operation failed; durable state retained", e); main(failure); }
        });
    }
    private void main(Runnable action) {
        if (stopping) return;
        try { plugin.getServer().getScheduler().runTask(plugin, () -> { if (!stopping) action.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* shutdown recovery owns pending state */ }
    }
    public void sample(Consumer<List<Entry>> result, Runnable failed) {
        int limit = plugin.settings().displayed(); query(() -> storage.sample(limit), result, failed);
    }
    public void publish(DepositJournal.Batch batch, Player player) {
        int max = plugin.settings().maxEntries();
        query(() -> { storage.deposit(batch.entries(), max); journal.complete(batch); return true; }, ignored -> {
            if (player.isOnline()) { plugin.message(player, "deposit-success"); recover(player); }
        }, () -> { if (player.isOnline()) plugin.message(player, "deposit-pending"); });
    }
    public void retryJournals() {
        int max = plugin.settings().maxEntries();
        query(() -> {
            for (var batch : journal.recoverable()) { storage.deposit(batch.entries(), max); journal.complete(batch); }
            int days = plugin.settings().expireDays();
            return days == 0 ? 0 : storage.purge(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
        }, count -> { if (count > 0) plugin.getLogger().info("Expired " + count + " available entries."); }, () -> {});
    }
    public void stats(Consumer<DumpsterStorage.Stats> result, Runnable failure) { query(storage::stats, result, failure); }
    public void purge(long before, Consumer<Integer> result, Runnable failure) { query(() -> storage.purge(before), result, failure); }
    public void profile(UUID owner, Consumer<Profile> result, Runnable failure) {
        Milestones catalog = plugin.milestones();
        query(() -> storage.profile(owner, catalog), result, failure);
    }
    public void selectTitle(Player player, String id, Consumer<Profile> result, Runnable failure) {
        UUID owner = player.getUniqueId(); Milestones catalog = plugin.milestones();
        query(() -> {
            storage.profile(owner, catalog);
            if (id != null && catalog.find(id).isEmpty()) throw new IllegalArgumentException("Unknown title");
            if (!storage.selectTitle(owner, id)) throw new IllegalArgumentException("Title is locked");
            return storage.profile(owner, catalog);
        }, result, () -> { plugin.message(player, "storage-error"); failure.run(); });
    }
    public void anonymous(Player player, boolean value, Consumer<Profile> result, Runnable failure) {
        UUID owner = player.getUniqueId(); Milestones catalog = plugin.milestones();
        query(() -> { storage.anonymous(owner, value); return storage.profile(owner, catalog); }, result,
                () -> { plugin.message(player, "storage-error"); failure.run(); });
    }
    public void checkMilestones(Player player) {
        UUID owner = player.getUniqueId(); String name = player.getName(); Milestones catalog = plugin.milestones();
        query(() -> { storage.rememberPlayer(owner, name); return storage.profile(owner, catalog); }, this::notifyUnlocks, () -> {});
    }
    private void updateMilestones(UUID owner) { profile(owner, this::notifyUnlocks, () -> {}); }
    private final Set<String> announcing = new HashSet<>(); // server thread only
    private void notifyUnlocks(Profile profile) {
        Player player = plugin.getServer().getPlayer(profile.player());
        if (player == null || !player.isOnline()) return;
        Set<String> announced = new HashSet<>();
        for (Milestones.Tier tier : plugin.milestones().tiers()) {
            if (profile.notices().contains(tier.id()) && announcing.add(profile.player() + ":" + tier.id())) {
                plugin.message(player, "title-unlocked", "{title}", tier.title()); announced.add(tier.id());
            }
        }
        if (announced.isEmpty()) return;
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        query(() -> { storage.acknowledgeTitles(profile.player(), announced); return true; }, ignored -> {
            announced.forEach(id -> announcing.remove(profile.player() + ":" + id));
        }, () -> announced.forEach(id -> announcing.remove(profile.player() + ":" + id)));
    }
    public void recover(Player player) {
        UUID owner = player.getUniqueId();
        if (claiming.contains(owner)) return;
        query(() -> storage.returns(owner), entries -> {
            if (player.isOnline() && !entries.isEmpty()) claim(player, entries.getFirst().id(), true, () -> {});
        }, () -> { if (player.isOnline()) plugin.message(player, "storage-error"); });
    }
    public void claim(Player player, UUID id, boolean returned, Runnable removeView) {
        claim(player, id, returned, removeView, ignored -> {});
    }
    public void claim(Player player, UUID id, boolean returned, Runnable removeView, Consumer<UUID> rescued) {
        UUID owner = player.getUniqueId();
        if (!claiming.add(owner)) return;
        query(() -> storage.reserve(id, owner, returned), claim -> {
            if (claim.status() != DumpsterStorage.ClaimStatus.SUCCESS) {
                claiming.remove(owner); removeView.run(); plugin.message(player, "already-claimed"); return;
            }
            ItemStack item;
            try { item = ItemStack.deserializeBytes(claim.entry().data()); }
            catch (Exception e) {
                // Keep the reservation quarantined for this runtime; log the exact entry for repair.
                claiming.remove(owner); log("Cannot deserialize reserved entry " + id, e); plugin.message(player, "storage-error"); return;
            }
            if (!player.isOnline() || !fits(player, item)) { release(player, id); return; }
            query(() -> storage.beginDelivery(id, owner), begun -> {
                if (!begun) { claiming.remove(owner); plugin.message(player, "storage-error"); return; }
                if (!player.isOnline() || !fits(player, item)) { release(player, id); return; }
                try {
                    // No asynchronous boundary between the capacity check and inventory mutation.
                    var leftovers = player.getInventory().addItem(item.clone());
                    if (!leftovers.isEmpty()) throw new IllegalStateException("Unexpected partial delivery");
                    player.saveData();
                    removeView.run();
                } catch (Exception e) {
                    // Never requeue after inventory mutation: even a partial give would duplicate items.
                    claiming.remove(owner); log("Uncertain delivery " + id + " to " + owner + "; DELIVERING retained for admin review", e);
                    plugin.message(player, "delivery-pending"); return;
                }
                query(() -> { storage.complete(id, owner); return true; }, ignored -> {
                    claiming.remove(owner); plugin.message(player, returned ? "returned" : "claim-success");
                    if (returned && player.isOnline()) recover(player);
                    if (!returned) {
                        updateMilestones(owner); updateMilestones(claim.entry().depositor());
                        if (player.isOnline()) rescued.accept(claim.entry().depositor());
                    }
                }, () -> { claiming.remove(owner); plugin.message(player, "delivery-pending"); });
            }, () -> { claiming.remove(owner); plugin.message(player, "delivery-pending"); });
        }, () -> { claiming.remove(owner); plugin.message(player, "storage-error"); });
    }
    private void release(Player player, UUID id) {
        UUID owner = player.getUniqueId();
        query(() -> { storage.release(id, owner); return true; }, ignored -> {
            claiming.remove(player.getUniqueId()); if (player.isOnline()) plugin.message(player, "inventory-full");
        }, () -> { claiming.remove(player.getUniqueId()); plugin.message(player, "storage-error"); });
    }
    static boolean fits(Player player, ItemStack stack) {
        int remaining = stack.getAmount();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            int maximum = Math.min(stack.getMaxStackSize(), player.getInventory().getMaxStackSize());
            if (slot == null || slot.getType().isAir()) remaining -= maximum;
            else if (slot.isSimilar(stack)) remaining -= Math.max(0, maximum - slot.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }
    public void close() {
        stopping = true;
        worker.execute(() -> { try { storage.close(); } catch (Exception e) { log("Cannot close SQLite", e); } });
        worker.shutdown();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().severe("Storage worker still draining; journals and delivery states retained."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void log(String message, Exception e) { plugin.getLogger().log(Level.SEVERE, message, e); }
}
