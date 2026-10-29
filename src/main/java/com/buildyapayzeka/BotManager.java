package com.buildyapayzeka;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public class BotManager {
    private final BuildYapayZeka pl;
    private Entity bot;
    private java.util.UUID owner;
    private BukkitTask follow;
    boolean building = false;

    public BotManager(BuildYapayZeka pl) { this.pl = pl; }

    public boolean active() { return bot != null && bot.isValid(); }

    @SuppressWarnings("unchecked")
    public void activate(Player p) {
        deactivate();
        owner = p.getUniqueId();
        String name = pl.getConfig().getString("bot-name", "BuildYapayZeka");
        Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2));
        World w = l.getWorld();
        Class<? extends Entity> cls = ArmorStand.class;
        try { cls = EntityType.valueOf("MANNEQUIN").getEntityClass(); } catch (Exception ignored) {}
        bot = w.spawn(l, (Class<Entity>) cls, e -> {
            e.setVisibleByDefault(false); // sadece OP'ler görecek
            e.customName(Component.text(name, NamedTextColor.AQUA));
            e.setCustomNameVisible(true);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setPersistent(false);
            e.setSilent(true);
        });
        if (bot instanceof ArmorStand as) {
            as.setArms(true);
            as.setBasePlate(false);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwningPlayer(Bukkit.getOfflinePlayer(pl.getConfig().getString("bot-skin", "Notch")));
            head.setItemMeta(m);
            as.getEquipment().setHelmet(head);
            as.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            as.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
            as.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            as.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
        }
        refreshVisibility();
        follow = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 5, 5);
    }

    public void deactivate() {
        if (follow != null) follow.cancel();
        follow = null;
        if (bot != null) bot.remove();
        bot = null;
    }

    private void tick() {
        if (!active()) return;
        refreshVisibility();
        if (building) return;
        Player p = Bukkit.getPlayer(owner);
        if (p == null) return;
        Location bl = bot.getLocation();
        if (!bl.getWorld().equals(p.getWorld()) || bl.distanceSquared(p.getLocation()) > 36)
            bot.teleport(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)));
        else {
            Location look = bl.clone();
            look.setDirection(p.getEyeLocation().toVector().subtract(bl.toVector()));
            bot.teleport(look);
        }
    }

    private void refreshVisibility() {
        for (Player pl2 : Bukkit.getOnlinePlayers()) {
            if (pl2.isOp()) pl2.showEntity(pl, bot);
            else pl2.hideEntity(pl, bot);
        }
    }

    public void moveTo(Location l) { if (active()) bot.teleport(l); }

    /** Bot'un sohbet mesajı: sadece OP'lerde görünür. */
    public void say(String msg) {
        Component c = Component.text("<", NamedTextColor.GRAY)
                .append(Component.text(pl.getConfig().getString("bot-name", "BuildYapayZeka"), NamedTextColor.AQUA))
                .append(Component.text("> ", NamedTextColor.GRAY))
                .append(Component.text(msg, NamedTextColor.WHITE));
        for (Player p : Bukkit.getOnlinePlayers()) if (p.isOp()) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
    }
}
