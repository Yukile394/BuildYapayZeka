package com.buildyapayzeka;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Çok aşamalı yapay zeka planlayıcı:
 *  1) Mimar: isteği palet + bölgelere (zone) ayıran bir plan (blueprint) üretir.
 *  2) Ustalar: her bölge için ayrı, ayrıntılı op listesi üretir (paralel).
 * Op listesi Expander tarafından WorldEdit tarzı bloklara çevrilir.
 */
public class AIClient {
    private final BuildYapayZeka pl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Deque<String> recent = new ArrayDeque<>();

    public AIClient(BuildYapayZeka pl) { this.pl = pl; }

    // --- Anahtar okuma: boşlukları ve tırnakları temizler ---
    private String cfgKey(String path) {
        String v = pl.getConfig().getString(path, "");
        return v == null ? "" : v.trim().replace("\"", "").replace("'", "");
    }

    /** Google anahtarları "AIza" ya da "AQ." ile başlar. Yanlış alana yazılsa bile Gemini olarak tanınır. */
    private static boolean looksGemini(String k) { return k.startsWith("AIza") || k.startsWith("AQ."); }

    private String geminiKey() {
        String g = cfgKey("gemini-api-key");
        if (!g.isEmpty()) return g;
        String a = cfgKey("anthropic-api-key");
        return looksGemini(a) ? a : "";
    }

    private String anthropicKey() {
        String a = cfgKey("anthropic-api-key");
        return looksGemini(a) ? "" : a;
    }

    private boolean hasAnthropic() { return !anthropicKey().isEmpty(); }

    private boolean hasGemini() { return !geminiKey().isEmpty(); }

    /** provider: auto | gemini | anthropic. auto: Anthropic anahtarı varsa o, yoksa Gemini. */
    private boolean useGemini() {
        String p = pl.getConfig().getString("provider", "auto").trim().toLowerCase(Locale.ROOT);
        if (p.equals("gemini")) return true;
        if (p.equals("anthropic")) return false;
        return !hasAnthropic() && hasGemini();
    }

    public boolean hasKey() { return useGemini() ? hasGemini() : hasAnthropic(); }

    public String provider() {
        return useGemini() ? "Gemini: " + geminiModels() : "Claude: " + pl.getConfig().getString("model", "claude-sonnet-5-5");
    }

    /** /bot durum için teşhis: anahtarların gerçekten okunup okunmadığını gösterir. */
    public String diagnose() {
        String g = geminiKey(), a = anthropicKey();
        return "provider=" + pl.getConfig().getString("provider", "auto")
                + " | gemini-key=" + (g.isEmpty() ? "YOK" : "VAR(" + g.length() + " karakter)")
                + " | anthropic-key=" + (a.isEmpty() ? "YOK" : "VAR(" + a.length() + " karakter)")
                + " | aktif=" + (hasKey() ? provider() : "HİÇBİRİ -> şablon");
    }

    private List<String> geminiModels() {
        List<String> m = pl.getConfig().getStringList("gemini-models");
        return m == null || m.isEmpty() ? List.of("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash") : m;
    }

    // ======================= PROMPTLAR =======================

    private static final String STYLE = """
        STYLE REFERENCES (the owner's favourite builds - aim for this level of craft, but never copy one blindly):
        1) MEDIEVAL MARKET SPAWN: cobblestone/andesite/stone-brick plaza with worn patterns, timber-frame houses (spruce/oak planks or white terracotta infill,
           dark-oak beams, steep red/brick gable roofs with overhangs, chimneys), market stalls with striped red/white awnings, lantern posts, stone-brick planters
           with grass and flowers edged by slabs, a white stone (calcite/diorite/snow) cliff with spruce trees behind, a stone-brick gate holding a glowing purple portal.
        2) FLOATING ISLAND CASTLE: jagged floating island (stone/deepslate/dirt, mossy, hanging spikes underneath), pink/white quartz-like crystal spires and amethyst
           glow below, bonsai-style dark-oak and azalea trees, tall slender towers with golden/orange pointed roofs, bridges between towers.
        3) ARENA: round stone-brick colosseum, sand/smooth-sandstone floor with a red brick ring pattern and a central monument, tiered wooden/stone seating,
           crenellated outer wall with arrow-slit gaps, two big towers with red cone roofs and coloured flags, lanterns, flower boxes with vines.
        4) PILLARED HUB: grand courtyard of light stone-brick and chiseled blocks, tall pillars with stair capitals, slate pitched roofs with timber beams,
           grass + small trees + geometric floor pattern in the centre, giant sword statues, huge block-letter signs (CRATES / RULES / SHOP) above gateways.
        """;

