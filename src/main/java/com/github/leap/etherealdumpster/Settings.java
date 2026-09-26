package com.github.leap.etherealdumpster;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public record Settings(Material accessBlock, int displayed, int cooldown, int maxEntries, int expireDays, Set<Material> blacklist) {
    public static Settings read(FileConfiguration c) {
        Material block = Material.matchMaterial(c.getString("dumpster.access-block", "ENDER_CHEST"));
        if (block == null || !block.isBlock() || block.isAir()) throw new IllegalArgumentException("dumpster.access-block must be a block material");
        int displayed = c.getInt("dive.displayed-items", 45), cooldown = c.getInt("dive.refresh-cooldown-seconds", 5);
        int max = c.getInt("storage.max-entries", 50000), days = c.getInt("storage.expire-after-days", 30);
        if (displayed < 1 || displayed > 45 || cooldown < 0 || max < 0 || days < 0 || days > 36500)
            throw new IllegalArgumentException("Invalid limits: displayed-items 1..45; cooldown/max >= 0; expire-after-days 0..36500");
        Set<Material> blacklist = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("restrictions.blacklist")) {
            Material m = Material.matchMaterial(name);
            if (m == null) throw new IllegalArgumentException("Unknown blacklist material: " + name);
            blacklist.add(m);
        }
        return new Settings(block, displayed, cooldown, max, days, Set.copyOf(blacklist));
    }
}
