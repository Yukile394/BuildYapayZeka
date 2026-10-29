package com.buildyapayzeka;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AIClient {
    private final BuildYapayZeka pl;
    private static final String SYSTEM = """
        You are BuildYapayZeka, an expert Minecraft builder bot for a Paper server (1.21+).
        The player describes what to build (usually in Turkish). Reply with ONLY one JSON object, no markdown:
        {"message":"short Turkish chat reply describing what you will build","ops":[ ... ]}

        Coordinates are relative to the build center (0,0,0). y=0 is the ground/floor layer, build upward (y>=1).
        Keep x and z within -70..70 and y within 0..60. Use ONLY these ops:
        {"t":"clear","x1":..,"y1":..,"z1":..,"x2":..,"y2":..,"z2":..}            fills AIR (do this FIRST for the whole build area)
        {"t":"fill","m":"STONE_BRICKS","x1":..,"y1":..,"z1":..,"x2":..,"y2":..,"z2":..}   solid box
        {"t":"hollow","m":"...","x1":..,"y1":..,"z1":..,"x2":..,"y2":..,"z2":..}         box shell (walls/floor/ceiling only)
        {"t":"cyl","m":"...","x1":cx,"y1":baseY,"z1":cz,"r":radius,"h":height,"hollow":false}  vertical cylinder
        {"t":"set","m":"CHEST","x1":..,"y1":..,"z1":..}                                    single block
        Materials must be valid Bukkit Material block names (uppercase, e.g. QUARTZ_BLOCK, SEA_LANTERN, CHEST, BARREL,
        ENCHANTING_TABLE, ANVIL, CRAFTING_TABLE, SMITHING_TABLE, LECTERN, BEACON, GOLD_BLOCK, WATER).
        Design rules: professional, detailed, symmetrical; mix 3-5 materials per structure (floor patterns, trim, pillars);
        add lighting (SEA_LANTERN/GLOWSTONE) so no dark spots; add entrances/gates; use dedicated zones when asked
        (trade stalls with BARREL tables, chest rooms with CHEST/BARREL/ENDER_CHEST rows, PvP arenas, 1v1 rooms with gold pads,
        spectator areas). Prefer big efficient ops (fill/hollow/cyl) over many set ops. Max 600 ops. Be generous and complete.
        """;

    public AIClient(BuildYapayZeka pl) { this.pl = pl; }

    public boolean hasKey() { return !pl.getConfig().getString("anthropic-api-key", "").isBlank(); }

    public Plan plan(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", pl.getConfig().getString("model", "claude-sonnet-5-5"));
        body.addProperty("max_tokens", 12000);
        body.addProperty("system", SYSTEM);
        JsonArray msgs = new JsonArray();
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", prompt);
        msgs.add(u);
        body.add("messages", msgs);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(240))
                .header("content-type", "application/json")
                .header("x-api-key", pl.getConfig().getString("anthropic-api-key"))
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("API " + res.statusCode());

        JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
        String text = j.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b < a) throw new RuntimeException("Geçersiz cevap");
        JsonObject plan = JsonParser.parseString(text.substring(a, b + 1)).getAsJsonObject();
        List<Op> ops = new ArrayList<>();
        int max = pl.getConfig().getInt("max-ops", 700);
        for (JsonElement e : plan.getAsJsonArray("ops")) {
            if (ops.size() >= max) break;
            ops.add(Op.from(e.getAsJsonObject()));
        }
        String msg = plan.has("message") ? plan.get("message").getAsString() : "İnşaata başlıyorum!";
        return new Plan(msg, ops);
    }
}