    private static final String OPS = """
        COORDINATES: relative to the build centre (0,0,0). x=east, z=south, y=up. y=0 is the GROUND SURFACE (top layer of the ground the player stands on).
        Building floors/foundations sit at y=0, walls start at y=1. Ground fill goes below (y=-1..-3). Allowed range: x,z in [-%R%,%R%], y in [%MINY%,%MAXY%].
        Every op is one JSON object on its own line. Later ops overwrite earlier ones in the same cell.

        BLOCK ARGUMENT "m" IS A WORLDEDIT-STYLE PATTERN:
          "STONE_BRICKS"                                              one block
          "60%STONE_BRICKS,25%MOSSY_STONE_BRICKS,15%CRACKED_STONE_BRICKS"   weighted random mix (use for floors, walls, ground = natural look)
          "SPRUCE_STAIRS[facing=north,half=top]", "LANTERN[hanging=true]", "OAK_SLAB[type=top]"   block states in [] (facing = direction the HIGH side of a stair points)
        Use valid Minecraft block ids (uppercase ok). Leaves are automatically persistent. Stairs corners and fence/wall/pane connections are fixed automatically.

        BASIC OPS (box = x1,y1,z1 -> x2,y2,z2 in any order):
          {"t":"clear", box}                          air
          {"t":"fill","m":P, box}                      solid box
          {"t":"hollow","m":P, box}                    shell: all 6 faces
          {"t":"walls","m":P, box}                     4 vertical faces only
          {"t":"replace","from":"STONE_BRICKS,COBBLESTONE" or "*","m":P, box}   re-texture what is already built in the box (weathering, accents)
          {"t":"set","m":P,"x1":..,"y1":..,"z1":..}    single block (stairs, slabs, doors, lanterns, beds, banners, signs, chests with states)
          {"t":"line","m":P, x1,y1,z1,x2,y2,z2, "r":0} 3D line; r>0 = flat path that is 2r+1 wide (roads, rivers, bridges)
          {"t":"cyl","m":P,"x1":cx,"y1":baseY,"z1":cz,"r":R,"rz":R2(optional ellipse),"h":H,"hollow":false,"thick":1}
          {"t":"sphere","m":P,"x1":cx,"y1":cy,"z1":cz,"r":R | "rx","ry","rz","hollow":false,"half":"top"|"bottom"}   domes, ellipsoids
          {"t":"cone","m":P,"x1":cx,"y1":baseY,"z1":cz,"r":R,"h":H,"hollow":false}    round roofs, spires
          {"t":"pyramid","m":P,"x1":cx,"y1":baseY,"z1":cz,"r":halfSize,"step":1,"hollow":false}
        ORGANIC TERRAIN:
          {"t":"island","x1":cx,"y1":topY,"z1":cz,"r":R,"depth":D,"m":TOP,"soil":P,"body":P,"rough":0.35,"bump":0}   floating island, jagged underside, extends DOWN from y1
          {"t":"mound","x1":cx,"y1":baseY,"z1":cz,"r":R,"h":H,"m":SURFACE,"soil":P,"body":P,"cap":P(snow/calcite on high parts),"capfrom":0.75,"rough":0.3,"steep":2.2}   hills, cliffs, mountains, extends UP
        ARCHITECTURE MACROS (use them a lot - they create professional detail automatically):
          {"t":"house","x1":..,"z1":..,"x2":..,"z2":..,"y1":0,"stories":1,"m":WALL_INFILL,"frame":"DARK_OAK_LOG","base":P,"floor":P,"roof":"BRICK_STAIRS","glass":"GLASS_PANE","door":"south","doorm":"SPRUCE_DOOR","axis":"x","overhang":1,"chimney":true,"lantern":true}
              timber-frame house, min 5x5, 1-4 stories: plinth, corner posts, beams, windows, gable roof with overhang, door, lanterns, chimney, furniture.
              "axis" = ridge direction (x or z), "door" = side of the entrance (north/south/east/west).
          {"t":"tower","x1":cx,"y1":0,"z1":cz,"r":4,"h":20,"m":WALL,"m2":TRIM,"top":"battlement"|"cone"|"flat","roof":P,"rh":coneHeight,"floors":5,"door":"south","doorm":"SPRUCE_DOOR","flag":"red","windows":true}
          {"t":"gable_roof","x1":..,"z1":..,"x2":..,"z2":..,"y1":firstRoofLevel,"axis":"x"|"z","m":"STONE_BRICK_STAIRS","m2":ridgeP,"gable":P,"ginset":0}
          {"t":"hip_roof","x1":..,"z1":..,"x2":..,"z2":..,"y1":firstRoofLevel,"m":"DARK_OAK_STAIRS"}
          {"t":"pillar","x1":..,"y1":..,"z1":..,"h":6,"m":P,"base":"STONE_BRICK_STAIRS","cap":"STONE_BRICK_STAIRS","w":1}
          {"t":"colonnade","x1":..,"z1":..,"x2":..,"z2":..,"y1":0,"h":6,"s":4,"m":P,"base":STAIRS,"cap":STAIRS,"beam":P}   row of pillars + beam on top
          {"t":"arch","x1":cx,"y1":baseY,"z1":cz,"axis":"x"|"z","w":5,"h":3,"thick":1,"depth":2,"m":RING_P,"m2":WALL_P,"wall":8,"wallh":9,"hole":true}   gate/portal frame in a wall
          {"t":"door","x1":..,"y1":..,"z1":..,"m":"SPRUCE_DOOR","facing":"north","hinge":"left","open":false}
          {"t":"flag","x1":..,"y1":..,"z1":..,"h":8,"color":"red","dir":"east","len":5}
          {"t":"tree","x1":..,"y1":1,"z1":..,"kind":"oak|spruce|birch|dark_oak|cherry|acacia|jungle|bonsai","h":7}
          {"t":"ring_seats","x1":cx,"y1":0,"z1":cz,"r":12,"rows":6,"m":"SPRUCE_STAIRS","fill":P}   circular tiered stands
          {"t":"scatter","m":"60%SHORT_GRASS,15%POPPY,15%DANDELION,10%FERN", box,"p":0.15,"on":"GRASS_BLOCK"}   random decoration on the surface already built
          {"t":"text","s":"CRATES","x1":..,"y1":..,"z1":..,"scale":2,"face":"south","m":"WHITE_CONCRETE","d":1}   giant block letters, 5*scale high; face = side the reader stands on, x1/z1 = left end; Turkish letters are simplified
          {"t":"copy", box,"dx":..,"dy":..,"dz":..,"n":1,"mirror":"x"|"z"|"xz","rot":0|90|180|270}   clone what is ALREADY built in box (WorldEdit copy/paste/stack/flip/rotate).
              Build one tower/house/stall once, then clone it. Mirror flips inside the box, then offsets: to mirror around the line x=M use dx=2*M-x1-x2.

        PREFERRED BLOCKS: STONE_BRICKS MOSSY_STONE_BRICKS CRACKED_STONE_BRICKS CHISELED_STONE_BRICKS STONE_BRICK_STAIRS STONE_BRICK_SLAB STONE_BRICK_WALL COBBLESTONE
          MOSSY_COBBLESTONE ANDESITE POLISHED_ANDESITE DIORITE POLISHED_DIORITE CALCITE SMOOTH_STONE DEEPSLATE_BRICKS DEEPSLATE_TILES POLISHED_DEEPSLATE CHISELED_DEEPSLATE
          POLISHED_BLACKSTONE_BRICKS BLACKSTONE GILDED_BLACKSTONE QUARTZ_BLOCK QUARTZ_BRICKS CHISELED_QUARTZ_BLOCK QUARTZ_PILLAR SMOOTH_SANDSTONE CUT_SANDSTONE CHISELED_SANDSTONE
          SPRUCE_PLANKS OAK_PLANKS DARK_OAK_PLANKS BIRCH_PLANKS JUNGLE_PLANKS CHERRY_PLANKS (each also _STAIRS _SLAB _FENCE _FENCE_GATE _TRAPDOOR _DOOR) *_LOG *_WOOD STRIPPED_*_LOG
          BRICKS BRICK_STAIRS BRICK_SLAB NETHER_BRICKS RED_NETHER_BRICKS RED_NETHER_BRICK_STAIRS WHITE_TERRACOTTA ORANGE_TERRACOTTA TERRACOTTA *_CONCRETE *_WOOL *_STAINED_GLASS GLASS GLASS_PANE
          GRASS_BLOCK DIRT DIRT_PATH COARSE_DIRT PODZOL GRAVEL SAND MOSS_BLOCK MOSS_CARPET SNOW_BLOCK OAK_LEAVES SPRUCE_LEAVES AZALEA_LEAVES FLOWERING_AZALEA_LEAVES CHERRY_LEAVES VINE
          SHORT_GRASS FERN POPPY DANDELION CORNFLOWER AZURE_BLUET ALLIUM LILAC ROSE_BUSH PEONY LILY_OF_THE_VALLEY SWEET_BERRY_BUSH FLOWERING_AZALEA
          LANTERN SOUL_LANTERN SEA_LANTERN GLOWSTONE SHROOMLIGHT END_ROD CHAIN IRON_BARS CAMPFIRE TORCH CANDLE
          BARREL CHEST ENDER_CHEST CRAFTING_TABLE SMITHING_TABLE ANVIL ENCHANTING_TABLE LECTERN BOOKSHELF LOOM BREWING_STAND CAULDRON FLOWER_POT HAY_BLOCK BELL
          WATER (still), GOLD_BLOCK IRON_BLOCK DIAMOND_BLOCK EMERALD_BLOCK AMETHYST_BLOCK AMETHYST_CLUSTER[facing=up] COPPER_BLOCK CUT_COPPER WAXED_CUT_COPPER OXIDIZED_CUT_COPPER
          Not allowed: command blocks, barriers, TNT, lava, bedrock, spawners, portals.
        """;

