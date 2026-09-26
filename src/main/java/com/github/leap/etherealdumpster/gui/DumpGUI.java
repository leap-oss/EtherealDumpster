package com.github.leap.etherealdumpster.gui;

import com.github.leap.etherealdumpster.EtherealDumpster;
import com.github.leap.etherealdumpster.service.DepositJournal;
import com.github.leap.etherealdumpster.storage.Entry;
import com.github.leap.etherealdumpster.progression.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.logging.Level;

/** Session identity is an InventoryHolder, never a spoofable title. State lives on the server thread. */
public final class DumpGUI implements Listener {
    private static final int DEPOSIT_SLOTS = 53;
    private static final int BACK_SLOT = 53;
    private enum Kind {
        MAIN(27), DEPOSIT(54), DIVE(54), STATS(27), PROFILE(27), PUBLIC_PROFILE(27), MILESTONES(54), TITLES(54);
        final int size;
        Kind(int size) { this.size = size; }
        int backSlot() { return size - 1; }
    }
    private static final class Menu implements InventoryHolder {
        final UUID owner;
        final Kind kind;
        final Inventory inventory;
        final Map<Integer, UUID> entries = new HashMap<>();
        final Map<Integer, String> titles = new HashMap<>();
        UUID depositor;
        Profile profile;
        Milestones catalog;
        boolean finalized, loading;
        Menu(Player player, Kind kind) {
            this.owner = player.getUniqueId(); this.kind = kind;
            inventory = Bukkit.createInventory(this, kind.size, switch (kind) {
                case MAIN -> "Ethereal Dumpster"; case DEPOSIT -> "Deposit • close to discard"; case DIVE -> "Dumpster Dive"; case STATS -> "Dumpster Statistics";
                case PROFILE -> "Your Profile"; case PUBLIC_PROFILE -> "Depositor Profile";
                case MILESTONES -> "Dumpster Milestones"; case TITLES -> "Title Collection";
            });
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final EtherealDumpster plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Menu> deposits = new HashMap<>();
    // If even the journal disk is unavailable, preserve the live inventory and retry; never clear it.
    private final Map<Menu, Player> held = new HashMap<>();
    public DumpGUI(EtherealDumpster plugin) { this.plugin = plugin; }
    public void open(Player player) {
        if (!allowed(player, "use")) return;
        Menu menu = new Menu(player, Kind.MAIN);
        decorateCompact(menu);
        menu.inventory.setItem(4, item(Material.ENDER_CHEST, "§b§lETHEREAL DUMPSTER",
                "§7One player's trash…", "§7…is another player's treasure."));
        menu.inventory.setItem(11, item(Material.HOPPER, "§a§lDeposit Items",
                "§7Make room for something new.", "§7Leave your unwanted items here.", "", "§aClick to deposit →"));
        menu.inventory.setItem(13, item(Material.ENDER_EYE, "§b§lDumpster Dive",
                "§7You never know what you'll find.", "§7Discover and rescue discarded items.", "", "§bClick to explore →"));
        menu.inventory.setItem(15, item(Material.PAPER, "§e§lStatistics",
                "§7See what's waiting in the dumpster", "§7and how much has been rescued.", "", "§eClick to view →"));
        menu.inventory.setItem(18, item(Material.PLAYER_HEAD, "§d§lYour Profile", "§7Progress, milestones, and your title collection.", "", "§dClick to open →"));
        menu.inventory.setItem(22, item(Material.BARRIER, "§cClose", "§7See you on your next dive."));
        player.openInventory(menu.inventory);
    }
    public void dive(Player player) {
        if (!allowed(player, "dive")) return;
        Menu menu = new Menu(player, Kind.DIVE);
        menu.inventory.setItem(49, item(Material.SUNFLOWER, "§aRefresh"));
        menu.inventory.setItem(53, item(Material.ARROW, "§eBack to Dumpster"));
        player.openInventory(menu.inventory); refresh(player, menu);
    }
    private void deposit(Player player) {
        if (!allowed(player, "deposit")) return;
        if (held.values().stream().anyMatch(p -> p.getUniqueId().equals(player.getUniqueId()))) { plugin.message(player, "deposit-pending"); return; }
        Menu menu = new Menu(player, Kind.DEPOSIT);
        menu.inventory.setItem(BACK_SLOT, item(Material.ARROW, "§eBack to Dumpster", "§7Deposits your items and returns to the main menu.", "§7Press Esc to deposit and close instead."));
        player.openInventory(menu.inventory); deposits.put(player.getUniqueId(), menu);
    }
    private boolean allowed(Player player, String permission) {
        if (plugin.service().stopping()) return false;
        if (!player.hasPermission("etherealdumpster.use") || !player.hasPermission("etherealdumpster." + permission)) {
            plugin.message(player, "no-permission"); return false;
        }
        return true;
    }
    private void refresh(Player player, Menu menu) {
        if (menu.loading || !allowed(player, "dive")) return;
        long now = System.nanoTime(), next = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < next) { plugin.message(player, "cooldown", "{seconds}", Long.toString((next - now + 999_999_999L) / 1_000_000_000L)); return; }
        cooldowns.put(player.getUniqueId(), now + plugin.settings().cooldown() * 1_000_000_000L);
        menu.loading = true;
        plugin.service().sample(entries -> {
            menu.loading = false;
            if (!viewing(player, menu)) return;
            for (int i = 0; i < 45; i++) menu.inventory.setItem(i, null);
            menu.entries.clear(); int slot = 0;
            for (Entry entry : entries) {
                try {
                    menu.inventory.setItem(slot, ItemStack.deserializeBytes(entry.data())); menu.entries.put(slot++, entry.id());
                } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Cannot decode entry " + entry.id(), e); }
            }
            if (menu.entries.isEmpty()) menu.inventory.setItem(22, item(Material.GRAY_STAINED_GLASS_PANE, plugin.text("empty")));
        }, () -> { menu.loading = false; if (viewing(player, menu)) plugin.message(player, "storage-error"); });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }
        if (!menu.owner.equals(player.getUniqueId()) || menu.finalized) { event.setCancelled(true); return; }
        if (menu.kind == Kind.DEPOSIT) {
            if (event.getRawSlot() == BACK_SLOT) {
                event.setCancelled(true);
                if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
                    Bukkit.getScheduler().runTask(plugin, () -> { if (viewing(player, menu)) open(player); });
                }
                return;
            }
            if (event instanceof InventoryCreativeEvent || event.getAction() == InventoryAction.CLONE_STACK
                    || event.getAction() == InventoryAction.COLLECT_TO_CURSOR || !allowed(player, "deposit")) {
                event.setCancelled(true);
                return;
            }
            if (event.getRawSlot() >= menu.kind.size && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Vanilla shift-transfer could merge with the control if an item has identical metadata.
                event.setCancelled(true);
                ItemStack source = event.getCurrentItem();
                if (source != null && !source.getType().isAir() && event.getClickedInventory() != null) {
                    ItemStack remaining = moveIntoDeposit(menu.inventory, source);
                    event.getClickedInventory().setItem(event.getSlot(), remaining);
                }
            }
            return;
        }
        event.setCancelled(true); // Includes bottom inventory, number keys, offhand, double-click and shift-click.
        if (event.getRawSlot() < 0 || event.getRawSlot() >= menu.kind.size || !event.isLeftClick() || event.isShiftClick()) return;
        int slot = event.getRawSlot();
        if (menu.kind == Kind.MAIN) {
            // Inventory transitions must happen outside the InventoryClickEvent transaction.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewing(player, menu)) return;
                switch (slot) {
                    case 11 -> deposit(player); case 13 -> dive(player); case 15 -> openStatistics(player); case 18 -> profile(player); case 22 -> player.closeInventory();
                    default -> { }
                }
            });
        } else if (slot == menu.kind.backSlot()) Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewing(player, menu)) return;
            if (menu.kind == Kind.MILESTONES || menu.kind == Kind.TITLES) profile(player); else open(player);
        });
        else if (menu.kind == Kind.PROFILE || menu.kind == Kind.TITLES || menu.kind == Kind.MILESTONES || menu.kind == Kind.PUBLIC_PROFILE) {
            if (menu.loading || menu.profile == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> { if (viewing(player, menu)) profileClick(player, menu, slot); });
        }
        else if (menu.kind == Kind.DIVE && slot == 48 && menu.depositor != null) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (viewing(player, menu)) openProfile(player, menu.depositor, Kind.PUBLIC_PROFILE); });
        }
        else if (menu.kind == Kind.DIVE && slot == 49) refresh(player, menu);
        else if (menu.kind == Kind.DIVE && !menu.loading && menu.entries.containsKey(slot) && allowed(player, "dive")) {
            UUID id = menu.entries.get(slot);
            plugin.service().claim(player, id, false, () -> {
                if (Objects.equals(menu.entries.get(slot), id)) { menu.entries.remove(slot); menu.inventory.setItem(slot, null); }
            }, depositor -> {
                if (!viewing(player, menu)) return;
                menu.depositor = depositor;
                menu.inventory.setItem(48, item(Material.PLAYER_HEAD, "§dDepositor Profile", "§7View the depositor of the last item", "§7you successfully rescued."));
            });
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (menu.kind != Kind.DEPOSIT || menu.finalized || event.getRawSlots().contains(BACK_SLOT)
                || !menu.owner.equals(event.getWhoClicked().getUniqueId()) || !event.getWhoClicked().hasPermission("etherealdumpster.use")
                || !event.getWhoClicked().hasPermission("etherealdumpster.deposit")) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && menu.kind == Kind.DEPOSIT && event.getPlayer() instanceof Player player)
            finalizeDeposit(player, menu);
    }
    private void finalizeDeposit(Player player, Menu menu) {
        if (menu.finalized) return;
        menu.finalized = true; deposits.remove(menu.owner, menu);
        List<Entry> entries = new ArrayList<>();
        DepositJournal.Batch batch;
        try {
            for (int slot = 0; slot < DEPOSIT_SLOTS; slot++) {
                ItemStack stack = menu.inventory.getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;
                entries.add(new Entry(UUID.randomUUID(), stack.serializeAsBytes(), menu.owner, System.currentTimeMillis(),
                        plugin.settings().blacklist().contains(stack.getType()) || !player.hasPermission("etherealdumpster.deposit")));
            }
            if (entries.isEmpty()) { held.remove(menu); return; }
            batch = plugin.journal().prepare(entries);
        } catch (Exception e) {
            menu.finalized = false; held.put(menu, player);
            plugin.getLogger().log(Level.SEVERE, "Deposit inventory retained in memory for " + menu.owner + "; journal unavailable", e);
            plugin.message(player, "deposit-pending"); return;
        }
        held.remove(menu);
        menu.inventory.clear();
        try {
            player.saveData(); // Persist the inventory after custody has left the player, before publication.
            plugin.journal().ready(batch);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Uncertain deposit " + batch.id() + "; journal retained for admin review", e);
            plugin.message(player, "deposit-pending"); return;
        }
        plugin.service().publish(batch, player);
    }
    public void retryHeld() {
        new HashMap<>(held).forEach((menu, previous) -> {
            Player current = Bukkit.getPlayer(menu.owner);
            // Never save an obsolete offline Player instance over a new login's inventory.
            if (current != null) finalizeDeposit(current, menu);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Menu menu = deposits.get(event.getPlayer().getUniqueId());
        if (menu != null) finalizeDeposit(event.getPlayer(), menu);
        // Cooldown intentionally survives reconnects until its deadline.
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.service().recover(event.getPlayer()); plugin.service().checkMilestones(event.getPlayer());
        });
    }
    public void maintenance() { cooldowns.entrySet().removeIf(e -> e.getValue() <= System.nanoTime()); retryHeld(); }
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
        }
        retryHeld();
        if (!held.isEmpty()) plugin.getLogger().severe("UNRESOLVED: " + held.size() + " deposits remain in memory because journal writes failed. Do not discard server logs/backups.");
    }
    public void profile(Player player) {
        if (!allowed(player, "use")) return;
        plugin.service().checkMilestones(player);
        openProfile(player, player.getUniqueId(), Kind.PROFILE);
    }
    private void openProfile(Player player, UUID target, Kind kind) {
        if (!allowed(player, "use")) return;
        Menu menu = new Menu(player, kind); menu.catalog = plugin.milestones(); menu.loading = true;
        decorateCompact(menu);
        menu.inventory.setItem(13, item(Material.CLOCK, "§eLoading profile…"));
        menu.inventory.setItem(kind.backSlot(), item(Material.ARROW, "§eBack"));
        player.openInventory(menu.inventory);
        plugin.service().profile(target, profile -> {
            if (viewing(player, menu)) renderProfile(menu, profile);
        }, () -> {
            if (!viewing(player, menu)) return;
            menu.loading = false;
            menu.inventory.setItem(13, item(Material.BARRIER, "§cProfile unavailable", "§7Go back and try again."));
        });
    }
    private void renderProfile(Menu menu, Profile profile) {
        menu.profile = profile; menu.loading = false; menu.titles.clear(); menu.inventory.clear(); decorateCompact(menu);
        menu.inventory.setItem(menu.kind.backSlot(), item(Material.ARROW, "§eBack"));
        if (menu.kind == Kind.PUBLIC_PROFILE && profile.anonymous() && !menu.owner.equals(profile.player())) {
            menu.inventory.setItem(13, item(Material.CARVED_PUMPKIN, "§7Anonymous Depositor", "§7This player has chosen to hide their profile.")); return;
        }
        String active = menu.catalog.find(profile.activeTitle()).filter(t -> profile.unlocked().contains(t.id())).map(Milestones.Tier::title).orElse("No Title");
        menu.inventory.setItem(4, item(Material.PLAYER_HEAD, "§d§l" + profile.name(), "§e✦ " + active));
        if (menu.kind == Kind.PROFILE || menu.kind == Kind.PUBLIC_PROFILE) {
            menu.inventory.setItem(11, item(Material.DIAMOND, "§bPersonal Statistics", "§f" + profile.rescues() + " successful rescues from other players",
                    "§f" + profile.contributions() + " deposits rescued by other players", "§7Claiming your own deposits does not count."));
            menu.inventory.setItem(13, item(Material.NAME_TAG, "§eActive Title", "§f" + active,
                    "§7" + profile.unlocked().size() + " / 20 titles unlocked", menu.kind == Kind.PROFILE ? "§eClick to choose a title →" : "§7This player's selected title."));
            List<String> lore = new ArrayList<>();
            for (Milestones.Track track : Milestones.Track.values()) {
                lore.add(track == Milestones.Track.RESCUE ? "§bRescue Track" : "§aContribution Track");
                menu.catalog.next(profile, track).ifPresentOrElse(t -> {
                    lore.add("§f" + t.title()); lore.add("§7" + t.progress(profile) + " / " + t.target() + " actions");
                    lore.add("§e" + Math.max(0, t.target() - t.progress(profile)) + " more to go!");
                }, () -> lore.add("§aAll milestones completed!"));
                lore.add("");
            }
            if (menu.kind == Kind.PROFILE) lore.add("§eClick to view all milestones →");
            menu.inventory.setItem(15, item(Material.EXPERIENCE_BOTTLE, "§aNext Milestones", lore.toArray(String[]::new)));
            if (menu.kind == Kind.PROFILE) menu.inventory.setItem(22, item(profile.anonymous() ? Material.CARVED_PUMPKIN : Material.ENDER_EYE,
                    "§dProfile: " + (profile.anonymous() ? "Anonymous" : "Public"), "§7Choose what other players can see.", "§eClick to toggle."));
            return;
        }
        int[] slots = {10,11,12,13,14,19,20,21,22,23,28,29,30,31,32,37,38,39,40,41};
        menu.inventory.setItem(4, item(Material.BOOK, menu.kind == Kind.TITLES ? "§e§lTITLE COLLECTION" : "§a§lMILESTONES",
                "§bTop two rows: rescues", "§aBottom two rows: contributions", "§7Cumulative progress • never resets"));
        for (int i = 0; i < menu.catalog.tiers().size(); i++) {
            Milestones.Tier tier = menu.catalog.tiers().get(i);
            boolean unlocked = profile.unlocked().contains(tier.id()), selected = tier.id().equals(profile.activeTitle());
            long current = tier.progress(profile); int filled = (int) Math.min(10, current * 10 / tier.target());
            String bar = "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
            menu.inventory.setItem(slots[i], item(selected ? Material.NETHER_STAR : unlocked ? Material.NAME_TAG : Material.GRAY_DYE,
                    (unlocked ? "§e" : "§7") + tier.title(), tier.track() == Milestones.Track.RESCUE ? "§bRescue other players' deposits" : "§aHave your deposits rescued by others",
                    "§f" + current + " / " + tier.target() + " actions", bar,
                    selected ? "§a✓ Active" : unlocked ? "§aUnlocked" : "§7Locked • " + Math.max(0, tier.target() - current) + " more to go",
                    menu.kind == Kind.TITLES && unlocked ? "§eClick to equip →" : "§7Unlocked titles stay in your collection."));
            if (menu.kind == Kind.TITLES && unlocked) menu.titles.put(slots[i], tier.id());
        }
        if (menu.kind == Kind.TITLES) menu.inventory.setItem(49, item(Material.BARRIER, "§7No Title", "§7Click to remove your active title."));
    }
    private void profileClick(Player player, Menu menu, int slot) {
        if (!allowed(player, "use")) return;
        if (menu.kind == Kind.PROFILE) {
            if (slot == 13) openProfile(player, player.getUniqueId(), Kind.TITLES);
            else if (slot == 15) openProfile(player, player.getUniqueId(), Kind.MILESTONES);
            else if (slot == 22) {
                menu.loading = true;
                plugin.service().anonymous(player, !menu.profile.anonymous(), updated -> { if (viewing(player, menu)) renderProfile(menu, updated); }, () -> menu.loading = false);
            }
        } else if (menu.kind == Kind.TITLES && (slot == 49 || menu.titles.containsKey(slot))) {
            menu.loading = true;
            plugin.service().selectTitle(player, slot == 49 ? null : menu.titles.get(slot), updated -> { if (viewing(player, menu)) renderProfile(menu, updated); }, () -> menu.loading = false);
        }
    }
    private static void decorateCompact(Menu menu) {
        ItemStack border = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack accent = item(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < menu.kind.size; slot++) {
            if (slot < 9 || slot >= menu.kind.size - 9 || slot % 9 == 0 || slot % 9 == 8)
                menu.inventory.setItem(slot, border);
        }
        for (int slot : new int[]{0, 8, 18, 26}) menu.inventory.setItem(slot, accent);
    }
    private void openStatistics(Player player) {
        if (!allowed(player, "use")) return;
        Menu menu = new Menu(player, Kind.STATS);
        decorateCompact(menu);
        menu.inventory.setItem(4, item(Material.ENDER_CHEST, "§b§lDUMPSTER STATISTICS", "§7Small contributions. Second chances."));
        menu.inventory.setItem(13, item(Material.CLOCK, "§eLoading statistics…"));
        menu.inventory.setItem(menu.kind.backSlot(), item(Material.ARROW, "§eBack to Dumpster", "§7Return to the main menu."));
        player.openInventory(menu.inventory);
        plugin.service().stats(stats -> {
            if (!viewing(player, menu)) return;
            menu.inventory.setItem(11, item(Material.CHEST, "§a§lAvailable Stacks", "§f" + stats.available(), "§7Waiting to be discovered."));
            menu.inventory.setItem(13, item(Material.DIAMOND, "§b§lRescued Stacks", "§f" + stats.rescued(), "§7Given a second home."));
            menu.inventory.setItem(15, item(Material.HOPPER, "§e§lPending Transfers", "§f" + stats.pending(), "§7Returns and deliveries awaiting completion."));
            String oldest = stats.oldest() == 0 ? "No items yet" : java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(stats.oldest()));
            menu.inventory.setItem(22, item(Material.CLOCK, "§6Oldest Available Stack", "§f" + oldest));
        }, () -> {
            if (viewing(player, menu)) menu.inventory.setItem(13, item(Material.BARRIER, "§cStatistics unavailable", "§7Please return and try again."));
        });
    }
    public void statistics(org.bukkit.command.CommandSender sender) {
        plugin.service().stats(stats -> plugin.message(sender, "stats", "{available}", Long.toString(stats.available()),
                "{rescued}", Long.toString(stats.rescued()), "{pending}", Long.toString(stats.pending())), () -> plugin.message(sender, "storage-error"));
    }
    /** Shift-transfer into real deposit slots only, preserving the navigation control. */
    static ItemStack moveIntoDeposit(Inventory inventory, ItemStack source) {
        ItemStack remaining = source.clone();
        int maximum = Math.min(source.getMaxStackSize(), inventory.getMaxStackSize());
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < DEPOSIT_SLOTS && remaining.getAmount() > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                boolean empty = current == null || current.getType().isAir();
                if (pass == 0 && !empty && current.isSimilar(source)) {
                    int moved = Math.min(remaining.getAmount(), Math.max(0, maximum - current.getAmount()));
                    if (moved > 0) {
                        ItemStack merged = current.clone(); merged.setAmount(current.getAmount() + moved);
                        inventory.setItem(slot, merged); remaining.setAmount(remaining.getAmount() - moved);
                    }
                } else if (pass == 1 && empty) {
                    int moved = Math.min(remaining.getAmount(), maximum);
                    ItemStack placed = source.clone(); placed.setAmount(moved);
                    inventory.setItem(slot, placed); remaining.setAmount(remaining.getAmount() - moved);
                }
            }
        }
        return remaining.getAmount() == 0 ? null : remaining;
    }
    private static boolean viewing(Player player, Menu menu) { return player.isOnline() && player.getOpenInventory().getTopInventory() == menu.inventory; }
    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); item.setItemMeta(meta); return item;
    }
}
