package com.buildyapayzeka;

import java.util.*;

/** Küçük, bağımsız JSON okuyucu. Bozuk/yarım kalmış AI cevaplarından kurtarma yapabilir. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s, int start) { this.s = s; this.i = start; }

    public static Object parse(String text) {
        Json j = new Json(text, 0);
        j.ws();
        Object v = j.value();
        return v;
    }

    /** Metindeki ilk '{' ile son '}' arasını okur. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> firstObject(String text) {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b < a) throw new IllegalArgumentException("JSON bulunamadı");
        try {
            Object o = parse(text.substring(a, b + 1));
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (RuntimeException ignored) {}
        // Son çare: baştan okuyabildiğimiz kadarını oku
        Json j = new Json(text, a);
        Object o = j.value();
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("JSON geçersiz");
    }

    /** Metindeki ilk '{' ile son '}' arasını ham metin olarak döndürür. */
    public static String firstObjectText(String text) {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        return a >= 0 && b > a ? text.substring(a, b + 1) : text;
    }

    /** "ops": [ ... ] dizisindeki tamamlanmış nesneleri tek tek okur; yarım kalan sonuncuyu atar. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> salvageOps(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        int k = text.indexOf("\"ops\"");
        if (k < 0) return out;
        int a = text.indexOf('[', k);
        if (a < 0) return out;
        Json j = new Json(text, a + 1);
        while (true) {
            try {
                j.ws();
                while (j.i < j.s.length() && (j.s.charAt(j.i) == ',' )) { j.i++; j.ws(); }
                if (j.i >= j.s.length() || j.s.charAt(j.i) == ']') break;
                if (j.s.charAt(j.i) != '{') { j.i++; continue; }
                Object o = j.value();
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            } catch (RuntimeException e) {
                break;
            }
        }
        return out;
    }

    public static String quote(String x) {
        StringBuilder b = new StringBuilder("\"");
        for (int n = 0; n < x.length(); n++) {
            char c = x.charAt(n);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ---------- okuyucu ----------
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else break;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw new IllegalArgumentException("beklenmedik son");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        while (true) {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("nesne kapanmadı");
            char c = s.charAt(i);
            if (c == '}') { i++; return m; }
            if (c == ',') { i++; continue; }
            if (c != '"') throw new IllegalArgumentException("anahtar bekleniyordu @" + i);
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException(": bekleniyordu @" + i);
            i++;
            m.put(k, value());
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        while (true) {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("dizi kapanmadı");
            char c = s.charAt(i);
            if (c == ']') { i++; return l; }
            if (c == ',') { i++; continue; }
            l.add(value());
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++; // "
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("kısa \\u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            } else b.append(c);
        }
        throw new IllegalArgumentException("metin kapanmadı");
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("beklenmedik karakter '" + s.charAt(i) + "' @" + i);
        return Double.parseDouble(s.substring(st, i));
    }
}