    private static final String BLUEPRINT_SYSTEM = """
        You are the lead architect of an elite Minecraft build team. The server is a Paper 1.21 server called Silvera.
        The player (usually writing Turkish) describes what to build. Turn it into a BLUEPRINT that your builders will execute in parallel.
        Output ONLY one JSON object, no markdown:
        {"title":"short name",
         "message":"1-2 sentence Turkish chat message to the player describing the concept",
         "theme":"3-5 sentences: architectural style, mood, signature features, how the zones relate",
         "palette":{"ground":"PATTERN","path":"PATTERN","wall":"PATTERN","trim":"PATTERN","pillar":"PATTERN","floor":"PATTERN","roof_stairs":"STAIRS_NAME","roof_block":"PATTERN","wood_frame":"LOG_NAME","glass":"GLASS_PANE","light":"BLOCK","plant":"PATTERN","accent":"PATTERN"},
         "zones":[{"id":"A","name":"...","role":"terrain|plaza|building|tower|wall|landmark|decor|path|interior|arena","x1":-60,"z1":-60,"x2":60,"z2":60,"max_height":30,"brief":"..."}]}

        RULES
        - The palette uses WorldEdit patterns: weighted mixes like "60%STONE_BRICKS,25%MOSSY_STONE_BRICKS,15%CRACKED_STONE_BRICKS" make builds look natural. 8-14 palette roles, choose roles that fit the request (add or rename freely).
        - Coordinates are relative to the build centre (0,0,0), x,z within [-%R%,%R%]. y=0 is the ground surface.
        - Zone 1 MUST have role "terrain": its box covers the whole build footprint; builders create ground, paths, plazas, hills/cliffs/islands there (no buildings).
        - Use 4 to %ZONES% zones in total. One zone = one cohesive structure or area, at most about 45x45 blocks (split big things: keep, walls, towers, gatehouse ...).
        - Zones may overlap only on purpose (e.g. trees/decor inside a plaza). Leave walkable paths. Entrances face the central plaza.
        - "brief" (60-150 words) must be concrete: exact structures, sizes, counts, positions inside the zone, palette roles to use, sign texts, lighting, special features.
          Builders only see the blueprint and their own brief, so put everything they need to know in it.
        - Obey explicit requests exactly (named areas, counts, sizes, signs). If the player gives few details, invent a rich, coherent design with many different structures and landmarks.
        - Make it large and impressive: plaza + several buildings + landmark + vegetation + lighting. Avoid empty space.
        """ + STYLE;

