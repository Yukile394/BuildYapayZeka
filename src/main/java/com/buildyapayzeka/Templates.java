package com.buildyapayzeka;

import java.util.*;

/** API anahtarı yokken kullanılan hazır profesyonel şablonlar. */
public class Templates {
    public static Plan plan(String prompt) {
        String p = prompt.toLowerCase(Locale.ROOT);
        boolean sp = has(p, "spawn", "doğma", "dogma", "lobi", "lobby");
        boolean ar = has(p, "arena", "pvp", "savaş", "savas");
        boolean du = has(p, "1v1", "duel", "düello", "duello", "oda");
        if (!sp && !ar && !du) sp = ar = du = true;
        List<Op> o = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (sp) { spawn(o); names.add("spawn alanı (takas + kasa bölgeleri)"); }
        if (ar) { arena(o, 0, 55); names.add("PvP arenası"); }
        if (du) { duel(o, 0, -35); names.add("1v1 odaları"); }
        return new Plan("Hazır şablonlarla inşa ediyorum: " + String.join(", ", names), o);
    }

    static boolean has(String s, String... k) { for (String x : k) if (s.contains(x)) return true; return false; }

    static void spawn(List<Op> o) {
        o.add(Op.fill("AIR", -23, 1, -23, 23, 18, 23));
        o.add(Op.cyl("SMOOTH_STONE", 0, 0, 0, 21, 1, false));
        o.add(Op.cyl("POLISHED_ANDESITE", 0, 0, 0, 12, 1, false));
        o.add(Op.cyl("CHISELED_STONE_BRICKS", 0, 0, 0, 5, 1, false));
        o.add(Op.cyl("QUARTZ_BLOCK", 0, 1, 0, 3, 2, true));
        o.add(Op.cyl("WATER", 0, 1, 0, 2, 1, false));
        o.add(Op.set("SEA_LANTERN", 0, 0, 0));
        o.add(Op.cyl("STONE_BRICKS", 0, 1, 0, 21, 6, true));
        o.add(Op.cyl("DEEPSLATE_BRICKS", 0, 7, 0, 21, 1, true));
        o.add(Op.fill("AIR", -3, 1, 17, 3, 5, 23));
        o.add(Op.fill("AIR", -3, 1, -23, 3, 5, -17));
        o.add(Op.fill("AIR", 17, 1, -3, 23, 5, 3));
        o.add(Op.fill("AIR", -23, 1, -3, -17, 5, 3));
        for (int x : new int[]{-14, 14}) for (int z : new int[]{-14, 14}) {
            o.add(Op.fill("POLISHED_ANDESITE", x, 1, z, x, 4, z));
            o.add(Op.set("SEA_LANTERN", x, 5, z));
        }
        // Takas alanı
        o.add(Op.fill("QUARTZ_BLOCK", 9, 0, -5, 18, 0, 5));
        o.add(Op.fill("CHISELED_QUARTZ_BLOCK", 9, 0, -5, 9, 0, 5));
        for (int z = -4; z <= 4; z += 2) {
            o.add(Op.set("BARREL", 13, 1, z));
            o.add(Op.set("LECTERN", 13, 2, z));
            o.add(Op.set("SEA_LANTERN", 16, 0, z));
        }
        // Kasa alanı
        o.add(Op.fill("DEEPSLATE_TILES", -18, 0, -5, -9, 0, 5));
        for (int x = -17; x <= -11; x += 2) {
            o.add(Op.set("CHEST", x, 1, -3));
            o.add(Op.set("BARREL", x, 1, 3));
        }
        o.add(Op.set("ENDER_CHEST", -14, 1, 0));
        o.add(Op.set("SEA_LANTERN", -12, 0, 0));
    }

    static void arena(List<Op> o, int ox, int oz) {
        o.add(Op.cyl("AIR", ox, 1, oz, 19, 14, false));
        o.add(Op.cyl("POLISHED_BLACKSTONE", ox, 0, oz, 17, 1, false));
        o.add(Op.cyl("DEEPSLATE_TILES", ox, 0, oz, 10, 1, false));
        o.add(Op.cyl("GOLD_BLOCK", ox, 0, oz, 3, 1, false));
        o.add(Op.cyl("DEEPSLATE_BRICKS", ox, 1, oz, 17, 10, true));
        o.add(Op.cyl("CHISELED_DEEPSLATE", ox, 11, oz, 17, 1, true));
        for (int x : new int[]{-7, 7}) for (int z : new int[]{-7, 7}) {
            o.add(Op.fill("POLISHED_DEEPSLATE", ox + x, 1, oz + z, ox + x + 1, 3, oz + z + 1));
        }
        for (int a : new int[]{-15, 15}) {
            o.add(Op.set("SEA_LANTERN", ox + a, 6, oz));
            o.add(Op.set("SEA_LANTERN", ox, 6, oz + a));
        }
        o.add(Op.fill("AIR", ox - 2, 1, oz - 19, ox + 2, 4, oz - 15));
    }

    static void duel(List<Op> o, int ox, int oz) {
        String[] floors = {"BLACKSTONE", "QUARTZ_BLOCK", "SMOOTH_SANDSTONE", "PRISMARINE_BRICKS"};
        for (int i = 0; i < 4; i++) {
            int cx = ox - 30 + i * 20;
            o.add(Op.fill("AIR", cx - 7, 1, oz - 7, cx + 7, 7, oz + 7));
            o.add(Op.hollow("POLISHED_DEEPSLATE", cx - 8, 0, oz - 8, cx + 8, 8, oz + 8));
            o.add(Op.fill(floors[i], cx - 7, 0, oz - 7, cx + 7, 0, oz + 7));
            o.add(Op.set("GOLD_BLOCK", cx - 5, 0, oz));
            o.add(Op.set("GOLD_BLOCK", cx + 5, 0, oz));
            for (int dx : new int[]{-6, 0, 6}) for (int dz : new int[]{-6, 6})
                o.add(Op.set("SEA_LANTERN", cx + dx, 8, oz + dz));
            o.add(Op.fill("AIR", cx - 1, 1, oz + 8, cx + 1, 3, oz + 8));
        }
    }
}
