package com.github.leap.etherealdumpster;

import com.github.leap.etherealdumpster.commands.DumpCommand;
import com.github.leap.etherealdumpster.progression.Milestones;
import com.github.leap.etherealdumpster.gui.DumpGUI;
import com.github.leap.etherealdumpster.service.*;
import com.github.leap.etherealdumpster.storage.SQLiteStorage;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;
import java.util.logging.Level;

public final class EtherealDumpster extends JavaPlugin {
    private volatile Settings settings;
    private volatile Milestones milestones;
    private YamlConfiguration messages;
    private DumpsterService service;
    private DepositJournal journal;
    private DumpGUI gui;
    private DumpsterRegistry registry;
    @Override public void onEnable() {
        SQLiteStorage storage = null;
        try {
            saveDefaultConfig(); reloadSettings();
            journal = new DepositJournal(getDataFolder().toPath().resolve("deposits"));
            registry = new DumpsterRegistry(this);
            storage = new SQLiteStorage(getDataFolder().toPath().resolve("dumpster.db"));
            storage.recoverReservations();
            for (var batch : journal.recoverable()) { storage.deposit(batch.entries(), settings.maxEntries()); journal.complete(batch); }
            for (var pending : journal.prepared()) getLogger().severe("Uncertain deposit requires admin review: " + pending);
            long pending = storage.stats().pending();
            if (pending > 0) getLogger().warning(pending + " pending returns/deliveries. DELIVERING rows require administrator review; never blindly replay.");
            service = new DumpsterService(this, storage, journal); gui = new DumpGUI(this);
            DumpCommand command = new DumpCommand(this);
            Objects.requireNonNull(getCommand("dumpster")).setExecutor(command);
            Objects.requireNonNull(getCommand("dumpster")).setTabCompleter(command);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(registry, this);
            getServer().getScheduler().runTaskTimer(this, () -> { gui.maintenance(); service.retryJournals(); }, 20L, 1200L);
            getServer().getOnlinePlayers().forEach(service::checkMilestones);
            getLogger().info("EtherealDumpster enabled (Paper; Folia support is not declared).");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Initialization failed; plugin disabled to protect item ownership", e);
            if (service == null && storage != null) try { storage.close(); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    public void reloadSettings() throws Exception {
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(new File(getDataFolder(), "config.yml"));
        boolean translatedDefaults = Milestones.upgradeEnglishDefaults(candidate);
        Settings next = Settings.read(candidate); // Atomic replacement; invalid config leaves current settings active.
        Milestones nextMilestones = Milestones.read(candidate);
        if (translatedDefaults) candidate.save(new File(getDataFolder(), "config.yml"));
        messages = candidate; settings = next; milestones = nextMilestones;
    }
    public Milestones milestones() { return milestones; }
    public Settings settings() { return settings; }
    public DumpsterService service() { return service; }
    public DepositJournal journal() { return journal; }
    public DumpGUI gui() { return gui; }
    public DumpsterRegistry registry() { return registry; }
    public String text(String key) {
        String fallback = getConfig().getString("messages." + key, key);
        return ChatColor.translateAlternateColorCodes('&', messages.getString("messages." + key, fallback));
    }
    public void message(CommandSender sender, String key, String... replacements) {
        String value = text(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) value = value.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(value);
    }
    @Override public void onDisable() {
        if (gui != null) gui.shutdown();
        if (service != null) service.close();
    }
}