    private static final String ZONE_SYSTEM = """
        You are a master Minecraft builder and WorldEdit expert on a Paper 1.21 server. You get a BLUEPRINT and ONE ZONE.
        Build that zone to an exceptional standard, comparable to professional server-spawn builds.
        Output ONLY one JSON object: {"ops":[ ... ]} with one op per line, no markdown, no comments.

        QUALITY CHECKLIST (follow it):
        - Work in layers: 1) footprint/foundation 2) main shell 3) trim, pillars, beams, windows, arches 4) roofs 5) interior + props 6) lighting 7) greenery 8) weathering.
        - Use 4-6 different materials per structure and weighted patterns on large surfaces; never leave big flat single-material walls or floors.
        - Vary heights, add overhangs, balconies, buttresses, chimneys, stairs, slabs, fences, walls, trapdoors, banners, barrels, flower pots, lanterns.
        - Prefer the macros (house, tower, gable_roof, hip_roof, pillar, colonnade, arch, ring_seats, tree, island, mound, text, scatter, copy) for their built-in detail,
          and combine them with fill/hollow/set for custom parts. Use copy to repeat or mirror a finished piece for symmetry.
        - Light every area (LANTERN on posts/walls, SEA_LANTERN/GLOWSTONE hidden in floors, CAMPFIRE) so there are no dark spots.
        - Add entrances (door/arch), connect paths, add signs with "text" where the brief asks for them.
        - Finish with 2-6 polish ops: "replace" for weathering inside your zone (mossy/cracked variants) and "scatter" for grass, flowers, ferns, vines, moss.
        - STAY INSIDE YOUR ZONE BOX (roofs may overhang by 2). Never "clear" outside your zone. Do not rebuild the ground unless your role is terrain.
        - Coordinates are absolute (relative to build centre), NOT relative to your zone corner.
        - Be precise: double-check that walls, roofs, doors and floors line up at the right y levels.
        """;

