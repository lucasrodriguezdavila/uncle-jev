package org.lamesachica.unclejev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Uncle_jev extends JavaPlugin {

    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private File mutedFile;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        if (!new File(getDataFolder(), "questions.json").exists()) {
            saveResource("questions.json", false);
        }

        String apiKey = getConfig().getString("api-key", "");
        if (apiKey.isBlank()) {
            apiKey = System.getenv().getOrDefault("TYPESAFE_API_KEY", "");
        }
        if (apiKey.isBlank()) {
            getLogger().severe("Missing JEV API key (api-key in config.yml or TYPESAFE_API_KEY). Chat will not be moderated.");
            return;
        }

        JsonObject questions;
        try {
            questions = JsonParser.parseString(Files.readString(new File(getDataFolder(), "questions.json").toPath())).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Invalid questions.json, chat will not be moderated: " + e);
            return;
        }

        mutedFile = new File(getDataFolder(), "muted.yml");
        YamlConfiguration.loadConfiguration(mutedFile).getStringList("muted").forEach(id -> muted.add(UUID.fromString(id)));

        JevClient jev = new JevClient(
                apiKey,
                getConfig().getString("model"),
                Duration.ofMillis(getConfig().getLong("timeout-ms")),
                getConfig().getDouble("severe-threshold"),
                questions);
        getServer().getPluginManager().registerEvents(new ChatModerationListener(this, jev), this);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jev.classify("hello", List.of(), List.of());
                getLogger().info("Connected to JEV.");
            } catch (Exception e) {
                getLogger().warning("Could not connect to JEV: " + e);
            }
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("unmute")) {
            return false;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || !muted.contains(target.getUniqueId())) {
            sender.sendMessage(args[1] + " is not muted.");
            return true;
        }
        muted.remove(target.getUniqueId());
        saveMuted();
        sender.sendMessage(args[1] + " was unmuted.");
        return true;
    }

    public boolean isMuted(UUID player) {
        return muted.contains(player);
    }

    public void mute(UUID player) {
        muted.add(player);
        saveMuted();
    }

    private synchronized void saveMuted() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("muted", muted.stream().map(UUID::toString).toList());
        try {
            yaml.save(mutedFile);
        } catch (IOException e) {
            getLogger().severe("Could not save muted.yml: " + e);
        }
    }
}
