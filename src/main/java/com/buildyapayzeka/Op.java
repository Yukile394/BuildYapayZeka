package com.buildyapayzeka;

import com.google.gson.JsonObject;

/** t: fill | hollow | cyl | set | clear. Koordinatlar merkeze göre göreli. cyl: x1,y1,z1 taban merkezi, r yarıçap, y2 = yükseklik. */
public record Op(String t, String m, int x1, int y1, int z1, int x2, int y2, int z2, int r, boolean hollow) {
    static String s(JsonObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.get(k).isJsonNull()) return o.get(k).getAsString();
        return "";
    }
    static int i(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? (int) Math.round(o.get(k).getAsDouble()) : 0;
    }
    public static Op from(JsonObject o) {
        String t = s(o, "t", "type", "op").toLowerCase();
        int y2 = o.has("h") ? i(o, "h") : i(o, "y2");
        return new Op(t, s(o, "m", "material").toUpperCase(), i(o, "x1"), i(o, "y1"), i(o, "z1"),
                i(o, "x2"), y2, i(o, "z2"), i(o, "r"), o.has("hollow") && o.get("hollow").getAsBoolean());
    }
    static Op fill(String m, int a, int b, int c, int d, int e, int f) { return new Op("fill", m, a, b, c, d, e, f, 0, false); }
    static Op hollow(String m, int a, int b, int c, int d, int e, int f) { return new Op("hollow", m, a, b, c, d, e, f, 0, false); }
    static Op cyl(String m, int cx, int cy, int cz, int r, int h, boolean hol) { return new Op("cyl", m, cx, cy, cz, 0, h, 0, r, hol); }
    static Op set(String m, int x, int y, int z) { return new Op("set", m, x, y, z, x, y, z, 0, false); }
}