    private static final String[] FAMILIES = {
        "warm medieval: spruce/dark oak timber, brick-red roofs, stone bricks, cobblestone, lanterns",
        "nordic frost: deepslate, spruce, snow, blue ice accents, soul lanterns, stone brick",
        "elven white-gold: quartz, calcite, smooth sandstone, waxed copper/gold roofs, cherry and azalea trees",
        "dark gothic: blackstone, deepslate bricks, crimson and warped accents, soul lanterns, chains, iron bars",
        "desert sultanate: sandstone, cut sandstone, orange/blue terracotta, palm-like acacia and jungle trees",
        "sky-island fantasy: mossy stone, amethyst, end rods, dark oak and cherry bonsai, hanging crystals",
        "royal castle: light stone bricks, chiseled blocks, slate roofs, red banners, gold accents",
        "japanese shrine town: dark oak, stripped logs, white concrete, cherry trees, red accents, paper lanterns"
    };

    private static final String[] FEATURES = {
        "a grand central fountain", "a tall clock/bell tower as landmark", "a glowing portal gate", "a waterfall flowing from a cliff into a pond",
        "a bridge over a small river", "a giant statue (sword, dragon head or knight)", "a market street with stalls", "a sunken garden with flower beds",
        "a lighthouse-like beacon tower", "an ancient ruin corner overgrown with moss and vines", "a hanging-lantern avenue of trees", "a symmetrical crossroads with four gates"
    };

    // ======================= ANA AKIŞ =======================

    private record Zone(String id, String name, String role, int x1, int z1, int x2, int z2, int maxH, String brief) {
        boolean terrain() { String r = role.toLowerCase(Locale.ROOT); return r.contains("terrain") || r.contains("base"); }
    }

