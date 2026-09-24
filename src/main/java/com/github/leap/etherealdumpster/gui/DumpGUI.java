package com.github.leap.etherealdumpster.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public final class DumpGUI implements Listener {
    public static void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, "EtherealDumpster");
        inventory.setItem(49, item(Material.BARRIER, "§cClose"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        if(title.equals("EtherealDumpster")) {
            event.setCancelled(true);
            if(event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
                return;
            }
            handleMainGUI(player, event.getRawSlot());
        }
    }

    private void handleMainGUI(Player player, int slot) {
        switch(slot) {
            case 49:
                player.closeInventory();
                break;
        }
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}