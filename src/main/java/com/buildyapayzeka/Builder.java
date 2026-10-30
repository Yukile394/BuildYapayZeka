package com.buildyapayzeka;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Builder {
    record U(int x, int y, int z, BlockData d) {}

    private static final Set<Material> BANNED = EnumSet.of(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.BARRIER,
            Material.TNT, Material.BEDROCK, Material.LAVA, Material.END_PORTAL, Material.END_GATEWAY,
            Material.NETHER_PORTAL, Material.SPAWNER);

    private final BuildYapayZeka pl;
    private BukkitTask task;
    private List<U> current;
    private World curWorld;
    private final Map<UUID, List<U>> history = new HashMap<>();
    private final Map<UUID, World> historyWorld = new HashMap<>();
    private UUID curOwner;

    public Builder(BuildYapayZeka pl) { this.pl = pl; }

    public boolean busy() { return task != null; }

    /** Expander ayarı: fence/duvar/cam bağlantıları için gerçek blok bilgisini kullanır. */
    public static Expander.Config expandConfig(BuildYapayZeka pl) {
        Expander.Config c = new Expander.Config();
        c.radius = pl.getConfig().getInt("max-radius", 80);
        c.minY = pl.getConfig().getInt("min-y", -40);
        c.maxY = pl.getConfig().getInt("max-y", 90);
        c.maxBlocks = pl.getConfig().getInt("max-blocks", 250000);
        c.seed = (int) (System.nanoTime() & 0x7fffffff);
        c.solid = base -> {
            Material m = Material.matchMaterial(base);
            return m != null && m.isBlock() && m.isOccluding();
        };
        return c;
    }

    /** Ana iş parçacığında çağrılır. Blok listesi (Expander sonucu) zaten hazırdır. */
    public void start(Player p, Location o, String message, Expander.Result res) {
        if (busy()) { pl.bot.say("Şu an başka bir inşaat yapıyorum, /bot iptal ile durdurabilirsin."); return; }
        if (res.solidCount == 0) { pl.bot.say("Geçerli bir plan oluşturamadım, isteği farklı anlatır mısın?"); return; }

        // palet -> BlockData (bir kez, ana thread'de)
        final BlockData[] pd = new BlockData[res.palette.size()];
        Set<String> bad = new LinkedHashSet<>();
        pd[1] = Material.AIR.createBlockData();
        for (int i = 2; i < pd.length; i++) {
            String state = res.palette.get(i);
            int b = state.indexOf('[');
            String base = b < 0 ? state : state.substring(0, b);
            Material m = Material.matchMaterial(base);
            if (m == null || !m.isBlock() || BANNED.contains(m)) { bad.add(base); continue; }
            try {
                pd[i] = Bukkit.createBlockData("minecraft:" + state);
            } catch (Exception e) {
                try { pd[i] = m.createBlockData(); bad.add(state + " (özellikler yok sayıldı)"); }
                catch (Exception e2) { bad.add(base); }
            }
        }
        if (!bad.isEmpty()) {
            String s = String.join(", ", bad);
            pl.bot.say("Geçersiz/yasaklı blok atlandı: " + (s.length() > 200 ? s.substring(0, 200) + "..." : s));
            pl.getLogger().warning("Atlanan bloklar: " + s);
        }
        for (String w : res.warnings) pl.getLogger().warning("Expander: " + w);

        pl.bot.say(message + " (" + res.solidCount + " blok)");

        World w = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        int per = pl.getConfig().getInt("blocks-per-tick", 400);
        int scanMax = Math.max(per * 40, 20000);
        current = new ArrayList<>();
        curWorld = w;
        curOwner = p.getUniqueId();
        pl.bot.building = true;
        final int total = res.n;
        task = new BukkitRunnable() {
            int idx = 0, lastPct = 0;
            public void run() {
                Location last = null;
                int placed = 0, scanned = 0;
                while (idx < total && placed < per && scanned < scanMax) {
                    int i = idx++;
                    scanned++;
                    BlockData d = pd[res.pi[i]];
                    if (d == null) continue;
                    int y = oy + res.ys[i];
                    if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(ox + res.xs[i], y, oz + res.zs[i]);
                    BlockData old = b.getBlockData();
                    if (old.equals(d)) continue;
                    current.add(new U(b.getX(), b.getY(), b.getZ(), old));
                    b.setBlockData(d, false);
                    placed++;
                    if (!d.getMaterial().isAir()) last = b.getLocation();
                }
                if (last != null) pl.bot.moveTo(last.add(0.5, 3, 0.5));
                int pct = (int) ((long) idx * 100 / Math.max(1, total));
                if (pct >= lastPct + 25 && pct < 100) { lastPct = pct; pl.bot.say("İnşaat %" + pct + "..."); }
                if (idx >= total) { finish(true); cancel(); }
            }
        }.runTaskTimer(pl, 0, 1);
    }

    private void finish(boolean ok) {
        task = null;
        pl.bot.building = false;
        if (curOwner != null && current != null) { history.put(curOwner, current); historyWorld.put(curOwner, curWorld); }
        pl.bot.say(ok ? "İnşaat tamamlandı! Geri almak için /bot geri" : "İnşaat iptal edildi.");
    }

    public void cancel() {
        if (task == null) return;
        task.cancel();
        finish(false);
    }

    public void undo(Player p) {
        List<U> list = history.remove(p.getUniqueId());
        World w = historyWorld.remove(p.getUniqueId());
        if (list == null || busy()) { pl.bot.say("Geri alınacak inşaat yok."); return; }
        pl.bot.building = true;
        int per = pl.getConfig().getInt("blocks-per-tick", 400);
        ListIterator<U> it = list.listIterator(list.size());
        task = new BukkitRunnable() {
            public void run() {
                for (int n = 0; n < per && it.hasPrevious(); n++) {
                    U u = it.previous();
                    w.getBlockAt(u.x(), u.y(), u.z()).setBlockData(u.d(), false);
                }
                if (!it.hasPrevious()) { task = null; pl.bot.building = false; pl.bot.say("Son inşaat geri alındı."); cancel(); }
            }
        }.runTaskTimer(pl, 0, 1);
    }
}
