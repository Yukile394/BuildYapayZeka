package com.buildyapayzeka;

import java.util.*;

/**
 * Tek bir inşa komutu. t = komut adı, a = parametreler (x1,y1,z1,x2,y2,z2,m,r,h ...).
 * Koordinatlar yapı merkezine göre göreli; y=0 oyuncunun bastığı zemin.
 */
public record Op(String t, Map<String, Object> a) {

    public boolean has(String k) { return a.containsKey(k) && a.get(k) != null; }

    public String s(String k, String def) {
        Object v = a.get(k);
        if (v == null) return def;
        String r = String.valueOf(v);
        if (v instanceof Double d && d == Math.rint(d)) r = String.valueOf(d.longValue());
        return r;
    }

    public String s(String k) { return s(k, ""); }

    public double d(String k, double def) {
        Object v = a.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String str) { try { return Double.parseDouble(str.trim()); } catch (Exception ignored) {} }
        return def;
    }

    public int i(String k, int def) {
        Object v = a.get(k);
        if (v instanceof Number n) return (int) Math.round(n.doubleValue());
        if (v instanceof String str) { try { return (int) Math.round(Double.parseDouble(str.trim())); } catch (Exception ignored) {} }
        return def;
    }

    public boolean b(String k, boolean def) {
        Object v = a.get(k);
        if (v instanceof Boolean x) return x;
        if (v instanceof String str) return str.equalsIgnoreCase("true") || str.equals("1");
        if (v instanceof Number n) return n.doubleValue() != 0;
        return def;
    }

    /** AI'dan gelen ham nesneyi normalize eder (anahtar adları, kısaltmalar). */
    public static Op from(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) m.put(e.getKey().toLowerCase(Locale.ROOT).trim(), e.getValue());
        String t = "";
        for (String k : new String[]{"t", "type", "op", "cmd"}) if (m.get(k) != null) { t = String.valueOf(m.get(k)); break; }
        t = t.toLowerCase(Locale.ROOT).trim();
        alias(m, "m", "material", "block", "pattern", "mat");
        alias(m, "x1", "x");
        alias(m, "y1", "y");
        alias(m, "z1", "z");
        alias(m, "r", "radius");
        alias(m, "h", "height");
        alias(m, "m2", "trim", "secondary");
        return new Op(t, m);
    }

    private static void alias(Map<String, Object> m, String main, String... others) {
        if (m.get(main) != null) return;
        for (String o : others) if (m.get(o) != null) { m.put(main, m.get(o)); return; }
    }

    // ---------- Templates.java için hazır fabrikalar ----------
    private static Op mk(String t, String m, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("m", m);
        for (int n = 0; n + 1 < kv.length; n += 2) a.put((String) kv[n], kv[n + 1]);
        return new Op(t, a);
    }

    static Op fill(String m, int x1, int y1, int z1, int x2, int y2, int z2) {
        return mk("fill", m, "x1", x1, "y1", y1, "z1", z1, "x2", x2, "y2", y2, "z2", z2);
    }
    static Op hollow(String m, int x1, int y1, int z1, int x2, int y2, int z2) {
        return mk("hollow", m, "x1", x1, "y1", y1, "z1", z1, "x2", x2, "y2", y2, "z2", z2);
    }
    static Op cyl(String m, int cx, int cy, int cz, int r, int h, boolean hol) {
        return mk("cyl", m, "x1", cx, "y1", cy, "z1", cz, "r", r, "h", h, "hollow", hol);
    }
    static Op set(String m, int x, int y, int z) { return mk("set", m, "x1", x, "y1", y, "z1", z); }
}
