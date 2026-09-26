package com.github.leap.etherealdumpster.progression;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** Stable IDs preserve earned titles when thresholds or display names change. */
public record Milestones(List<Tier> tiers) {
    public enum Track { RESCUE, CONTRIBUTION }
    public record Tier(String id, Track track, long target, String title) {
        public long progress(Profile profile) { return track == Track.RESCUE ? profile.rescues() : profile.contributions(); }
    }
    private static final List<Long> TARGETS = List.of(100L,250L,500L,1000L,2000L,3500L,5000L,7500L,10000L,20000L);
    private static final List<String> RESCUE = List.of("Rookie Scavenger", "Curious Scavenger", "Scrap Hunter", "Dumpster Diver", "Discarded Treasure Hunter", "Forgotten Relic Expert", "Dumpster Veteran", "Keeper of Lost Treasures", "Dumpster Legend", "Ethereal Sovereign");
    private static final List<String> CONTRIBUTION = List.of("Still Useful", "Second Chance Giver", "Scrap Donor", "Finds Supplier", "Dumpster Benefactor", "Treasure Supplier", "Scrap Baron", "Legendary Donor", "Second Chance Hero", "Ethereal Legacy");
    public Milestones { tiers = List.copyOf(tiers); }
    /** Translate defaults shipped in the previous build without replacing custom server text. */
    public static boolean upgradeEnglishDefaults(ConfigurationSection config) {
        boolean changed = false;
        if (config.getStringList("milestones.rescue.titles").equals(List.of("Pemulung Pemula", "Pengais Penasaran", "Pemburu Barang Bekas", "Penyelam Dumpster", "Pemburu Harta Terbuang", "Ahli Barang Terlupakan", "Veteran Dumpster", "Penjaga Harta Terbuang", "Legenda Dumpster", "Penguasa Ethereal"))) {
            config.set("milestones.rescue.titles", RESCUE); changed = true;
        }
        if (config.getStringList("milestones.contribution.titles").equals(List.of("Masih Berguna", "Pemberi Kesempatan", "Donatur Barang Bekas", "Pemasok Temuan", "Dermawan Dumpster", "Pemasok Harta Karun", "Juragan Barang Bekas", "Donatur Legendaris", "Pahlawan Kesempatan Kedua", "Warisan Ethereal"))) {
            config.set("milestones.contribution.titles", CONTRIBUTION); changed = true;
        }
        if ("&e✦ Gelar terbuka: &f{title}&e! Pilih lewat /dumpster profile.".equals(config.getString("messages.title-unlocked"))) {
            config.set("messages.title-unlocked", "&e✦ Title unlocked: &f{title}&e! Choose it with /dumpster profile."); changed = true;
        }
        return changed;
    }
    public static Milestones defaults() { return read(null); }
    public static Milestones read(ConfigurationSection config) {
        List<Tier> tiers = new ArrayList<>();
        for (Track track : Track.values()) {
            String path = "milestones." + track.name().toLowerCase(Locale.ROOT);
            List<?> raw = config != null && config.contains(path + ".targets") ? config.getList(path + ".targets") : TARGETS;
            List<String> titles = config != null && config.contains(path + ".titles") ? config.getStringList(path + ".titles") : track == Track.RESCUE ? RESCUE : CONTRIBUTION;
            if (raw == null || raw.size() != 10 || titles.size() != 10) throw new IllegalArgumentException(path + " requires 10 targets and 10 titles");
            long previous = 0;
            for (int i = 0; i < 10; i++) {
                Object value = raw.get(i);
                if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) throw new IllegalArgumentException(path + " targets must be integers");
                long target = number.longValue();
                if (target <= previous || target > 1_000_000_000L || titles.get(i).isBlank() || titles.get(i).length() > 80)
                    throw new IllegalArgumentException(path + " targets must increase (1..1000000000), with nonempty titles up to 80 characters");
                tiers.add(new Tier(track.name().toLowerCase(Locale.ROOT) + "-" + (i + 1), track, target, titles.get(i))); previous = target;
            }
        }
        return new Milestones(tiers);
    }
    public Optional<Tier> find(String id) { return tiers.stream().filter(t -> t.id().equals(id)).findFirst(); }
    public Optional<Tier> next(Profile profile, Track track) {
        return tiers.stream().filter(t -> t.track() == track && !profile.unlocked().contains(t.id())).findFirst();
    }
}
