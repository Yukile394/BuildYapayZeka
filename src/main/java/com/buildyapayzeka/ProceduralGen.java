package com.buildyapayzeka;

import java.util.*;

/**
 * API anahtarı GEREKTİRMEZ. İstekteki anahtar kelimelere göre (spawn, arena, 1v1, ada, kale) yapıyı seçer,
 * yerleşim / boyut / renk / ağaç türü her seferinde rastgele değişir. Yapay zeka değildir, serbest cümle anlamaz.
 */
public final class ProceduralGen {
    private final Random r = new Random();
    private final List<Op> o = new ArrayList<>();

    // {duvar dolgusu, çerçeve kütüğü, çatı merdiveni, temel, zemin}
    private static final String[][] STYLES = {
        {"white_terracotta", "dark_oak_log", "brick_stairs", "stone_bricks", "spruce_planks"},
        {"spruce_planks", "dark_oak_log", "dark_oak_stairs", "cobblestone", "spruce_planks"},
        {"birch_planks", "stripped_spruce_log", "red_nether_brick_stairs", "stone_bricks", "oak_planks"},
        {"smooth_sandstone", "stripped_oak_log", "sandstone_stairs", "cut_sandstone", "birch_planks"},
        {"calcite", "stripped_dark_oak_log", "stone_brick_stairs", "polished_andesite", "spruce_planks"},
        {"light_gray_terracotta", "spruce_log", "spruce_stairs", "cobblestone", "dark_oak_planks"}
    };
    private static final String[] COLORS = {"red", "blue", "yellow", "purple", "orange", "green", "cyan", "white"};
    private static final String[] TREES = {"oak", "spruce", "birch", "dark_oak", "spruce", "oak", "cherry"};
    private final String[] st = STYLES[r.nextInt(STYLES.length)];
    private final String stone = r.nextBoolean() ? "50%stone_bricks,25%mossy_stone_bricks,15%cracked_stone_bricks,10%andesite"
            : "45%stone_bricks,30%cobblestone,15%mossy_cobblestone,10%cracked_stone_bricks";
    private final String roofBlock = r.nextBoolean() ? "red_nether_bricks" : "60%bricks,40%red_nether_bricks";

    private static boolean has(String s, String... k) { for (String x : k) if (s.contains(x)) return true; return false; }

    private void op(String t, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        o.add(Op.from(m));
    }

    public static Plan plan(String prompt) {
        String p = prompt.toLowerCase(Locale.ROOT);
        ProceduralGen g = new ProceduralGen();
        boolean island = has(p, "ada", "island", "yüzen", "yuzen", "gökyüzü", "gokyuzu");
        boolean castle = has(p, "kale", "castle", "şato", "sato");
        boolean arena = has(p, "arena", "pvp", "savaş", "savas", "colosseum");
        boolean duel = has(p, "1v1", "duel", "düello", "duello", "oda");
        boolean spawn = has(p, "spawn", "lobi", "lobby", "hub", "köy", "koy", "şehir", "sehir", "pazar", "takas", "kasa", "market", "doğma", "dogma");
        List<String> names = new ArrayList<>();
        if (island) { g.island(); names.add("yüzen ada kalesi"); }
        else if (castle) { g.castle(); names.add("kale"); }
        else {
            if (!spawn && !arena && !duel) { spawn = arena = duel = true; }
            if (spawn) { g.spawn(); names.add("ortaçağ spawn"); }
            if (arena) { g.arena(0, spawn ? 60 : 0); names.add("arena"); }
            if (duel) { g.duel(0, spawn ? -62 : 0); names.add("1v1 odaları"); }
        }
        return new Plan("Üretici (API'siz) ile kuruyorum: " + String.join(", ", names), g.o);
    }

