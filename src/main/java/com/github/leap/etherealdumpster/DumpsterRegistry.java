package com.github.leap.etherealdumpster;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/** Locations are access points only. Registry mutations never touch the global pool. */
public final class DumpsterRegistry implements Listener {
    private final EtherealDumpster plugin;
    private final Path file;
    private final Set<String> blocks = new HashSet<>();
    public DumpsterRegistry(EtherealDumpster plugin) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        this.plugin = plugin; file = plugin.getDataFolder().toPath().resolve("dumpsters.yml");
        if (Files.exists(file)) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile()); blocks.addAll(yaml.getStringList("locations"));
        }
    }
    private static String key(Block block) { return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ(); }
    public boolean contains(Block block) { return blocks.contains(key(block)); }
    public void change(Player player, boolean create) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || (create && block.getType() != plugin.settings().accessBlock())) { plugin.message(player, "invalid-block"); return; }
        String key = key(block); boolean existed = blocks.contains(key);
        if (create) blocks.add(key); else blocks.remove(key);
        try { save(); plugin.message(player, create ? "registered" : "unregistered"); }
        catch (IOException e) {
            if (existed) blocks.add(key); else blocks.remove(key);
            plugin.getLogger().log(Level.SEVERE, "Cannot save dumpster registry", e); plugin.message(player, "storage-error");
        }
    }
    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("locations", blocks.stream().sorted().toList());
        Path temporary = file.resolveSibling("dumpsters.yml.tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !contains(event.getClickedBlock())) return;
        boolean denied = event.useInteractedBlock() == Event.Result.DENY;
        event.setCancelled(true); // Never open the underlying vanilla container.
        if (!denied && event.getHand() == EquipmentSlot.HAND) plugin.gui().open(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!contains(event.getBlock())) return;
        if (!event.getPlayer().hasPermission("etherealdumpster.admin.remove")) { event.setCancelled(true); plugin.message(event.getPlayer(), "no-permission"); return; }
        String key = key(event.getBlock()); blocks.remove(key);
        try { save(); } catch (IOException e) {
            blocks.add(key); event.setCancelled(true); plugin.getLogger().log(Level.SEVERE, "Cannot unregister broken dumpster", e);
        }
    }
    @EventHandler(ignoreCancelled = true) public void explode(EntityExplodeEvent event) { event.blockList().removeIf(this::contains); }
    @EventHandler(ignoreCancelled = true) public void explode(BlockExplodeEvent event) { event.blockList().removeIf(this::contains); }
    @EventHandler(ignoreCancelled = true) public void piston(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(this::contains)) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void piston(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(this::contains)) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void burn(BlockBurnEvent event) { if (contains(event.getBlock())) event.setCancelled(true); }
}
