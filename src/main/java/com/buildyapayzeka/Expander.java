package com.buildyapayzeka;

import java.util.*;
import java.util.function.Predicate;

/**
 * WorldEdit tarzı inşa motoru (Bukkit'ten bağımsız, asenkron çalışabilir).
 * Op listesini 3B bir ızgaraya yazar, merdiven köşelerini / çit bağlantılarını düzeltir,
 * sonra aşağıdan yukarı sıralı blok listesi üretir.
 */
public final class Expander {

    public static final class Config {
        public int radius = 80, minY = -40, maxY = 90;
        public int maxBlocks = 250000;
        public int seed = 1;
        public Predicate<String> solid = Expander::guessSolid;
    }

    public static final class Result {
        public List<String> palette = new ArrayList<>();   // 0 = kullanılmıyor, 1 = air
        public int[] xs, ys, zs;
        public short[] pi;
        public int n;                 // toplam yerleştirme (air dahil)
        public int solidCount, airCount;
        public List<String> warnings = new ArrayList<>();
    }

    public static Result expand(List<Op> ops, Config cfg) { return new Expander(cfg).run(ops); }

    // ================= durum =================
    private final Config cfg;
    private final int R, sx, sy, sz, minY;
    private final short[] grid;
    private final List<String> pal = new ArrayList<>();
    private final Map<String, Short> palMap = new HashMap<>();
    private final Map<String, Pat> pats = new HashMap<>();
    private final List<String> warn = new ArrayList<>();
    private int seed;

    private Expander(Config cfg) {
        this.cfg = cfg;
        R = cfg.radius;
        sx = sz = 2 * R + 1;
        minY = cfg.minY;
        sy = cfg.maxY - cfg.minY + 1;
        grid = new short[sx * sy * sz];
        pal.add("");
        pal.add("air");
        palMap.put("air", (short) 1);
    }

    private void warn(String s) { if (warn.size() < 40) warn.add(s); }

    // ================= ızgara =================
    private int gi(int x, int y, int z) { return ((y - minY) * sz + (z + R)) * sx + (x + R); }

    private boolean in(int x, int y, int z) {
        return x >= -R && x <= R && z >= -R && z <= R && y >= minY && y < minY + sy;
    }

    private int get(int x, int y, int z) { return in(x, y, z) ? grid[gi(x, y, z)] : 0; }

    private void put(int x, int y, int z, short id) { if (in(x, y, z)) grid[gi(x, y, z)] = id; }

    private void putIfFree(int x, int y, int z, short id) {
        if (in(x, y, z) && grid[gi(x, y, z)] <= 1) grid[gi(x, y, z)] = id;
    }

    private short id(String state) {
        Short s = palMap.get(state);
        if (s != null) return s;
        if (pal.size() >= 32000) return 1;
        short n = (short) pal.size();
        pal.add(state);
        palMap.put(state, n);
        return n;
    }