    public Plan plan(String prompt, Consumer<String> say) throws Exception {
        var cfg = pl.getConfig();
        int R = cfg.getInt("max-radius", 80), minY = cfg.getInt("min-y", -40), maxY = cfg.getInt("max-y", 90);
        int maxZones = clamp(cfg.getInt("max-zones", 8), 2, 14);
        int par = clamp(cfg.getInt("zones-parallel", 3), 1, 6);
        int opsPerZone = clamp(cfg.getInt("max-ops-per-zone", 450), 50, 1500);
        int maxOps = clamp(cfg.getInt("max-ops", 4000), 100, 20000);
        int maxTok = clamp(cfg.getInt("max-tokens", 16000), 2000, 64000);

        // ---- 1) mimar ----
        say.accept("Mimar plan çiziyor...");
        String sysBp = fill(BLUEPRINT_SYSTEM, R, minY, maxY, maxZones);
        String userBp = "PLAYER REQUEST:\n" + prompt + "\n\n" + variation();
        String bpText = call(sysBp, userBp, 8000);
        Map<String, Object> bp = Json.firstObject(bpText);
        String title = str(bp.get("title"), "Yapı");
        String message = str(bp.get("message"), "İnşaata başlıyorum!");
        String theme = str(bp.get("theme"), "");
        List<Zone> zones = parseZones(bp.get("zones"), R, maxZones);
        if (zones.isEmpty()) throw new RuntimeException("Plan bölge içermiyor");
        zones.sort(Comparator.comparing((Zone z) -> !z.terrain()));
        synchronized (recent) {
            recent.addLast(title + " — " + (theme.length() > 140 ? theme.substring(0, 140) : theme));
            while (recent.size() > 5) recent.removeFirst();
        }
        say.accept("Plan hazır: \"" + title + "\" — " + zones.size() + " bölge. Ustalar çalışıyor...");

        // ---- 2) ustalar (paralel) ----
        StringBuilder zoneList = new StringBuilder();
        for (Zone z : zones)
            zoneList.append("- ").append(z.id).append(" \"").append(z.name).append("\" (").append(z.role).append(") x[").append(z.x1).append("..").append(z.x2)
                    .append("] z[").append(z.z1).append("..").append(z.z2).append("]\n");
        String sysZone = fill(ZONE_SYSTEM + "\n" + OPS + "\n" + STYLE, R, minY, maxY, maxZones);
        String bpClean = Json.firstObjectText(bpText);
        ExecutorService ex = Executors.newFixedThreadPool(par);
        List<Future<List<Op>>> futs = new ArrayList<>();
        for (Zone z : zones) {
            String userZone = "PLAYER REQUEST:\n" + prompt + "\n\nBLUEPRINT (shared by all builders):\n" + bpClean
                    + "\n\nALL ZONES (other builders build them in parallel, do not touch them):\n" + zoneList
                    + "\nYOUR ZONE: id=" + z.id + " name=\"" + z.name + "\" role=" + z.role + "\nBOX: x[" + z.x1 + ".." + z.x2 + "] z[" + z.z1 + ".." + z.z2
                    + "] max_height=" + z.maxH + "\nBRIEF: " + z.brief + "\n\nTarget: " + target(z) + " ops (hard limit " + opsPerZone
                    + "). Return {\"ops\":[...]} only.";
            futs.add(ex.submit(() -> zoneOps(sysZone, userZone, z, opsPerZone, maxTok)));
        }
        List<Op> all = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, top = 12;
        for (Zone z : zones) {
            minX = Math.min(minX, z.x1); maxX = Math.max(maxX, z.x2); minZ = Math.min(minZ, z.z1); maxZ = Math.max(maxZ, z.z2); top = Math.max(top, z.maxH);
        }
        if (cfg.getBoolean("auto-clear", true)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("t", "clear");
            c.put("x1", (double) Math.max(-R, minX - 2)); c.put("x2", (double) Math.min(R, maxX + 2));
            c.put("z1", (double) Math.max(-R, minZ - 2)); c.put("z2", (double) Math.min(R, maxZ + 2));
            c.put("y1", 1.0); c.put("y2", (double) Math.min(maxY, top + 8));
            all.add(Op.from(c));
        }
        int ok = 0, fail = 0;
        String lastErr = "";
        try {
            for (int i = 0; i < zones.size(); i++) {
                Zone z = zones.get(i);
                try {
                    List<Op> zo = futs.get(i).get();
                    all.addAll(zo);
                    ok++;
                    say.accept("✔ Bölge " + (i + 1) + "/" + zones.size() + " \"" + z.name + "\" hazır (" + zo.size() + " komut)");
                } catch (ExecutionException e) {
                    fail++;
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    lastErr = String.valueOf(c.getMessage());
                    say.accept("✖ Bölge \"" + z.name + "\" üretilemedi: " + lastErr);
                }
            }
        } finally {
            ex.shutdownNow();
        }
        if (ok == 0) throw new RuntimeException("Hiçbir bölge üretilemedi: " + lastErr);
        if (all.size() > maxOps) all = new ArrayList<>(all.subList(0, maxOps));
        return new Plan(message + (fail > 0 ? " (" + fail + " bölge eksik)" : ""), all);
    }