    // ---------------- ortak parçalar ----------------
    private void ground(int x1, int x2, int z1, int z2) {
        op("fill", "m", "dirt", "x1", x1, "y1", -3, "z1", z1, "x2", x2, "y2", -1, "z2", z2);
        op("fill", "m", "78%grass_block,12%coarse_dirt,10%podzol", "x1", x1, "y1", 0, "z1", z1, "x2", x2, "y2", 0, "z2", z2);
    }

    private void lamp(int x, int z) {
        op("fill", "m", "dark_oak_fence", "x1", x, "y1", 1, "z1", z, "x2", x, "y2", 3, "z2", z);
        op("set", "m", "lantern[hanging=false]", "x1", x, "y1", 4, "z1", z);
    }

    private void stall(int x, int z) {
        for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-1, 1})
            op("fill", "m", "spruce_fence", "x1", x + dx, "y1", 1, "z1", z + dz, "x2", x + dx, "y2", 3, "z2", z + dz);
        String c = COLORS[r.nextInt(COLORS.length)];
        op("fill", "m", c + "_wool,white_wool", "x1", x - 3, "y1", 4, "z1", z - 2, "x2", x + 3, "y2", 4, "z2", z + 2);
        op("fill", "m", "spruce_slab[type=top]", "x1", x - 3, "y1", 5, "z1", z - 2, "x2", x + 3, "y2", 5, "z2", z + 2);
        for (int dx = -1; dx <= 1; dx++) op("set", "m", dx == 0 ? "barrel[facing=up]" : "crafting_table", "x1", x + dx, "y1", 1, "z1", z);
        op("set", "m", "lantern[hanging=true]", "x1", x, "y1", 3, "z1", z);
    }

    private void houseAt(int cx, int cz, int toX, int toZ) {
        int w = 7 + r.nextInt(3), d = 7 + r.nextInt(3);
        String door = Math.abs(toX - cx) > Math.abs(toZ - cz) ? (toX > cx ? "east" : "west") : (toZ > cz ? "south" : "north");
        String[] s = r.nextInt(3) == 0 ? STYLES[r.nextInt(STYLES.length)] : st;
        op("house", "x1", cx - w / 2, "z1", cz - d / 2, "x2", cx - w / 2 + w - 1, "z2", cz - d / 2 + d - 1, "y1", 0,
                "stories", r.nextInt(4) == 0 ? 2 : 1, "m", s[0], "frame", s[1], "roof", s[2], "base", s[3], "floor", s[4],
                "door", door, "axis", r.nextBoolean() ? "x" : "z");
    }

    private boolean far(List<int[]> pts, int x, int z, int d) {
        for (int[] q : pts) if ((q[0] - x) * (q[0] - x) + (q[1] - z) * (q[1] - z) < d * d) return false;
        return true;
    }

    private void sign(String txt, int cx, int y, int z, int scale, String face) {
        int w = Glyphs.render(txt)[0].length * scale;
        op("text", "s", txt, "x1", cx - w / 2, "y1", y, "z1", z, "scale", scale, "face", face, "m", "white_concrete", "d", 1);
    }

    // ---------------- SPAWN ----------------
    private void spawn() {
        int e = 44;
        op("mound", "x1", r.nextInt(31) - 15, "y1", 0, "z1", -50, "r", 19, "h", 20, "m", "grass_block", "body", "stone", "cap", "calcite", "capfrom", 0.6, "rough", 0.4);
        ground(-e, e, -e, e);
        op("cyl", "m", stone, "x1", 0, "y1", 0, "z1", 0, "r", 20, "h", 1);
        op("cyl", "m", "polished_andesite", "x1", 0, "y1", 0, "z1", 0, "r", 12, "h", 1);
        op("cyl", "m", "chiseled_stone_bricks", "x1", 0, "y1", 0, "z1", 0, "r", 9, "h", 1, "hollow", true);
        op("cyl", "m", "stone_bricks", "x1", 0, "y1", 0, "z1", 0, "r", 5, "h", 2, "hollow", true, "thick", 1);
        op("cyl", "m", "water", "x1", 0, "y1", 1, "z1", 0, "r", 4, "h", 1);
        op("cyl", "m", "quartz_pillar", "x1", 0, "y1", 0, "z1", 0, "r", 1, "h", 5);
        op("set", "m", "sea_lantern", "x1", 0, "y1", 5, "z1", 0);
        List<int[]> houses = new ArrayList<>();
        int n = 9;
        double off = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) {
            double a = off + i * 6.2832 / n;
            double deg = Math.toDegrees(Math.atan2(Math.sin(a), Math.cos(a)));
            if (Math.abs(deg - 90) < 28 || Math.abs(deg + 90) < 30) continue;
            int rad = 29 + r.nextInt(4);
            int hx = (int) Math.round(Math.cos(a) * rad), hz = (int) Math.round(Math.sin(a) * rad);
            houseAt(hx, hz, 0, 0);
            houses.add(new int[]{hx, hz});
            op("line", "m", "55%dirt_path,45%gravel", "x1", (int) Math.round(hx * 0.62), "y1", 0, "z1", (int) Math.round(hz * 0.62), "x2", (int) Math.round(hx * 0.8), "y2", 0, "z2", (int) Math.round(hz * 0.8), "r", 1);
        }
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            stall((int) Math.round(Math.cos(a) * 14), (int) Math.round(Math.sin(a) * 14));
        }
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4 + 0.39; lamp((int) Math.round(Math.cos(a) * 18), (int) Math.round(Math.sin(a) * 18)); }
        // arka duvar + işaretler
        op("fill", "m", stone, "x1", -21, "y1", 1, "z1", -41, "x2", 21, "y2", 9, "z2", -40);
        op("fill", "m", "stone_brick_slab[type=top]", "x1", -21, "y1", 10, "z1", -41, "x2", 21, "y2", 10, "z2", -40);
        String[] labels = {"TAKAS", "KASA", "KURALLAR"};
        int[] xs = {-14, 0, 14};
        for (int i = 0; i < 3; i++) sign(labels[i], xs[i], 3, -39, 1, "south");
        for (int sx : new int[]{-23, 23}) {
            String c = COLORS[r.nextInt(COLORS.length)];
            op("tower", "x1", sx, "y1", 0, "z1", -40, "r", 4, "h", 15, "m", stone, "m2", "chiseled_stone_bricks", "top", "cone", "roof", roofBlock, "flag", c, "floors", 5);
        }
        // giriş kapısı
        op("arch", "x1", 0, "y1", 1, "z1", 40, "axis", "x", "w", 7, "h", 5, "depth", 2, "m", "chiseled_stone_bricks", "m2", stone, "wall", 14, "wallh", 12);
        op("line", "m", "gravel", "x1", 0, "y1", 0, "z1", 39, "x2", 0, "y2", 0, "z2", 20, "r", 2);
        sign("SILVERA", 0, 14, 41, 2, "south");
        lamp(-5, 38); lamp(5, 38);
        int tries = 0;
        List<int[]> trees = new ArrayList<>();
        while (trees.size() < 16 && tries++ < 200) {
            int x = r.nextInt(2 * e - 6) - e + 3, z = r.nextInt(2 * e - 6) - e + 3;
            if (x * x + z * z < 26 * 26 || !far(houses, x, z, 9) || !far(trees, x, z, 5) || (Math.abs(x) < 7 && z > 28) || z < -37) continue;
            trees.add(new int[]{x, z});
            op("tree", "x1", x, "y1", 1, "z1", z, "kind", TREES[r.nextInt(TREES.length)], "h", 6 + r.nextInt(6));
        }
        op("replace", "from", "stone_bricks", "m", stone, "x1", -e, "y1", 0, "z1", -e, "x2", e, "y2", 14, "z2", e);
        op("scatter", "m", "55%short_grass,12%poppy,12%dandelion,8%cornflower,8%fern,5%azure_bluet", "x1", -e, "y1", 0, "z1", -e, "x2", e, "y2", 0, "z2", e, "p", 0.22, "on", "grass_block,podzol");
    }

    // ---------------- ARENA ----------------
    private void arena(int cx, int cz) {
        int in = 10, rows = 6;
        ground(cx - 26, cx + 26, cz - 26, cz + 26);
        op("cyl", "m", "smooth_sandstone", "x1", cx, "y1", 0, "z1", cz, "r", in - 1, "h", 1);
        op("cyl", "m", "red_terracotta", "x1", cx, "y1", 0, "z1", cz, "r", 6, "h", 1, "hollow", true);
        op("cyl", "m", "bricks", "x1", cx, "y1", 0, "z1", cz, "r", 3, "h", 1, "hollow", true);
        op("cyl", "m", "gold_block", "x1", cx, "y1", 0, "z1", cz, "r", 1, "h", 1);
        op("ring_seats", "x1", cx, "y1", 0, "z1", cz, "r", in, "rows", rows, "m", r.nextBoolean() ? "spruce_stairs" : "dark_oak_stairs", "fill", stone);
        op("cyl", "m", stone, "x1", cx, "y1", 0, "z1", cz, "r", in + rows + 2, "h", rows + 5, "hollow", true, "thick", 2);
        op("cyl", "m", "stone_brick_slab[type=top]", "x1", cx, "y1", rows + 5, "z1", cz, "r", in + rows + 2, "h", 1, "hollow", true, "thick", 2);
        for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}})
            op("clear", "x1", cx + d[0] * (in + rows - 1) - (d[0] == 0 ? 2 : 0), "y1", 1, "z1", cz + d[1] * (in + rows - 1) - (d[1] == 0 ? 2 : 0),
                    "x2", cx + d[0] * (in + rows + 3) + (d[0] == 0 ? 2 : 0), "y2", 4, "z2", cz + d[1] * (in + rows + 3) + (d[1] == 0 ? 2 : 0));
        for (int sx : new int[]{-1, 1})
            op("tower", "x1", cx + sx * (in + rows + 5), "y1", 0, "z1", cz, "r", 4, "h", 17, "m", stone, "m2", "chiseled_stone_bricks", "top", "cone", "roof", roofBlock,
                    "flag", COLORS[r.nextInt(COLORS.length)], "floors", 5);
        op("pillar", "x1", cx, "y1", 1, "z1", cz, "h", 6, "m", "stone_bricks", "base", "stone_brick_stairs", "cap", "stone_brick_stairs");
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int x = cx + (int) Math.round(Math.cos(a) * (in + rows + 2)), z = cz + (int) Math.round(Math.sin(a) * (in + rows + 2));
            op("set", "m", "lantern[hanging=false]", "x1", x, "y1", rows + 6, "z1", z);
        }
        op("replace", "from", "stone_bricks", "m", stone, "x1", cx - 24, "y1", 0, "z1", cz - 24, "x2", cx + 24, "y2", 14, "z2", cz + 24);
    }

    // ---------------- 1v1 ----------------
    private void duel(int cx, int cz) {
        String[] floors = {"blackstone", "quartz_block", "smooth_sandstone", "prismarine_bricks", "polished_andesite", "red_nether_bricks"};
        ground(cx - 44, cx + 44, cz - 14, cz + 14);
        for (int i = 0; i < 4; i++) {
            int x = cx - 30 + i * 20;
            String f = floors[r.nextInt(floors.length)];
            op("clear", "x1", x - 7, "y1", 1, "z1", cz - 7, "x2", x + 7, "y2", 9, "z2", cz + 7);
            op("hollow", "m", "polished_deepslate", "x1", x - 8, "y1", 0, "z1", cz - 8, "x2", x + 8, "y2", 9, "z2", cz + 8);
            op("fill", "m", f, "x1", x - 7, "y1", 0, "z1", cz - 7, "x2", x + 7, "y2", 0, "z2", cz + 7);
            op("fill", "m", "chiseled_deepslate", "x1", x - 8, "y1", 1, "z1", cz - 8, "x2", x - 8, "y2", 8, "z2", cz - 8);
            for (int sx : new int[]{-8, 8}) for (int sz : new int[]{-8, 8})
                op("fill", "m", "deepslate_bricks", "x1", x + sx, "y1", 1, "z1", cz + sz, "x2", x + sx, "y2", 8, "z2", cz + sz);
            op("fill", "m", "glass_pane", "x1", x - 5, "y1", 3, "z1", cz - 8, "x2", x + 5, "y2", 5, "z2", cz - 8);
            op("set", "m", "gold_block", "x1", x - 5, "y1", 0, "z1", cz);
            op("set", "m", "gold_block", "x1", x + 5, "y1", 0, "z1", cz);
            op("cyl", "m", "gold_block", "x1", x, "y1", 0, "z1", cz, "r", 2, "h", 1, "hollow", true);
            for (int dx : new int[]{-6, 0, 6}) for (int dz : new int[]{-6, 6}) op("set", "m", "sea_lantern", "x1", x + dx, "y1", 9, "z1", cz + dz);
            op("clear", "x1", x - 1, "y1", 1, "z1", cz + 8, "x2", x + 1, "y2", 3, "z2", cz + 8);
            sign(String.valueOf(i + 1) + "V" + (i + 1), x, 10, cz + 9, 1, "south");
        }
    }

    // ---------------- YÜZEN ADA ----------------
    private void island() {
        op("island", "x1", 0, "y1", 0, "z1", 0, "r", 28, "depth", 24, "m", "grass_block", "soil", "dirt", "body", "60%stone,20%andesite,20%mossy_cobblestone", "rough", 0.4, "bump", 1);
        op("cyl", "m", stone, "x1", 0, "y1", 0, "z1", 0, "r", 14, "h", 1);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            op("tower", "x1", sx * 11, "y1", 0, "z1", sz * 11, "r", 3, "h", 16 + r.nextInt(5), "m", stone, "m2", "chiseled_stone_bricks", "top", "cone",
                    "roof", "60%orange_terracotta,40%yellow_terracotta", "rh", 10, "flag", COLORS[r.nextInt(COLORS.length)], "floors", 5, "door", "south");
        op("fill", "m", stone, "x1", -11, "y1", 1, "z1", -11, "x2", 11, "y2", 8, "z2", -11);
        op("fill", "m", stone, "x1", -11, "y1", 1, "z1", 11, "x2", 11, "y2", 8, "z2", 11);
        op("fill", "m", stone, "x1", -11, "y1", 1, "z1", -11, "x2", -11, "y2", 8, "z2", 11);
        op("fill", "m", stone, "x1", 11, "y1", 1, "z1", -11, "x2", 11, "y2", 8, "z2", 11);
        op("clear", "x1", -2, "y1", 1, "z1", 11, "x2", 2, "y2", 5, "z2", 11);
        op("tower", "x1", 0, "y1", 0, "z1", 0, "r", 6, "h", 30, "m", "stone_bricks", "m2", "chiseled_stone_bricks", "top", "cone", "roof", "60%orange_terracotta,40%yellow_terracotta",
                "rh", 16, "flag", "yellow", "floors", 6, "door", "south");
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            op("line", "m", "stone_bricks", "x1", sx * 8, "y1", 14, "z1", sz * 8, "x2", sx * 11, "y2", 14, "z2", sz * 11, "r", 1);
        for (int i = 0; i < 3; i++) {
            double a = 0.6 + i * 2.1;
            houseAt((int) Math.round(Math.cos(a) * 21), (int) Math.round(Math.sin(a) * 21), 0, 0);
        }
        for (int i = 0; i < 7; i++) {
            double a = r.nextDouble() * 6.28;
            int rad = 16 + r.nextInt(8);
            op("tree", "x1", (int) Math.round(Math.cos(a) * rad), "y1", 1, "z1", (int) Math.round(Math.sin(a) * rad), "kind", r.nextBoolean() ? "bonsai" : "cherry", "h", 7 + r.nextInt(4));
        }
        op("scatter", "m", "55%short_grass,15%azure_bluet,15%allium,15%fern", "x1", -30, "y1", 0, "z1", -30, "x2", 30, "y2", 1, "z2", 30, "p", 0.25, "on", "grass_block");
        op("replace", "from", "stone_bricks", "m", stone, "x1", -30, "y1", 0, "z1", -30, "x2", 30, "y2", 40, "z2", 30);
    }

    // ---------------- KALE ----------------
    private void castle() {
        int e = 24;
        ground(-e - 12, e + 12, -e - 12, e + 12);
        op("fill", "m", "55%cobblestone,30%gravel,15%stone_bricks", "x1", -e + 3, "y1", 0, "z1", -e + 3, "x2", e - 3, "y2", 0, "z2", e - 3);
        for (int[] w : new int[][]{{-e, -e, e, -e + 2}, {-e, e - 2, e, e}, {-e, -e, -e + 2, e}, {e - 2, -e, e, e}}) {
            op("fill", "m", stone, "x1", w[0], "y1", 1, "z1", w[1], "x2", w[2], "y2", 11, "z2", w[3]);
            op("fill", "m", "stone_brick_slab[type=top]", "x1", w[0], "y1", 12, "z1", w[1], "x2", w[2], "y2", 12, "z2", w[3]);
        }
        for (int sx : new int[]{-e, e}) for (int sz : new int[]{-e, e})
            op("tower", "x1", sx, "y1", 0, "z1", sz, "r", 5, "h", 18, "m", stone, "m2", "chiseled_stone_bricks", "top", "cone", "roof", roofBlock,
                    "flag", COLORS[r.nextInt(COLORS.length)], "floors", 6);
        op("tower", "x1", 0, "y1", 0, "z1", -10, "r", 8, "h", 28, "m", stone, "m2", "chiseled_stone_bricks", "top", "battlement", "floors", 7, "door", "south", "doorm", "dark_oak_door");
        op("arch", "x1", 0, "y1", 1, "z1", e - 1, "axis", "x", "w", 7, "h", 6, "depth", 3, "m", "chiseled_stone_bricks", "m2", stone, "wall", 9, "wallh", 14);
        op("line", "m", "gravel", "x1", 0, "y1", 0, "z1", e, "x2", 0, "y2", 0, "z2", 0, "r", 2);
        List<int[]> houses = new ArrayList<>();
        for (int[] p : new int[][]{{-14, 8}, {14, 8}, {-14, 18}, {14, 18}}) { houseAt(p[0], p[1], 0, 0); houses.add(p); }
        for (int i = 0; i < 6; i++) lamp(i % 2 == 0 ? -4 : 4, 3 + i * 3);
        for (int i = 0; i < 12; i++) {
            int x = r.nextInt(2 * e - 10) - e + 5, z = r.nextInt(2 * e - 10) - e + 5;
            if (!far(houses, x, z, 7) || Math.abs(x) < 6 || (Math.abs(z + 10) < 10 && Math.abs(x) < 10)) continue;
            op("tree", "x1", x, "y1", 1, "z1", z, "kind", TREES[r.nextInt(TREES.length)], "h", 6 + r.nextInt(4));
        }
        op("replace", "from", "stone_bricks", "m", stone, "x1", -e - 2, "y1", 0, "z1", -e - 2, "x2", e + 2, "y2", 30, "z2", e + 2);
        op("scatter", "m", "60%short_grass,20%poppy,20%dandelion", "x1", -e - 12, "y1", 0, "z1", -e - 12, "x2", e + 12, "y2", 0, "z2", e + 12, "p", 0.2, "on", "grass_block,podzol");
    }
}