    // ================= gürültü =================
    static double rnd(int x, int y, int z, int s) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ s * 2654435761L;
        h ^= h >>> 13; h *= 0x5bd1e995L; h ^= h >>> 15; h *= 0x27d4eb2dL; h ^= h >>> 16;
        return (h & 0x7fffffffL) / (double) 0x7fffffffL;
    }

    static double noise2(double x, double z, int s, double scale) {
        double fx = x / scale, fz = z / scale;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = rnd(ix, 0, iz, s), b = rnd(ix + 1, 0, iz, s), c = rnd(ix, 0, iz + 1, s), d = rnd(ix + 1, 0, iz + 1, s);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    // ================= desenler (pattern) =================
    private final class Pat {
        final short[] ids; final double[] cum;
        Pat(short[] ids, double[] w) {
            this.ids = ids;
            double sum = 0; for (double x : w) sum += x;
            cum = new double[w.length];
            double acc = 0;
            for (int k = 0; k < w.length; k++) { acc += w[k] / sum; cum[k] = acc; }
        }
        short pick(int x, int y, int z) {
            if (ids.length == 1) return ids[0];
            double r = rnd(x, y, z, seed);
            for (int k = 0; k < ids.length; k++) if (r < cum[k]) return ids[k];
            return ids[ids.length - 1];
        }
    }

    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("grass", "short_grass"), Map.entry("tallgrass", "short_grass"), Map.entry("stone_brick", "stone_bricks"),
            Map.entry("cobble", "cobblestone"), Map.entry("snow_layer", "snow"), Map.entry("wood_planks", "oak_planks"),
            Map.entry("planks", "oak_planks"), Map.entry("log", "oak_log"), Map.entry("leaves", "oak_leaves"),
            Map.entry("glass_panes", "glass_pane"), Map.entry("stained_glass", "glass"), Map.entry("grass_path", "dirt_path"),
            Map.entry("path", "dirt_path"), Map.entry("cave_air", "air"), Map.entry("void_air", "air"));

    static String norm(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.startsWith("minecraft:")) s = s.substring(10);
        String base = s, props = "";
        int b = s.indexOf('[');
        if (b >= 0) { base = s.substring(0, b); props = s.substring(b); }
        if (base.isEmpty()) throw new IllegalArgumentException("boş blok adı");
        base = ALIAS.getOrDefault(base, base);
        if (base.endsWith("_leaves") && !props.contains("persistent"))
            props = props.isEmpty() ? "[persistent=true]" : props.substring(0, props.length() - 1) + ",persistent=true]";
        return base + props;
    }

    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, st = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '[') depth++;
            else if (c == ']') depth = Math.max(0, depth - 1);
            else if (c == sep && depth == 0) { out.add(s.substring(st, k)); st = k + 1; }
        }
        out.add(s.substring(st));
        return out;
    }

    private Pat pat(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("blok/desen (m) verilmemiş");
        Pat c = pats.get(spec);
        if (c != null) return c;
        List<String> toks = splitTop(spec, ',');
        List<Short> ids = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (String tk : toks) {
            tk = tk.trim();
            if (tk.isEmpty()) continue;
            double w = 1;
            int pc = tk.indexOf('%');
            if (pc > 0 && pc < 8 && tk.substring(0, pc).trim().matches("\\d+(\\.\\d+)?")) {
                w = Double.parseDouble(tk.substring(0, pc).trim());
                tk = tk.substring(pc + 1);
            }
            String state = norm(tk);
            String base = baseOf(state);
            ids.add(base.equals("air") ? (short) 1 : id(state));
            ws.add(Math.max(0.0001, w));
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("boş desen");
        short[] ia = new short[ids.size()];
        double[] wa = new double[ids.size()];
        for (int k = 0; k < ia.length; k++) { ia[k] = ids.get(k); wa[k] = ws.get(k); }
        Pat p = new Pat(ia, wa);
        pats.put(spec, p);
        return p;
    }

    private Pat air() { return pat("air"); }

    static String baseOf(String state) { int b = state.indexOf('['); return b < 0 ? state : state.substring(0, b); }

    static String prop(String state, String key) {
        int b = state.indexOf('[');
        if (b < 0) return null;
        for (String kv : state.substring(b + 1, state.length() - 1).split(",")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) return kv.substring(e + 1);
        }
        return null;
    }

    static String withProp(String state, String key, String val) {
        String base = baseOf(state);
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        int b = state.indexOf('[');
        if (b >= 0) for (String kv : state.substring(b + 1, state.length() - 1).split(",")) {
            int e = kv.indexOf('=');
            if (e > 0) m.put(kv.substring(0, e), kv.substring(e + 1));
        }
        m.put(key, val);
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    // ================= kutu yardımcıları =================
    private static final class Box {
        int x1, y1, z1, x2, y2, z2;
        boolean empty() { return x1 > x2 || y1 > y2 || z1 > z2; }
    }

    private Box box(Op o) {
        int x1 = o.i("x1", 0), y1 = o.i("y1", 0), z1 = o.i("z1", 0);
        int x2 = o.i("x2", x1), y2 = o.i("y2", y1), z2 = o.i("z2", z1);
        Box b = new Box();
        b.x1 = Math.max(Math.min(x1, x2), -R); b.x2 = Math.min(Math.max(x1, x2), R);
        b.y1 = Math.max(Math.min(y1, y2), minY); b.y2 = Math.min(Math.max(y1, y2), minY + sy - 1);
        b.z1 = Math.max(Math.min(z1, z2), -R); b.z2 = Math.min(Math.max(z1, z2), R);
        return b;
    }

    // ================= ana döngü =================
    private Result run(List<Op> ops) {
        for (int k = 0; k < ops.size(); k++) {
            Op op = ops.get(k);
            seed = cfg.seed * 31 + k * 7919 + 17;
            try { apply(op); }
            catch (RuntimeException e) { warn("op#" + k + " " + op.t() + ": " + e.getMessage()); }
        }
        postStairs();
        postConnections();
        return collect();
    }

    private void apply(Op o) {
        switch (o.t()) {
            case "set", "block" -> { Box b = box(o); if (!b.empty()) put(b.x1, b.y1, b.z1, pat(o.s("m")).pick(b.x1, b.y1, b.z1)); }
            case "fill", "cuboid", "box" -> boxOp(o, 0, pat(o.s("m")));
            case "hollow" -> boxOp(o, 1, pat(o.s("m")));
            case "walls" -> boxOp(o, 2, pat(o.s("m")));
            case "clear", "air" -> boxOp(o, 0, air());
            case "replace" -> replace(o);
            case "line" -> line(o);
            case "cyl", "cylinder" -> cyl(o);
            case "sphere", "ellipsoid" -> sphere(o, o.s("half", ""));
            case "dome" -> sphere(o, "top");
            case "cone", "spire" -> cone(o);
            case "pyramid" -> pyramid(o);
            case "island" -> island(o);
            case "mound", "hill", "cliff" -> mound(o);
            case "gable_roof", "roof" -> roofOp(o, false);
            case "hip_roof" -> roofOp(o, true);
            case "house" -> house(o);
            case "tower" -> tower(o);
            case "pillar" -> pillarOp(o);
            case "colonnade" -> colonnade(o);
            case "arch", "gate" -> arch(o);
            case "door" -> doorOp(o);
            case "flag" -> flag(o);
            case "tree" -> tree(o);
            case "ring_seats", "stands", "stadium" -> ringSeats(o);
            case "scatter" -> scatter(o);
            case "text", "sign" -> text(o);
            case "copy", "stack" -> copy(o);
            default -> warn("bilinmeyen komut: " + o.t());
        }
    }

    // ================= temel komutlar =================
    private void boxOp(Op o, int mode, Pat p) {
        Box b = box(o);
        if (b.empty()) return;
        for (int y = b.y1; y <= b.y2; y++) for (int z = b.z1; z <= b.z2; z++) for (int x = b.x1; x <= b.x2; x++) {
            boolean edgeX = x == b.x1 || x == b.x2, edgeY = y == b.y1 || y == b.y2, edgeZ = z == b.z1 || z == b.z2;
            if (mode == 1 && !(edgeX || edgeY || edgeZ)) continue;
            if (mode == 2 && !(edgeX || edgeZ)) continue;
            put(x, y, z, p.pick(x, y, z));
        }
    }

    private void replace(Op o) {
        Box b = box(o);
        if (b.empty()) return;
        Pat p = pat(o.s("m"));
        String from = o.s("from", "*").toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(" ", "");
        boolean any = from.equals("*") || from.isEmpty();
        Set<String> names = new HashSet<>();
        for (String f : from.split(",")) names.add(ALIAS.getOrDefault(f, f));
        boolean[] match = new boolean[pal.size()];
        for (int k = 2; k < pal.size(); k++) match[k] = any || names.contains(baseOf(pal.get(k)));
        for (int y = b.y1; y <= b.y2; y++) for (int z = b.z1; z <= b.z2; z++) for (int x = b.x1; x <= b.x2; x++) {
            int g = grid[gi(x, y, z)];
            if (g >= 2 && g < match.length && match[g]) grid[gi(x, y, z)] = p.pick(x, y, z);
        }
    }

    private void line(Op o) {
        int x1 = o.i("x1", 0), y1 = o.i("y1", 0), z1 = o.i("z1", 0);
        int x2 = o.i("x2", x1), y2 = o.i("y2", y1), z2 = o.i("z2", z1);
        Pat p = pat(o.s("m"));
        int r = Math.max(0, o.i("r", 0));
        int n = Math.min(700, Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1))));
        for (int k = 0; k <= n; k++) {
            double t = n == 0 ? 0 : k / (double) n;
            int x = (int) Math.round(x1 + (x2 - x1) * t), y = (int) Math.round(y1 + (y2 - y1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
            if (r == 0) put(x, y, z, p.pick(x, y, z));
            else for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (dx * dx + dz * dz <= r * r + r) put(x + dx, y, z + dz, p.pick(x + dx, y, z + dz));
        }
    }

    private void cyl(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(0, o.i("r", 3)), rz = Math.max(0, o.i("rz", r));
        int h = o.has("h") ? Math.max(1, o.i("h", 1)) : (o.has("y2") ? Math.max(1, o.i("y2", cy) - cy + 1) : 1);
        boolean hol = o.b("hollow", false);
        int th = Math.max(1, o.i("thick", 1));
        Pat p = pat(o.s("m"));
        disc(cx, cy, cz, r, rz, h, hol, th, p);
    }

    private void disc(int cx, int cy, int cz, int r, int rz, int h, boolean hol, int th, Pat p) {
        for (int dy = 0; dy < h; dy++) for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -rz - 1; dz <= rz + 1; dz++) {
            double a = dx / (r + 0.5), c = dz / (rz + 0.5);
            if (a * a + c * c > 1) continue;
            if (hol) {
                double ri = r - th + 0.5, rzi = rz - th + 0.5;
                if (ri > 0 && rzi > 0) { double a2 = dx / ri, c2 = dz / rzi; if (a2 * a2 + c2 * c2 <= 1) continue; }
            }
            put(cx + dx, cy + dy, cz + dz, p.pick(cx + dx, cy + dy, cz + dz));
        }
    }

    private void sphere(Op o, String half) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(1, o.i("r", 3));
        int rx = Math.max(1, o.i("rx", r)), ry = Math.max(1, o.i("ry", r)), rz = Math.max(1, o.i("rz", r));
        boolean hol = o.b("hollow", false);
        int th = Math.max(1, o.i("thick", 1));
        Pat p = pat(o.s("m"));
        for (int dy = -ry; dy <= ry; dy++) {
            if (half.equals("top") && dy < 0) continue;
            if (half.equals("bottom") && dy > 0) continue;
            for (int dx = -rx; dx <= rx; dx++) for (int dz = -rz; dz <= rz; dz++) {
                double a = dx / (rx + 0.5), b = dy / (ry + 0.5), c = dz / (rz + 0.5);
                if (a * a + b * b + c * c > 1) continue;
                if (hol) {
                    double ai = rx - th + 0.5, bi = ry - th + 0.5, ci = rz - th + 0.5;
                    if (ai > 0 && bi > 0 && ci > 0) {
                        double a2 = dx / ai, b2 = dy / bi, c2 = dz / ci;
                        if (a2 * a2 + b2 * b2 + c2 * c2 <= 1) continue;
                    }
                }
                put(cx + dx, cy + dy, cz + dz, p.pick(cx + dx, cy + dy, cz + dz));
            }
        }
    }

    private void cone(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(1, o.i("r", 4)), h = Math.max(1, o.i("h", r * 2));
        boolean hol = o.b("hollow", false);
        coneAt(cx, cy, cz, r, h, hol, pat(o.s("m")));
    }

    private void coneAt(int cx, int cy, int cz, int r, int h, boolean hol, Pat p) {
        for (int k = 0; k < h; k++) {
            double rk = Math.max(0.6, (r + 0.5) * (h - k) / (double) h);
            int ir = (int) Math.ceil(rk);
            for (int dx = -ir; dx <= ir; dx++) for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > rk) continue;
                if (hol && d < rk - 1.2 && k < h - 1) continue;
                put(cx + dx, cy + k, cz + dz, p.pick(cx + dx, cy + k, cz + dz));
            }
        }
    }

    private void pyramid(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(1, o.i("r", 5)), step = Math.max(1, o.i("step", 1));
        boolean hol = o.b("hollow", false);
        Pat p = pat(o.s("m"));
        for (int k = 0; k <= r; k++) {
            int half = r - k;
            for (int s = 0; s < step; s++) for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
                if (hol && Math.abs(dx) != half && Math.abs(dz) != half) continue;
                put(cx + dx, cy + k * step + s, cz + dz, p.pick(cx + dx, cy + k * step + s, cz + dz));
            }
        }
    }

    // ================= arazi =================
    private void island(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(3, o.i("r", 20));
        int depth = Math.max(3, o.i("depth", (int) (r * 0.9)));
        double rough = o.d("rough", 0.35);
        int bump = Math.max(0, o.i("bump", 0));
        Pat top = pat(o.s("m", "grass_block")), soil = pat(o.s("soil", "dirt")), body = pat(o.s("body", "stone"));
        double p1 = rnd(1, 2, 3, seed) * 6.28, p2 = rnd(4, 5, 6, seed) * 6.28, p3 = rnd(7, 8, 9, seed) * 6.28;
        int rr = (int) Math.ceil(r * (1 + rough)) + 2;
        for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz), ang = Math.atan2(dz, dx);
            double edge = r * (1 + rough * (0.5 * Math.sin(3 * ang + p1) + 0.3 * Math.sin(5 * ang + p2) + 0.2 * Math.sin(9 * ang + p3)));
            if (dist > edge) continue;
            double t = dist / edge, n = noise2(dx, dz, seed, 5.0);
            int dcol = (int) Math.round(depth * (1 - Math.pow(t, 1.5)) * (0.6 + 0.8 * n));
            if (t < 0.85 && rnd(dx, 7, dz, seed) < 0.02) dcol += depth / 2;
            dcol = Math.max(dcol, 2);
            int topY = cy + (bump > 0 ? (int) Math.floor(noise2(dx, dz, seed + 5, 6.0) * (bump + 0.999)) : 0);
            for (int y = cy - dcol; y <= topY; y++) {
                int layer = topY - y;
                Pat p = layer == 0 ? top : layer <= 2 ? soil : body;
                put(cx + dx, y, cz + dz, p.pick(cx + dx, y, cz + dz));
            }
        }
    }

    private void mound(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(3, o.i("r", 15)), h = Math.max(2, o.i("h", 10));
        double rough = o.d("rough", 0.3), steep = o.d("steep", 2.2), capFrom = o.d("capfrom", 0.75);
        Pat surf = pat(o.s("m", "grass_block")), soil = pat(o.s("soil", "dirt")), body = pat(o.s("body", "stone"));
        Pat cap = o.has("cap") ? pat(o.s("cap")) : null;
        double p1 = rnd(1, 2, 3, seed) * 6.28, p2 = rnd(4, 5, 6, seed) * 6.28;
        int rr = (int) Math.ceil(r * (1 + rough)) + 2;
        for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz), ang = Math.atan2(dz, dx);
            double edge = r * (1 + rough * (0.6 * Math.sin(3 * ang + p1) + 0.4 * Math.sin(7 * ang + p2)));
            if (dist > edge) continue;
            double t = dist / edge;
            double n = 0.5 * noise2(dx, dz, seed, 6.0) + 0.5 * noise2(dx, dz, seed + 3, 3.0);
            int top = (int) Math.round(h * Math.max(0, 1 - Math.pow(t, steep)) * (0.7 + 0.6 * n));
            if (top < 1) continue;
            boolean capped = cap != null && top > h * capFrom;
            for (int k = 0; k < top; k++) {
                int fromTop = top - 1 - k;
                Pat p = fromTop == 0 ? (capped ? cap : surf) : fromTop <= 2 ? (capped ? cap : soil) : body;
                put(cx + dx, cy + k, cz + dz, p.pick(cx + dx, cy + k, cz + dz));
            }
        }
    }

    // ================= yön yardımcıları =================
    private static int[] dir(String f) {
        return switch (f) { case "north" -> new int[]{0, -1}; case "south" -> new int[]{0, 1}; case "east" -> new int[]{1, 0}; default -> new int[]{-1, 0}; };
    }
    private static String opp(String f) { return switch (f) { case "north" -> "south"; case "south" -> "north"; case "east" -> "west"; default -> "east"; }; }
    private static String cw(String f) { return switch (f) { case "north" -> "east"; case "east" -> "south"; case "south" -> "west"; default -> "north"; }; }
    private static String ccw(String f) { return switch (f) { case "north" -> "west"; case "west" -> "south"; case "south" -> "east"; default -> "north"; }; }
    private static String axisOf(String f) { return f.equals("north") || f.equals("south") ? "z" : "x"; }

    private short stair(String name, String face, String half) {
        return id(norm(baseOf(name.toLowerCase(Locale.ROOT).trim()) + "[facing=" + face + ",half=" + half + "]"));
    }

    private short slab(String stairName, String type) {
        String b = baseOf(stairName.toLowerCase(Locale.ROOT).trim());
        b = b.endsWith("_stairs") ? b.substring(0, b.length() - 7) + "_slab" : b + "_slab";
        return id(norm(b + "[type=" + type + "]"));
    }

    private static String logAxis(String name, String axis) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        return n.contains("[") ? n : n + "[axis=" + axis + "]";
    }

    // ================= çatılar =================
    private void roofOp(Op o, boolean hip) {
        Box b = box(o);
        String st = o.s("m", "dark_oak_stairs");
        if (hip) hip(b.x1, b.z1, b.x2, b.z2, b.y1, st);
        else gable(b.x1, b.z1, b.x2, b.z2, b.y1, o.s("axis", (b.x2 - b.x1) >= (b.z2 - b.z1) ? "x" : "z").toLowerCase(Locale.ROOT), st,
                o.has("m2") ? pat(o.s("m2")) : null, o.has("gable") ? pat(o.s("gable")) : null, o.i("ginset", 0));
    }

    private void putR(boolean ax, int a, int s, int y, short id) { if (ax) put(a, y, s, id); else put(s, y, a, id); }

    /** Beşik çatı. axis="x": sırt x boyunca, eğim z yönünde. */
    private void gable(int x1, int z1, int x2, int z2, int y, String axis, String stairName, Pat ridge, Pat gablePat, int ginset) {
        boolean ax = axis.equals("x");
        int lo = ax ? z1 : x1, hi = ax ? z2 : x2, a0 = ax ? x1 : z1, a1 = ax ? x2 : z2;
        String loFace = ax ? "south" : "east", hiFace = ax ? "north" : "west";
        int W = hi - lo + 1;
        for (int k = 0; lo + k < hi - k; k++) {
            short sl = stair(stairName, loFace, "bottom"), sh = stair(stairName, hiFace, "bottom");
            for (int a = a0; a <= a1; a++) { putR(ax, a, lo + k, y + k, sl); putR(ax, a, hi - k, y + k, sh); }
        }
        if (W % 2 == 1) {
            int k = (W - 1) / 2;
            for (int a = a0; a <= a1; a++) {
                short id = ridge != null ? ridge.pick(a, y + k, lo + k) : slab(stairName, "bottom");
                putR(ax, a, lo + k, y + k, id);
            }
        }
        if (gablePat != null) {
            for (int a : new int[]{a0 + ginset, a1 - ginset}) {
                if (a < a0 || a > a1) continue;
                for (int k = 0; lo + k + 1 <= hi - k - 1; k++)
                    for (int s = lo + k + 1; s <= hi - k - 1; s++) {
                        int px = ax ? a : s, pz = ax ? s : a;
                        putIfFree(px, y + k, pz, gablePat.pick(px, y + k, pz));
                    }
            }
        }
    }

    private void hip(int x1, int z1, int x2, int z2, int y, String st) {
        for (int k = 0; ; k++) {
            int xa = x1 + k, xb = x2 - k, za = z1 + k, zb = z2 - k;
            if (xa > xb || za > zb) break;
            if (xb - xa < 1 || zb - za < 1) {
                for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) put(x, y + k, z, slab(st, "bottom"));
                break;
            }
            for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) {
                boolean edge = x == xa || x == xb || z == za || z == zb;
                if (!edge) continue;
                String f = z == za ? "south" : z == zb ? "north" : x == xa ? "east" : "west";
                put(x, y + k, z, stair(st, f, "bottom"));
            }
        }
    }

    // ================= ev =================
    private void house(Op o) {
        int xa = Math.min(o.i("x1", 0), o.i("x2", 0)), xb = Math.max(o.i("x1", 0), o.i("x2", 0));
        int za = Math.min(o.i("z1", 0), o.i("z2", 0)), zb = Math.max(o.i("z1", 0), o.i("z2", 0));
        int y0 = o.i("y1", 0);
        int stories = Math.max(1, Math.min(4, o.has("h") ? o.i("h", 4) / 4 : o.i("stories", 1)));
        int h = stories * 4;
        int W = xb - xa + 1, D = zb - za + 1;
        if (W < 5 || D < 5) throw new IllegalArgumentException("house en az 5x5 olmalı");
        Pat wall = pat(o.s("m", "white_terracotta")), base = pat(o.s("base", "stone_bricks")), floor = pat(o.s("floor", "spruce_planks"));
        String frameName = o.s("frame", "dark_oak_log");
        Pat fy = pat(logAxis(frameName, "y")), fx = pat(logAxis(frameName, "x")), fz = pat(logAxis(frameName, "z"));
        short glass = id(norm(o.s("glass", "glass_pane")));
        String roofSt = o.s("roof", "dark_oak_stairs");
        String axis = o.s("axis", W >= D ? "x" : "z").toLowerCase(Locale.ROOT);
        String doorFace = o.s("door", "south").toLowerCase(Locale.ROOT);
        if (!doorFace.matches("north|south|east|west")) doorFace = "south";
        String doorM = o.s("doorm", "spruce_door");
        boolean chim = o.b("chimney", true), lant = o.b("lantern", true);
        int ov = Math.max(0, Math.min(2, o.i("overhang", 1)));

        for (int y = y0 + 1; y <= y0 + h; y++) for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) put(x, y, z, (short) 1);
        for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) {
            boolean per = x == xa || x == xb || z == za || z == zb;
            put(x, y0, z, (per ? base : floor).pick(x, y0, z));
        }
        for (int s = 1; s < stories; s++)
            for (int x = xa + 1; x <= xb - 1; x++) for (int z = za + 1; z <= zb - 1; z++) put(x, y0 + 4 * s, z, floor.pick(x, y0 + 4 * s, z));

        int doorX = xa + (W - 1) / 2, doorZ = za + (D - 1) / 2;
        if (doorFace.equals("south")) doorZ = zb;
        else if (doorFace.equals("north")) doorZ = za;
        else if (doorFace.equals("east")) doorX = xb;
        else doorX = xa;

        for (int y = y0 + 1; y <= y0 + h; y++) for (int x = xa; x <= xb; x++) for (int z = za; z <= zb; z++) {
            if (!(x == xa || x == xb || z == za || z == zb)) continue;
            boolean onX = z == za || z == zb, onZ = x == xa || x == xb;
            boolean corner = onX && onZ;
            int L = onX ? W : D, u = onX ? x - xa : z - za;
            boolean doorWall = (doorFace.equals("south") && z == zb) || (doorFace.equals("north") && z == za)
                    || (doorFace.equals("east") && x == xb) || (doorFace.equals("west") && x == xa);
            int doorU = onX ? doorX - xa : doorZ - za;
            boolean nearDoor = doorWall && !corner && Math.abs(u - doorU) <= 1;
            int rel = y - y0;
            if (doorWall && !corner && u == doorU && rel <= 2) { put(x, y, z, (short) 1); continue; }
            Pat p;
            if (corner) p = fy;
            else if (rel % 4 == 0) p = onX ? fx : fz;
            else if (L >= 9 && u % 4 == 0 && (L - 1 - u) >= 3 && !nearDoor) p = fy;
            else if (rel == 1) p = base;
            else {
                int r = (rel - 1) % 4;
                boolean col = L >= 9 ? (u % 4 == 2 && (L - 1 - u) >= 2) : (u == (L - 1) / 2);
                if ((r == 1 || r == 2) && !nearDoor && col) { put(x, y, z, glass); continue; }
                p = wall;
            }
            put(x, y, z, p.pick(x, y, z));
        }
        doorAt(doorX, y0 + 1, doorZ, doorM, opp(doorFace), "left", false);

        int roofY = y0 + h + 1;
        boolean ax = axis.equals("x");
        gable(xa - ov, za - ov, xb + ov, zb + ov, roofY, axis, roofSt, null, wall, ov);
        int span = (ax ? D : W) + 2 * ov;
        int ridgeY = roofY + (span % 2 == 1 ? (span - 1) / 2 : span / 2 - 1);

        if (chim) {
            int cx = ax ? xb - 1 : xa + 1, cz = ax ? za + 1 : zb - 1;
            for (int y = y0 + h; y <= ridgeY + 2; y++) put(cx, y, cz, base.pick(cx, y, cz));
            put(cx, ridgeY + 3, cz, id(norm("cobblestone_wall")));
        }
        if (lant) {
            int[] d = dir(doorFace);
            for (int sgn : new int[]{-2, 2}) {
                int px = doorX + d[0] + (d[0] == 0 ? sgn : 0), pz = doorZ + d[1] + (d[1] == 0 ? sgn : 0);
                if (px < xa - 1 || px > xb + 1 || pz < za - 1 || pz > zb + 1) continue;
                put(px, y0 + 1, pz, id(norm("dark_oak_fence")));
                put(px, y0 + 2, pz, id(norm("dark_oak_fence")));
                put(px, y0 + 3, pz, id(norm("lantern[hanging=false]")));
            }
        }
        int fz2 = doorFace.equals("north") ? zb - 1 : za + 1;
        put(xa + 1, y0 + 1, fz2, id(norm("barrel[facing=up]")));
        put(xa + 2, y0 + 1, fz2, id(norm("crafting_table")));
        put(xa + 1, y0 + 2, fz2, id(norm("lantern[hanging=false]")));
    }

    private void doorOp(Op o) {
        doorAt(o.i("x1", 0), o.i("y1", 0), o.i("z1", 0), o.s("m", "spruce_door"), o.s("facing", "south").toLowerCase(Locale.ROOT),
                o.s("hinge", "left"), o.b("open", false));
    }

    private void doorAt(int x, int y, int z, String m, String facing, String hinge, boolean open) {
        if (!facing.matches("north|south|east|west")) facing = "south";
        String b = baseOf(m.toLowerCase(Locale.ROOT).trim());
        put(x, y, z, id(norm(b + "[facing=" + facing + ",half=lower,hinge=" + hinge + ",open=" + open + "]")));
        put(x, y + 1, z, id(norm(b + "[facing=" + facing + ",half=upper,hinge=" + hinge + ",open=" + open + "]")));
    }

    // ================= kule =================
    private void tower(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(2, o.i("r", 4)), h = Math.max(6, o.i("h", 20));
        Pat wall = pat(o.s("m", "stone_bricks")), trim = pat(o.s("m2", "chiseled_stone_bricks")), floor = pat(o.s("floor", "spruce_planks"));
        String top = o.s("top", "battlement").toLowerCase(Locale.ROOT);
        Pat roof = pat(o.s("roof", o.s("m2", "red_nether_bricks")));
        int rh = Math.max(3, o.i("rh", 2 * r + 3));
        int floors = Math.max(0, o.i("floors", 0));
        String doorFace = o.s("door", "south").toLowerCase(Locale.ROOT);
        boolean windows = o.b("windows", true);

        disc(cx, cy, cz, r + 1, r + 1, 1, false, 1, trim);
        disc(cx, cy + 1, cz, r - 1, r - 1, h, false, 1, air());
        disc(cx, cy + 1, cz, r, r, h, true, 1, wall);
        for (int rel = 6; rel <= h; rel += 6) disc(cx, cy + rel, cz, r, r, 1, true, 1, trim);
        if (windows) for (int w = 4; w + 1 < h - 2; w += 6) {
            for (int dy = 0; dy < 2; dy++) {
                put(cx + r, cy + w + dy, cz, (short) 1); put(cx - r, cy + w + dy, cz, (short) 1);
                put(cx, cy + w + dy, cz + r, (short) 1); put(cx, cy + w + dy, cz - r, (short) 1);
            }
        }
        for (int f = 1; floors > 0 && cy + f * floors < cy + h - 2; f++) disc(cx, cy + f * floors, cz, r - 1, r - 1, 1, false, 1, floor);
        disc(cx, cy + h - 1, cz, r - 1, r - 1, 1, false, 1, trim);
        if (floors > 0 && o.b("ladder", true)) {
            for (int y = cy + 1; y <= cy + h - 1; y++) put(cx, y, cz - (r - 1), id(norm("ladder[facing=south]")));
        }
        int tipY;
        if (top.equals("cone") || top.equals("spire") || top.equals("roof")) {
            coneAt(cx, cy + h + 1, cz, r + 1, rh, false, roof);
            tipY = cy + h + 1 + rh;
        } else {
            if (!top.equals("flat"))
                for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
                    double a = dx / (r + 0.5), c = dz / (r + 0.5);
                    double d2 = a * a + c * c;
                    if (d2 > 1 || d2 <= ((r - 1 + 0.5) / (r + 0.5)) * ((r - 1 + 0.5) / (r + 0.5))) continue;
                    if (((cx + dx + cz + dz) & 1) == 0) put(cx + dx, cy + h + 1, cz + dz, wall.pick(cx + dx, cy + h + 1, cz + dz));
                }
            tipY = cy + h + 2;
        }
        int[] d = dir(doorFace.matches("north|south|east|west") ? doorFace : "south");
        for (int dy = 1; dy <= 2; dy++) put(cx + d[0] * r, cy + dy, cz + d[1] * r, (short) 1);
        if (o.has("doorm")) doorAt(cx + d[0] * r, cy + 1, cz + d[1] * r, o.s("doorm"), opp(doorFace), "left", false);
        if (o.has("flag")) flagAt(cx, tipY, cz, 4, o.s("flag"), "east", 5, pat("spruce_fence"), null);
    }

    // ================= sütun / kemer / kapı =================
    private void pillarAt(int x, int y, int z, int h, Pat shaft, String baseSt, String capSt) {
        for (int k = 0; k < h; k++) put(x, y + k, z, shaft.pick(x, y + k, z));
        for (String f : new String[]{"north", "east", "south", "west"}) {
            int[] d = dir(f);
            int px = x + d[0], pz = z + d[1];
            String toward = opp(f);
            if (baseSt != null && !baseSt.isBlank()) put(px, y, pz, stair(baseSt, toward, "bottom"));
            if (capSt != null && !capSt.isBlank()) put(px, y + h - 1, pz, stair(capSt, toward, "top"));
        }
    }

    private void pillarOp(Op o) {
        int w = Math.max(1, o.i("w", 1)) | 1;
        int off = w / 2;
        for (int dx = -off; dx <= off; dx++) for (int dz = -off; dz <= off; dz++)
            pillarAt(o.i("x1", 0) + dx, o.i("y1", 0), o.i("z1", 0) + dz, Math.max(1, o.i("h", 6)), pat(o.s("m", "stone_bricks")),
                    w == 1 ? o.s("base", "") : "", w == 1 ? o.s("cap", "") : "");
    }

    private void colonnade(Op o) {
        int x1 = o.i("x1", 0), z1 = o.i("z1", 0), x2 = o.i("x2", x1), z2 = o.i("z2", z1), y = o.i("y1", 0);
        int h = Math.max(2, o.i("h", 6)), step = Math.max(2, o.i("s", 4));
        Pat shaft = pat(o.s("m", "stone_bricks"));
        String baseSt = o.s("base", ""), capSt = o.s("cap", "");
        int len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int n = Math.max(1, (int) Math.round(len / (double) step));
        for (int k = 0; k <= n; k++) {
            double t = k / (double) n;
            pillarAt((int) Math.round(x1 + (x2 - x1) * t), y, (int) Math.round(z1 + (z2 - z1) * t), h, shaft, baseSt, capSt);
        }
        if (o.has("beam")) {
            Pat bp = pat(o.s("beam"));
            for (int k = 0; k <= len; k++) {
                double t = len == 0 ? 0 : k / (double) len;
                int x = (int) Math.round(x1 + (x2 - x1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
                put(x, y + h, z, bp.pick(x, y + h, z));
            }
        }
    }

    private void arch(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        boolean ax = !o.s("axis", "x").equalsIgnoreCase("z");
        int w = Math.max(1, o.i("w", 5));
        if (w % 2 == 0) w++;
        int hw = w / 2, h = Math.max(1, o.i("h", 3)), th = Math.max(1, o.i("thick", 1)), depth = Math.max(1, Math.min(8, o.i("depth", 1)));
        Pat ring = pat(o.s("m", "stone_bricks"));
        Pat fillw = o.has("m2") ? pat(o.s("m2")) : null;
        int wallHalf = o.i("wall", 0), wallH = o.i("wallh", 0);
        boolean hole = o.b("hole", true);
        double Rr = hw + 0.5;
        for (int d = 0; d < depth; d++) {
            if (fillw != null && wallHalf > 0)
                for (int u = -wallHalf; u <= wallHalf; u++) for (int y = cy; y < cy + wallH; y++) putUD(ax, cx, cz, u, y, d, fillw);
            if (hole) {
                for (int u = -hw; u <= hw; u++) for (int y = cy; y < cy + h; y++) putUD(ax, cx, cz, u, y, d, air());
                for (int u = -hw; u <= hw; u++) for (int dy = 0; dy <= hw + 1; dy++)
                    if (u * u + dy * dy <= Rr * Rr) putUD(ax, cx, cz, u, cy + h + dy, d, air());
            }
            for (int u = -(hw + th); u <= hw + th; u++) for (int dy = 0; dy <= hw + th + 1; dy++) {
                double d2 = u * u + dy * dy;
                if (d2 > Rr * Rr && d2 <= (Rr + th) * (Rr + th)) putUD(ax, cx, cz, u, cy + h + dy, d, ring);
            }
            for (int y = cy; y < cy + h; y++) for (int t = 1; t <= th; t++) {
                putUD(ax, cx, cz, -(hw + t), y, d, ring);
                putUD(ax, cx, cz, hw + t, y, d, ring);
            }
        }
    }

    private void putUD(boolean ax, int cx, int cz, int u, int y, int d, Pat p) {
        int x = ax ? cx + u : cx + d, z = ax ? cz + d : cz + u;
        put(x, y, z, p.pick(x, y, z));
    }

    // ================= bayrak =================
    private void flag(Op o) {
        flagAt(o.i("x1", 0), o.i("y1", 0), o.i("z1", 0), Math.max(3, o.i("h", 8)), o.s("color", "red"),
                o.s("dir", "east").toLowerCase(Locale.ROOT), Math.max(2, o.i("len", 5)), pat(o.s("pole", "spruce_fence")), o.has("m") ? pat(o.s("m")) : null);
    }

    private void flagAt(int x, int y, int z, int h, String color, String dir, int len, Pat pole, Pat cloth) {
        if (!dir.matches("north|south|east|west")) dir = "east";
        if (cloth == null) cloth = pat(color.toLowerCase(Locale.ROOT).replace(" ", "_") + "_wool");
        for (int k = 0; k < h; k++) put(x, y + k, z, pole.pick(x, y + k, z));
        put(x, y + h, z, id(norm("end_rod[facing=up]")));
        int[] d = dir(dir);
        int px = d[0] == 0 ? 1 : 0, pz = d[1] == 0 ? 1 : 0;
        for (int row = 0; row < 3; row++) {
            int rl = len - row;
            for (int c = 1; c <= rl; c++) {
                int w = ((c + 1) / 2) % 2;
                int fx = x + d[0] * c + px * w, fz = z + d[1] * c + pz * w;
                put(fx, y + h - 1 - row, fz, cloth.pick(fx, y + h - 1 - row, fz));
            }
        }
    }

    // ================= ağaç =================
    private void blob(int cx, int cy, int cz, int rx, int ry, int rz, Pat leaf, double holes) {
        for (int dx = -rx; dx <= rx; dx++) for (int dy = -ry; dy <= ry; dy++) for (int dz = -rz; dz <= rz; dz++) {
            double a = dx / (rx + 0.5), b = dy / (ry + 0.5), c = dz / (rz + 0.5);
            double d = a * a + b * b + c * c;
            if (d > 1) continue;
            if (d > 0.6 && rnd(cx + dx, cy + dy, cz + dz, seed) < holes) continue;
            putIfFree(cx + dx, cy + dy, cz + dz, leaf.pick(cx + dx, cy + dy, cz + dz));
        }
    }

    private void tree(Op o) {
        int x = o.i("x1", 0), y = o.i("y1", 0), z = o.i("z1", 0);
        String kind = o.s("kind", "oak").toLowerCase(Locale.ROOT);
        String logN, leafN;
        int dh;
        switch (kind) {
            case "spruce", "pine" -> { logN = "spruce_log"; leafN = "spruce_leaves"; dh = 11; }
            case "birch" -> { logN = "birch_log"; leafN = "birch_leaves"; dh = 7; }
            case "dark_oak" -> { logN = "dark_oak_log"; leafN = "dark_oak_leaves"; dh = 9; }
            case "cherry" -> { logN = "cherry_log"; leafN = "cherry_leaves"; dh = 8; }
            case "acacia" -> { logN = "acacia_log"; leafN = "acacia_leaves"; dh = 8; }
            case "jungle" -> { logN = "jungle_log"; leafN = "jungle_leaves"; dh = 13; }
            case "bonsai" -> { logN = "dark_oak_log"; leafN = "azalea_leaves"; dh = 9; }
            default -> { kind = "oak"; logN = "oak_log"; leafN = "oak_leaves"; dh = 7; }
        }
        int h = Math.max(3, o.i("h", dh));
        Pat log = pat(o.s("log", logN) + (o.has("log") ? "" : "[axis=y]"));
        Pat leaf = pat(o.s("leaf", leafN));
        switch (kind) {
            case "spruce", "pine" -> {
                int maxR = Math.max(2, h / 3);
                for (int j = 0; j < h - 2; j++) {
                    int rj = Math.min(maxR, (j + 1) / 2);
                    int yy = y + h - j;
                    for (int dx = -rj; dx <= rj; dx++) for (int dz = -rj; dz <= rj; dz++)
                        if (dx * dx + dz * dz <= rj * rj + rj * 0.5) putIfFree(x + dx, yy, z + dz, leaf.pick(x + dx, yy, z + dz));
                }
                putIfFree(x, y + h + 1, z, leaf.pick(x, y + h + 1, z));
                for (int k = 0; k < h; k++) put(x, y + k, z, log.pick(x, y + k, z));
            }
            case "dark_oak" -> {
                int rr = Math.max(3, (int) Math.round(h * 0.55));
                blob(x, y + h, z, rr, 2, rr, leaf, 0.2);
                blob(x, y + h + 2, z, rr - 2, 1, rr - 2, leaf, 0.1);
                for (int k = 0; k < h; k++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) put(x + dx, y + k, z + dz, log.pick(x, y + k, z));
            }
            case "cherry" -> {
                blob(x, y + h, z, 3, 2, 3, leaf, 0.15);
                blob(x + 4, y + h - 1, z + 1, 3, 2, 3, leaf, 0.15);
                blob(x - 3, y + h + 1, z - 2, 3, 2, 3, leaf, 0.15);
                for (int k = 0; k < h; k++) put(x, y + k, z, log.pick(x, y + k, z));
                lineBlocks(x, y + h - 3, z, x + 4, y + h - 1, z + 1, log);
                lineBlocks(x, y + h - 2, z, x - 3, y + h + 1, z - 2, log);
            }
            case "acacia" -> {
                for (int k = 0; k < h - 2; k++) put(x, y + k, z, log.pick(x, y + k, z));
                lineBlocks(x, y + h - 3, z, x + 2, y + h - 1, z + 1, log);
                blob(x + 2, y + h, z + 1, 4, 1, 4, leaf, 0.15);
                lineBlocks(x, y + h - 4, z, x - 2, y + h - 2, z - 1, log);
                blob(x - 2, y + h - 1, z - 1, 3, 1, 3, leaf, 0.15);
            }
            case "bonsai" -> {
                int tx = x, tz = z;
                for (int k = 0; k < h; k++) {
                    tx = x + (int) Math.round(Math.sin(k * 0.7) * 1.4);
                    tz = z + (int) Math.round(Math.cos(k * 0.6) * 1.0);
                    put(tx, y + k, tz, log.pick(tx, y + k, tz));
                }
                blob(tx, y + h, tz, 4, 1, 4, leaf, 0.2);
                blob(tx + 3, y + h - 3, tz - 1, 3, 1, 3, leaf, 0.2);
                blob(tx - 3, y + h - 5, tz + 1, 3, 1, 3, leaf, 0.2);
            }
            default -> {
                int rx = Math.max(2, (int) Math.round(h * 0.45)), ry = Math.max(2, (int) Math.round(h * 0.35));
                blob(x, y + h - 1, z, rx, ry, rx, leaf, 0.18);
                for (int k = 0; k < h; k++) put(x, y + k, z, log.pick(x, y + k, z));
            }
        }
    }

    private void lineBlocks(int x1, int y1, int z1, int x2, int y2, int z2, Pat p) {
        int n = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        for (int k = 0; k <= n; k++) {
            double t = n == 0 ? 0 : k / (double) n;
            int x = (int) Math.round(x1 + (x2 - x1) * t), y = (int) Math.round(y1 + (y2 - y1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
            put(x, y, z, p.pick(x, y, z));
        }
    }

    // ================= tribün / dağıtma / yazı / kopyalama =================
    private void ringSeats(Op o) {
        int cx = o.i("x1", 0), cy = o.i("y1", 0), cz = o.i("z1", 0);
        int r = Math.max(2, o.i("r", 12)), rows = Math.max(1, Math.min(30, o.i("rows", 6)));
        String st = o.s("m", "spruce_stairs");
        Pat fill = pat(o.s("fill", "stone_bricks"));
        for (int k = 0; k < rows; k++) {
            int rk = r + k, y = cy + 1 + k;
            for (int dx = -rk - 1; dx <= rk + 1; dx++) for (int dz = -rk - 1; dz <= rk + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < rk - 0.5 || d >= rk + 0.5) continue;
                String f = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
                put(cx + dx, y, cz + dz, stair(st, f, "bottom"));
                for (int yy = cy; yy < y; yy++) put(cx + dx, yy, cz + dz, fill.pick(cx + dx, yy, cz + dz));
            }
        }
    }

    private void scatter(Op o) {
        Box b = box(o);
        if (b.empty()) return;
        Pat p = pat(o.s("m"));
        double prob = Math.max(0, Math.min(1, o.d("p", 0.1)));
        int dy = o.i("dy", 1);
        Set<String> on = new HashSet<>();
        if (o.has("on")) for (String s : o.s("on").toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(" ", "").split(",")) on.add(s);
        for (int x = b.x1; x <= b.x2; x++) for (int z = b.z1; z <= b.z2; z++) {
            int sup = Integer.MIN_VALUE;
            for (int y = b.y2; y >= b.y1; y--) if (grid[gi(x, y, z)] >= 2) { sup = y; break; }
            if (sup == Integer.MIN_VALUE) continue;
            if (!on.isEmpty() && !on.contains(baseOf(pal.get(grid[gi(x, sup, z)])))) continue;
            if (rnd(x, 99, z, seed) >= prob) continue;
            int ty = sup + dy;
            if (in(x, ty, z) && grid[gi(x, ty, z)] <= 1) put(x, ty, z, p.pick(x, ty, z));
        }
    }

    private void text(Op o) {
        int x = o.i("x1", 0), y = o.i("y1", 0), z = o.i("z1", 0);
        String s = o.s("s", o.s("text", ""));
        int sc = Math.max(1, Math.min(4, o.i("scale", 1))), depth = Math.max(1, Math.min(4, o.i("d", 1)));
        String face = o.s("face", "south").toLowerCase(Locale.ROOT);
        Pat p = pat(o.s("m", "white_concrete"));
        boolean[][] g = Glyphs.render(s);
        if (g[0].length == 0) return;
        for (int r = 0; r < 5; r++) for (int c = 0; c < g[0].length; c++) {
            if (!g[r][c]) continue;
            for (int sy = 0; sy < sc; sy++) for (int sxx = 0; sxx < sc; sxx++) for (int dd = 0; dd < depth; dd++) {
                int col = c * sc + sxx, yy = y + (4 - r) * sc + sy;
                int px, pz;
                switch (face) {
                    case "north" -> { px = x - col; pz = z + dd; }
                    case "east" -> { px = x - dd; pz = z - col; }
                    case "west" -> { px = x + dd; pz = z + col; }
                    default -> { px = x + col; pz = z - dd; }
                }
                put(px, yy, pz, p.pick(px, yy, pz));
            }
        }
    }

    private void copy(Op o) {
        Box b = box(o);
        if (b.empty()) return;
        int dx = o.i("dx", 0), dy = o.i("dy", 0), dz = o.i("dz", 0), n = Math.max(1, Math.min(o.i("n", 1), 60));
        String mir = o.s("mirror", "").toLowerCase(Locale.ROOT);
        boolean mx = mir.contains("x"), mz = mir.contains("z");
        int rot = ((o.i("rot", 0) / 90) % 4 + 4) % 4;
        boolean air = o.b("air", false);
        List<int[]> cells = new ArrayList<>();
        for (int y = b.y1; y <= b.y2; y++) for (int z = b.z1; z <= b.z2; z++) for (int x = b.x1; x <= b.x2; x++) {
            int g = grid[gi(x, y, z)];
            if (g == 0 || (g == 1 && !air)) continue;
            cells.add(new int[]{x, y, z, g});
        }
        double cxm = (b.x1 + b.x2) / 2.0, czm = (b.z1 + b.z2) / 2.0;
        Map<Integer, Short> xm = new HashMap<>();
        for (int k = 1; k <= n; k++) for (int[] c : cells) {
            int x = c[0], z = c[2];
            if (mx) x = b.x1 + b.x2 - x;
            if (mz) z = b.z1 + b.z2 - z;
            if (rot > 0) {
                double rx = x - cxm, rz = z - czm;
                for (int q = 0; q < rot; q++) { double t = -rz; rz = rx; rx = t; }
                x = (int) Math.round(cxm + rx); z = (int) Math.round(czm + rz);
            }
            short nid;
            if (c[3] == 1) nid = 1;
            else nid = xm.computeIfAbsent(c[3], key -> id(xform(pal.get(key), mx, mz, rot)));
            put(x + dx * k, c[1] + dy * k, z + dz * k, nid);
        }
    }

    private static String xform(String state, boolean mx, boolean mz, int rot) {
        String s = state;
        String f = prop(s, "facing");
        if (f != null && f.matches("north|south|east|west")) {
            if (mx && (f.equals("east") || f.equals("west"))) f = opp(f);
            if (mz && (f.equals("north") || f.equals("south"))) f = opp(f);
            for (int q = 0; q < rot; q++) f = cw(f);
            s = withProp(s, "facing", f);
        }
        String ax = prop(s, "axis");
        if (ax != null && rot % 2 == 1) { if (ax.equals("x")) s = withProp(s, "axis", "z"); else if (ax.equals("z")) s = withProp(s, "axis", "x"); }
        String hinge = prop(s, "hinge");
        if (hinge != null && ((mx ? 1 : 0) + (mz ? 1 : 0)) % 2 == 1) s = withProp(s, "hinge", hinge.equals("left") ? "right" : "left");
        String rt = prop(s, "rotation");
        if (rt != null) {
            try {
                int r = Integer.parseInt(rt);
                if (mx) r = (16 - r) % 16;
                if (mz) r = (24 - r) % 16;
                r = (r + 4 * rot) % 16;
                s = withProp(s, "rotation", String.valueOf(r));
            } catch (NumberFormatException ignored) {}
        }
        if (prop(s, "shape") != null && baseOf(s).endsWith("_stairs")) s = withProp(s, "shape", "straight");
        return s;
    }

    // ================= son işlemler =================
    private static String or(String v, String def) { return v == null ? def : v; }

    /** Merdiven köşelerini (inner/outer) vanilla kurallarıyla hesaplar. */
    private void postStairs() {
        int n = pal.size();
        boolean[] st = new boolean[n];
        String[] face = new String[n], half = new String[n];
        boolean any = false;
        for (int k = 2; k < n; k++) {
            String s = pal.get(k);
            if (!baseOf(s).endsWith("_stairs")) continue;
            st[k] = true; any = true;
            face[k] = or(prop(s, "facing"), "north");
            half[k] = or(prop(s, "half"), "bottom");
        }
        if (!any) return;
        List<int[]> upd = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        for (int g = 0; g < grid.length; g++) {
            int v = grid[g];
            if (v < 2 || v >= n || !st[v]) continue;
            int x = g % sx - R, t = g / sx, z = t % sz - R, y = t / sz + minY;
            String f = face[v];
            int[] d = dir(f);
            String shape = "straight";
            int b = get(x + d[0], y, z + d[1]);
            if (b >= 2 && b < n && st[b] && half[b].equals(half[v]) && !axisOf(face[b]).equals(axisOf(f))
                    && canTake(x, y, z, v, opp(face[b]), st, face, half, n)) {
                shape = face[b].equals(ccw(f)) ? "outer_left" : "outer_right";
            } else {
                int fr = get(x - d[0], y, z - d[1]);
                if (fr >= 2 && fr < n && st[fr] && half[fr].equals(half[v]) && !axisOf(face[fr]).equals(axisOf(f))
                        && canTake(x, y, z, v, face[fr], st, face, half, n)) {
                    shape = face[fr].equals(ccw(f)) ? "inner_left" : "inner_right";
                }
            }
            if (!shape.equals("straight")) { upd.add(new int[]{g, v}); shapes.add(shape); }
        }
        for (int k = 0; k < upd.size(); k++) grid[upd.get(k)[0]] = id(withProp(pal.get(upd.get(k)[1]), "shape", shapes.get(k)));
    }

    private boolean canTake(int x, int y, int z, int v, String faceDir, boolean[] st, String[] face, String[] half, int n) {
        int[] d = dir(faceDir);
        int nb = get(x + d[0], y, z + d[1]);
        return !(nb >= 2 && nb < n && st[nb] && face[nb].equals(face[v]) && half[nb].equals(half[v]));
    }

    /** Çit / duvar / cam panel / demir kafes bağlantılarını hesaplar. */
    private void postConnections() {
        int n = pal.size();
        String[] fam = new String[n];
        boolean[] solid = new boolean[n];
        boolean any = false;
        for (int k = 2; k < n; k++) {
            String b = baseOf(pal.get(k));
            if (b.endsWith("_fence") || b.equals("fence")) fam[k] = "fence";
            else if (b.endsWith("_wall")) fam[k] = "wall";
            else if (b.equals("iron_bars") || b.endsWith("_pane")) fam[k] = "pane";
            if (fam[k] != null) any = true;
            boolean sol;
            try { sol = cfg.solid.test(b); } catch (RuntimeException e) { sol = false; }
            solid[k] = sol;
        }
        if (!any) return;
        List<int[]> upd = new ArrayList<>();
        List<String> states = new ArrayList<>();
        String[] names = {"north", "east", "south", "west"};
        for (int g = 0; g < grid.length; g++) {
            int v = grid[g];
            if (v < 2 || v >= n || fam[v] == null) continue;
            int x = g % sx - R, t = g / sx, z = t % sz - R, y = t / sz + minY;
            boolean[] c = new boolean[4];
            for (int q = 0; q < 4; q++) {
                int[] d = dir(names[q]);
                int nb = get(x + d[0], y, z + d[1]);
                if (nb < 2 || nb >= n) continue;
                c[q] = fam[v].equals(fam[nb]) || solid[nb]
                        || (fam[v].equals("fence") && baseOf(pal.get(nb)).endsWith("_fence_gate"));
            }
            String s = pal.get(v);
            if (fam[v].equals("wall")) {
                for (int q = 0; q < 4; q++) s = withProp(s, names[q], c[q] ? "low" : "none");
                boolean straight = (c[0] && c[2] && !c[1] && !c[3]) || (c[1] && c[3] && !c[0] && !c[2]);
                int above = get(x, y + 1, z);
                s = withProp(s, "up", (!straight || above >= 2) ? "true" : "false");
            } else {
                for (int q = 0; q < 4; q++) s = withProp(s, names[q], String.valueOf(c[q]));
            }
            upd.add(new int[]{g});
            states.add(s);
        }
        for (int k = 0; k < upd.size(); k++) grid[upd.get(k)[0]] = id(states.get(k));
    }

    // ================= sonuç =================
    private Result collect() {
        Result r = new Result();
        r.palette = new ArrayList<>(pal);
        r.warnings = warn;
        int solid = 0, airc = 0;
        for (short v : grid) { if (v >= 2) solid++; else if (v == 1) airc++; }
        int limit = cfg.maxBlocks;
        int cap = Math.min(solid, limit) + airc;
        r.xs = new int[cap]; r.ys = new int[cap]; r.zs = new int[cap]; r.pi = new short[cap];
        int n = 0, kept = 0;
        boolean truncated = false;
        for (int y = minY; y < minY + sy; y++) for (int z = -R; z <= R; z++) for (int x = -R; x <= R; x++) {
            short v = grid[gi(x, y, z)];
            if (v == 0) continue;
            if (v >= 2) {
                if (kept >= limit) { truncated = true; continue; }
                kept++;
            }
            r.xs[n] = x; r.ys[n] = y; r.zs[n] = z; r.pi[n] = v; n++;
        }
        r.n = n;
        r.solidCount = kept;
        r.airCount = airc;
        if (truncated) r.warnings.add("max-blocks (" + limit + ") aşıldı, üst katmanlar kesildi");
        return r;
    }

    static boolean guessSolid(String b) {
        if (b.endsWith("_stairs") || b.endsWith("_slab") || b.endsWith("_fence") || b.endsWith("_pane") || b.endsWith("_door")
                || b.endsWith("_trapdoor") || b.endsWith("_leaves") || b.endsWith("_sign") || b.endsWith("lantern") || b.endsWith("torch")
                || b.equals("iron_bars") || b.equals("air") || b.endsWith("_carpet") || b.endsWith("_banner")) return false;
        for (String k : new String[]{"planks", "log", "wood", "stone", "brick", "terracotta", "concrete", "deepslate", "blackstone", "quartz",
                "sandstone", "andesite", "diorite", "granite", "cobble", "basalt", "prismarine", "wool", "dirt", "grass_block", "sand",
                "barrel", "bookshelf", "obsidian", "block", "tuff", "calcite", "copper", "ice", "snow_block"})
            if (b.contains(k)) return true;
        return false;
    }
}
