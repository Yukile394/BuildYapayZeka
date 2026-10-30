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
        if (!ai.hasKey()) getLogger().warning("anthropic-api-key boş: hazır şablonlar kullanılacak (her istekte aynı yapı çıkar!).");
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
        if (a.length == 0) { p.sendMessage("§e/bot <ne inşa edeyim?> | geri | iptal | durum"); return true; }
        if (a[0].equalsIgnoreCase("geri")) { builder.undo(p); return true; }
        if (a[0].equalsIgnoreCase("iptal")) { builder.cancel(); return true; }
        if (a[0].equalsIgnoreCase("durum")) {
            bot.say("API anahtarı: " + (ai.hasKey() ? "VAR (yapay zeka kullanılacak)" : "YOK (sabit şablon kullanılır, hep aynı çıkar)")
                    + " | model: " + getConfig().getString("model", "claude-sonnet-5-5")
                    + " | max-zones: " + getConfig().getInt("max-zones", 8)
                    + " | fallback-templates: " + getConfig().getBoolean("fallback-templates", false));
            return true;
        }
        if (builder.busy()) { p.sendMessage("§cBot şu an meşgul. /bot iptal ile durdurabilirsin."); return true; }

        String prompt = String.join(" ", a);
        Location origin = p.getLocation().getBlock().getLocation().subtract(0, 1, 0); // y=0 = bastığın zemin
        bot.say("Anladım, planlıyorum: \"" + prompt + "\"");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Plan plan;
                if (ai.hasKey()) {
                    try {
                        plan = ai.plan(prompt, bot::say);
                    } catch (Exception e) {
                        getLogger().warning("AI hatası: " + e);
                        bot.say("AI hatası: " + e.getMessage());
                        if (!getConfig().getBoolean("fallback-templates", false)) {
                            bot.say("Hiçbir şey inşa edilmedi. (Hazır şablonu istersen config.yml'de fallback-templates: true yap.)");
                            return;
                        }
                        bot.say("Hazır şablon kullanılıyor.");
                        plan = Templates.plan(prompt);
                    }
                } else {
                    bot.say("anthropic-api-key BOŞ! Hazır şablon kullanılıyor (isteğe göre değişmez). config.yml'e anahtarını yaz.");
                    plan = Templates.plan(prompt);
                }
                Expander.Result res = Expander.expand(plan.ops(), Builder.expandConfig(this));
                String msg = plan.message();
                Bukkit.getScheduler().runTask(this, () -> builder.start(p, origin, msg, res));
            } catch (Throwable t) {
                getLogger().severe("Plan hatası: " + t);
                bot.say("Hata: " + t.getMessage());
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.isOp() || a.length != 1) return List.of();
        return c.getName().equals("buildyapayzeka") ? List.of("aktif", "kapat") : List.of("geri", "iptal", "durum");
    }
}
