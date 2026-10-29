package com.buildyapayzeka;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Builder {
    record P(int x, int y, int z, Material m) {}
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

    public void start(Player p, Location o, Plan plan) {
        if (busy()) { pl.bot.say("Şu an başka bir inşaat yapıyorum, /bot iptal ile durdurabilirsin."); return; }
        int max = pl.getConfig().getInt("max-blocks", 250000);
        List<P> blocks = expand(plan.ops(), max);
        if (blocks.isEmpty()) { pl.bot.say("Geçerli bir plan oluşturamadım, isteği farklı anlatır mısın?"); return; }
        pl.bot.say(plan.message() + " (" + blocks.size() + " blok)");

        World w = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        int per = pl.getConfig().getInt("blocks-per-tick", 400);
        current = new ArrayList<>(blocks.size());
        curWorld = w;
        curOwner = p.getUniqueId();
        pl.bot.building = true;
        Iterator<P> it = blocks.iterator();
        int total = blocks.size();
        task = new BukkitRunnable() {
            int done = 0, lastPct = 0;
            public void run() {
                Location last = null;
                for (int n = 0; n < per && it.hasNext(); n++) {
                    P q = it.next();
                    done++;
                    int y = oy + q.y();
                    if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(ox + q.x(), y, oz + q.z());
                    current.add(new U(b.getX(), b.getY(), b.getZ(), b.getBlockData()));
                    b.setType(q.m(), false);
                    last = b.getLocation();
                }
                if (last != null) pl.bot.moveTo(last.add(0.5, 3, 0.5));
                int pct = done * 100 / total;
                if (pct >= lastPct + 25 && pct < 100) { lastPct = pct; pl.bot.say("İnşaat %" + pct + "..."); }
                if (!it.hasNext()) { finish(true); cancel(); }
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

    // ---------- Op -> blok listesi ----------
    private List<P> expand(List<Op> ops, int max) {
        List<P> out = new ArrayList<>();
        for (Op op : ops) {
            Material m = op.t().equals("clear") ? Material.AIR : Material.matchMaterial(op.m());
            if (m == null || !m.isBlock() || BANNED.contains(m)) continue;
            int x1 = Math.min(op.x1(), op.x2()), x2 = Math.max(op.x1(), op.x2());
            int y1 = Math.min(op.y1(), op.y2()), y2 = Math.max(op.y1(), op.y2());
            int z1 = Math.min(op.z1(), op.z2()), z2 = Math.max(op.z1(), op.z2());
            switch (op.t()) {
                case "set" -> out.add(new P(op.x1(), op.y1(), op.z1(), m));
                case "fill", "clear" -> {
                    for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                        if (out.size() >= max) return out;
                        out.add(new P(x, y, z, m));
                    }
                }
                case "hollow" -> {
                    for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                        if (x != x1 && x != x2 && y != y1 && y != y2 && z != z1 && z != z2) continue;
                        if (out.size() >= max) return out;
                        out.add(new P(x, y, z, m));
                    }
                }
                case "cyl" -> {
                    int r = Math.max(1, op.r()), h = Math.max(1, op.y2());
                    int outer = r * r + r, inner = (r - 1) * (r - 1) + (r - 1);
                    for (int y = op.y1(); y < op.y1() + h; y++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                        int d = dx * dx + dz * dz;
                        if (d > outer || (op.hollow() && d <= inner)) continue;
                        if (out.size() >= max) return out;
                        out.add(new P(op.x1() + dx, y, op.z1() + dz, m));
                    }
                }
                default -> {}
            }
        }
        return out;
    }
}
