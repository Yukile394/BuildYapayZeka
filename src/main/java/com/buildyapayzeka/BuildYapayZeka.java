package com.buildyapayzeka;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class BuildYapayZeka extends JavaPlugin {
    BotManager bot;
    Builder builder;
    AIClient ai;

    @Override public void onEnable() {
        saveDefaultConfig();
        bot = new BotManager(this);
        builder = new Builder(this);
        ai = new AIClient(this);
        if (!ai.hasKey()) getLogger().warning("anthropic-api-key boş: hazır şablonlar kullanılacak.");
    }

    @Override public void onDisable() { if (bot != null) bot.deactivate(); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p) || !p.isOp()) { s.sendMessage("§cBu komut sadece OP oyuncular içindir."); return true; }

        if (c.getName().equals("buildyapayzeka")) {
            if (a.length == 0) { p.sendMessage("§e/BuildYapayZeka <aktif|kapat>"); return true; }
            switch (a[0].toLowerCase()) {
                case "aktif" -> {
                    bot.activate(p);
                    bot.say("Merhaba " + p.getName() + "! Hazırım. /bot <isteğin> yaz, inşa edeyim.");
                }
                case "kapat" -> {
                    builder.cancel();
                    bot.deactivate();
                    bot.say("Kapatıldı. (Bot mesajlarını sadece OP'ler görür.)");
                }
                default -> p.sendMessage("§e/BuildYapayZeka <aktif|kapat>");
            }
            return true;
        }

        // /bot
        if (!bot.active()) { p.sendMessage("§cÖnce /BuildYapayZeka aktif yaz."); return true; }
        if (a.length == 0) { p.sendMessage("§e/bot <ne inşa edeyim?> | geri | iptal"); return true; }
        if (a[0].equalsIgnoreCase("geri")) { builder.undo(p); return true; }
        if (a[0].equalsIgnoreCase("iptal")) { builder.cancel(); return true; }
        if (builder.busy()) { p.sendMessage("§cBot şu an meşgul. /bot iptal ile durdurabilirsin."); return true; }

        String prompt = String.join(" ", a);
        Location origin = p.getLocation().getBlock().getLocation().subtract(0, 1, 0); // y=0 = bastığın zemin
        bot.say("Anladım, planlıyorum: \"" + prompt + "\"");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Plan plan;
            try {
                plan = ai.hasKey() ? ai.plan(prompt) : Templates.plan(prompt);
            } catch (Exception e) {
                bot.say("AI hatası (" + e.getMessage() + "), hazır şablon kullanıyorum.");
                plan = Templates.plan(prompt);
            }
            Plan fp = plan;
            Bukkit.getScheduler().runTask(this, () -> builder.start(p, origin, fp));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.isOp() || a.length != 1) return List.of();
        return c.getName().equals("buildyapayzeka") ? List.of("aktif", "kapat") : List.of("geri", "iptal");
    }
}