    private List<Op> zoneOps(String system, String user, Zone z, int limit, int maxTok) throws Exception {
        String text = call(system, user, maxTok);
        List<Map<String, Object>> raw = Json.salvageOps(text);
        if (raw.size() < 5) {
            text = call(system, user + "\n\nYour previous answer contained too few valid ops. Return the complete {\"ops\":[...]} JSON now.", maxTok);
            raw = Json.salvageOps(text);
        }
        if (raw.isEmpty()) throw new RuntimeException("geçerli op yok");
        List<Op> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            if (out.size() >= limit) break;
            Op op = Op.from(m);
            if (op.t().isEmpty()) continue;
            if (op.t().equals("clear") || op.t().equals("air")) {          // güvenlik: kendi bölgesinin dışını silme
                int x1 = Math.max(Math.min(op.i("x1", 0), op.i("x2", 0)), z.x1 - 3), x2 = Math.min(Math.max(op.i("x1", 0), op.i("x2", 0)), z.x2 + 3);
                int z1 = Math.max(Math.min(op.i("z1", 0), op.i("z2", 0)), z.z1 - 3), z2 = Math.min(Math.max(op.i("z1", 0), op.i("z2", 0)), z.z2 + 3);
                if (x1 > x2 || z1 > z2) continue;
                Map<String, Object> c = new LinkedHashMap<>(op.a());
                c.put("x1", (double) x1); c.put("x2", (double) x2); c.put("z1", (double) z1); c.put("z2", (double) z2);
                op = new Op("clear", c);
            }
            out.add(op);
        }
        return out;
    }

    private static String target(Zone z) {
        if (z.terrain()) return "40-90";
        String r = z.role.toLowerCase(Locale.ROOT);
        if (r.contains("decor") || r.contains("path")) return "80-160";
        return "150-350";
    }

    private List<Zone> parseZones(Object o, int R, int maxZones) {
        List<Zone> out = new ArrayList<>();
        if (!(o instanceof List<?> l)) return out;
        for (Object e : l) {
            if (out.size() >= maxZones) break;
            if (!(e instanceof Map<?, ?> m)) continue;
            int a = (int) num(m.get("x1"), -R), b = (int) num(m.get("x2"), R), c = (int) num(m.get("z1"), -R), d = (int) num(m.get("z2"), R);
            int x1 = clamp(Math.min(a, b), -R, R), x2 = clamp(Math.max(a, b), -R, R), z1 = clamp(Math.min(c, d), -R, R), z2 = clamp(Math.max(c, d), -R, R);
            out.add(new Zone(str(m.get("id"), "Z" + (out.size() + 1)), str(m.get("name"), "Bölge"), str(m.get("role"), "building"), x1, z1, x2, z2,
                    clamp((int) num(m.get("max_height"), 30), 6, 85), str(m.get("brief"), "")));
        }
        return out;
    }

    private String variation() {
        Random r = new Random();
        List<String> rec;
        synchronized (recent) { rec = new ArrayList<>(recent); }
        StringBuilder sb = new StringBuilder("VARIATION DIRECTIVE (random seed " + r.nextInt(1_000_000) + "):\n");
        sb.append("- If the player did not specify a style or materials, use this palette family: ").append(FAMILIES[r.nextInt(FAMILIES.length)]).append(".\n");
        sb.append("- Work in these signature ideas where they fit the request: ").append(FEATURES[r.nextInt(FEATURES.length)]).append(" and ")
                .append(FEATURES[r.nextInt(FEATURES.length)]).append(".\n");
        sb.append("- Layout hint: ").append(r.nextBoolean() ? "asymmetric, organic and winding" : "strongly symmetric and formal").append("; main landmark ")
                .append(r.nextBoolean() ? "in the centre" : "off-centre with a view axis towards it").append(".\n");
        if (!rec.isEmpty()) {
            sb.append("- RECENT BUILDS (do NOT repeat their layout, palette or landmarks; make this one clearly different):\n");
            for (String s : rec) sb.append("  * ").append(s).append("\n");
        }
        return sb.toString();
    }

    // ======================= HTTP (stream) =======================

    private String call(String system, String user, int maxTokens) throws Exception {
        return useGemini() ? callGemini(system, user, maxTokens) : callClaude(system, user, maxTokens);
    }

    private String callClaude(String system, String user, int maxTokens) throws Exception {
        String key = anthropicKey();
        String model = pl.getConfig().getString("model", "claude-sonnet-5-5").trim();
        String body = "{\"model\":" + Json.quote(model) + ",\"max_tokens\":" + maxTokens + ",\"stream\":true,\"system\":" + Json.quote(system)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + Json.quote(user) + "}]}";
        String last = "";
        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofMinutes(10))
                    .header("content-type", "application/json")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            try {
                HttpResponse<java.util.stream.Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
                int sc = res.statusCode();
                if (sc == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (var lines = res.body()) { lines.forEach(line -> handleSse(line, sb)); }
                    if (sb.length() == 0) throw new RuntimeException("boş cevap");
                    return sb.toString();
                }
                String err;
                try (var lines = res.body()) { err = lines.collect(Collectors.joining("\n")); }
                last = "HTTP " + sc + ": " + (err.length() > 300 ? err.substring(0, 300) : err);
                if (sc != 429 && sc < 500) throw new NonRetry(last);
            } catch (NonRetry e) {
                throw new RuntimeException(e.getMessage());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt < 4) Thread.sleep(2000L * (1L << (attempt - 1)));
        }
        throw new RuntimeException(last);
    }

    // ---------- Gemini (ücretsiz katman) ----------
    private final Object geminiLock = new Object();
    private long lastGemini = 0;

    private void throttle() throws InterruptedException {
        long gap = pl.getConfig().getInt("gemini-min-gap-ms", 7000);
        synchronized (geminiLock) {
            long wait = lastGemini + gap - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastGemini = System.currentTimeMillis();
        }
    }

    private String callGemini(String system, String user, int maxTokens) throws Exception {
        String key = geminiKey();
        int mx = Math.min(60000, maxTokens * 2);
        String body = "{\"systemInstruction\":{\"parts\":[{\"text\":" + Json.quote(system) + "}]},"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":" + Json.quote(user) + "}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":" + mx + ",\"responseMimeType\":\"application/json\"}}";
        String last = "";
        for (String model : geminiModels()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                throttle();
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model.trim() + ":streamGenerateContent?alt=sse"))
                        .timeout(Duration.ofMinutes(10))
                        .header("content-type", "application/json")
                        .header("x-goog-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                try {
                    HttpResponse<java.util.stream.Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
                    int sc = res.statusCode();
                    if (sc == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (var lines = res.body()) { lines.forEach(line -> handleGeminiSse(line, sb)); }
                        if (sb.length() == 0) throw new RuntimeException("boş cevap");
                        return sb.toString();
                    }
                    String err;
                    try (var lines = res.body()) { err = lines.collect(Collectors.joining("\n")); }
                    last = "HTTP " + sc + " (" + model + "): " + (err.length() > 300 ? err.substring(0, 300) : err);
                    if (sc == 404 || (sc == 400 && err.toLowerCase(Locale.ROOT).contains("model"))) break;     // sıradaki model
                    if (sc == 401 || sc == 403) throw new NonRetry(last + " (anahtar geçersiz ya da izinsiz olabilir)");
                    if (sc == 429) { if (attempt == 3) break; Thread.sleep(20000L * attempt); continue; }
                    if (sc >= 500) { Thread.sleep(3000L * attempt); continue; }
                    throw new NonRetry(last);
                } catch (NonRetry e) {
                    throw new RuntimeException(e.getMessage());
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    last = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Thread.sleep(3000L * attempt);
                }
            }
        }
        throw new RuntimeException(last);
    }

    static void handleGeminiSse(String line, StringBuilder sb) {
        if (!line.startsWith("data:")) return;
        String p = line.substring(5).trim();
        if (p.isEmpty() || p.equals("[DONE]")) return;
        Object o;
        try { o = Json.parse(p); } catch (RuntimeException e) { return; }
        if (!(o instanceof Map<?, ?> m)) return;
        if (m.get("error") != null) throw new RuntimeException("Gemini akış hatası: " + m.get("error"));
        if (!(m.get("candidates") instanceof List<?> cs) || cs.isEmpty() || !(cs.get(0) instanceof Map<?, ?> c)) return;
        if (!(c.get("content") instanceof Map<?, ?> content) || !(content.get("parts") instanceof List<?> parts)) return;
        for (Object part : parts)
            if (part instanceof Map<?, ?> pm && !Boolean.TRUE.equals(pm.get("thought")) && pm.get("text") instanceof String t) sb.append(t);
    }

    private static final class NonRetry extends RuntimeException { NonRetry(String m) { super(m); } }

    private static void handleSse(String line, StringBuilder sb) {
        if (!line.startsWith("data:")) return;
        String p = line.substring(5).trim();
        if (p.isEmpty() || p.equals("[DONE]")) return;
        Object o;
        try { o = Json.parse(p); } catch (RuntimeException e) { return; }
        if (!(o instanceof Map<?, ?> m)) return;
        Object type = m.get("type");
        if ("content_block_delta".equals(type)) {
            if (m.get("delta") instanceof Map<?, ?> d && "text_delta".equals(d.get("type")) && d.get("text") instanceof String t) sb.append(t);
        } else if ("error".equals(type)) {
            throw new RuntimeException("API akış hatası: " + m.get("error"));
        }
    }

    // ======================= yardımcılar =======================

    private static String fill(String s, int R, int minY, int maxY, int zones) {
        return s.replace("%R%", String.valueOf(R)).replace("%MINY%", String.valueOf(minY)).replace("%MAXY%", String.valueOf(maxY)).replace("%ZONES%", String.valueOf(zones));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    private static String str(Object o, String def) { return o instanceof String s && !s.isBlank() ? s : def; }
}
