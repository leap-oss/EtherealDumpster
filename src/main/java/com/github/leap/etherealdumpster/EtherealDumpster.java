package com.github.leap.etherealdumpster;

import com.github.leap.etherealdumpster.commands.DumpCommand;
import com.github.leap.etherealdumpster.gui.DumpGUI;
import org.bukkit.plugin.java.JavaPlugin;

public final class EtherealDumpster extends JavaPlugin {

    private DumpCommand dumpCommand;

    @Override
    public void onEnable() {
        dumpCommand = new DumpCommand();
        getCommand("dump").setExecutor(dumpCommand);
        getServer().getPluginManager().registerEvents(new DumpGUI(), this);
        getLogger().info("EtherealDumpster enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("EtherealDumpster disabled.");
    }
}
