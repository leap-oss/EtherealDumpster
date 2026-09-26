package com.github.leap.etherealdumpster.commands;

import com.github.leap.etherealdumpster.EtherealDumpster;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class DumpCommand implements TabExecutor {
    private record Confirmation(long before, long expires) { }
    private final EtherealDumpster plugin;
    private final Map<String, Confirmation> confirmations = new HashMap<>();
    public DumpCommand(EtherealDumpster plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        String permission = switch (action) {
            case "create", "remove", "stats", "reload", "purge" -> "admin." + action;
            case "dive" -> "dive";
            default -> "use";
        };
        if (!sender.hasPermission("etherealdumpster." + permission)) { plugin.message(sender, "no-permission"); return true; }
        switch (action) {
            case "reload" -> {
                try { plugin.reloadSettings(); plugin.message(sender, "reloaded"); }
                catch (Exception e) { sender.sendMessage("§cInvalid configuration: " + e.getMessage()); }
            }
            case "stats" -> plugin.gui().statistics(sender);
            case "purge" -> purge(sender, args);
            case "help" -> plugin.message(sender, "help");
            case "open", "dive", "create", "remove", "recover", "profile" -> {
                if (!(sender instanceof Player player)) { plugin.message(sender, "player-only"); break; }
                switch (action) {
                    case "profile" -> plugin.gui().profile(player);
                    case "open" -> plugin.gui().open(player); case "dive" -> plugin.gui().dive(player);
                    case "recover" -> plugin.service().recover(player);
                    case "create" -> plugin.registry().change(player, true); case "remove" -> plugin.registry().change(player, false);
                }
            }
            default -> plugin.message(sender, "help");
        }
        return true;
    }
    private void purge(CommandSender sender, String[] args) {
        long now = System.currentTimeMillis();
        confirmations.entrySet().removeIf(e -> e.getValue().expires() < now);
        String owner = sender instanceof Player p ? p.getUniqueId().toString() : "console:" + sender.getName();
        if (args.length != 2) { plugin.message(sender, "purge-help"); return; }
        if (args[1].equalsIgnoreCase("confirm")) {
            Confirmation confirmation = confirmations.remove(owner);
            if (confirmation == null || confirmation.expires() < now) { plugin.message(sender, "purge-expired"); return; }
            plugin.service().purge(confirmation.before(), count -> {
                plugin.message(sender, "purged", "{count}", Integer.toString(count));
                plugin.getLogger().info(sender.getName() + " purged " + count + " available dumpster entries.");
            }, () -> plugin.message(sender, "storage-error"));
            return;
        }
        long before;
        if (args[1].equalsIgnoreCase("all")) before = now + 1;
        else {
            try {
                if (!args[1].matches("[1-9][0-9]{0,4}d")) throw new IllegalArgumentException();
                int days = Integer.parseInt(args[1].substring(0, args[1].length() - 1));
                before = now - TimeUnit.DAYS.toMillis(days);
            } catch (IllegalArgumentException e) { plugin.message(sender, "purge-help"); return; }
        }
        confirmations.put(owner, new Confirmation(before, now + 30_000));
        plugin.message(sender, "purge-confirm", "{scope}", args[1]);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("open", "dive", "recover", "profile", "help", "create", "remove", "stats", "reload", "purge").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .filter(s -> !List.of("create", "remove", "stats", "reload", "purge").contains(s) || sender.hasPermission("etherealdumpster.admin." + s)).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("purge") && sender.hasPermission("etherealdumpster.admin.purge"))
            return List.of("all", "30d", "confirm").stream().filter(s -> s.startsWith(args[1])).toList();
        return List.of();
    }
}
