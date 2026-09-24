package com.github.leap.etherealdumpster.commands;

import com.github.leap.etherealdumpster.gui.DumpGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class DumpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        DumpGUI.open(player);
        return true;
    }

}