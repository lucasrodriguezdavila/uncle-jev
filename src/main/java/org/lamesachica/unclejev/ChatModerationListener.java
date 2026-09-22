package org.lamesachica.unclejev;

import com.google.gson.GsonBuilder;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatModerationListener implements Listener {

    private record ChatLine(UUID player, String text) {
    }

    private static final String[] SPINNER = {"|", "/", "-", "\\"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Uncle_jev plugin;
    private final JevClient jev;
    private final Deque<ChatLine> history = new ArrayDeque<>();

    public ChatModerationListener(Uncle_jev plugin, JevClient jev) {
        this.plugin = plugin;
        this.jev = jev;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(message("muted"));
            return;
        }

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        JevClient.Verdict verdict;
        BukkitTask spinner = showAnalyzing(player, text);
        try {
            verdict = jev.classify(text, previousFrom(player.getUniqueId()), recentChat());
        } catch (Exception e) {
            plugin.getLogger().warning("JEV did not respond, message passes unmoderated: " + e);
            record(player, text, "unmoderated");
            return;
        } finally {
            spinner.cancel();
            player.sendActionBar(Component.empty());
        }

        if (!verdict.severeReasons().isEmpty()) {
            event.setCancelled(true);
            record(player, text, "SEVERE " + verdict.severeReasons());
            punish(player, text, verdict);
            return;
        }

        switch (verdict.insult()) {
            case "strong" -> {
                event.setCancelled(true);
                record(player, text, "censored");
                player.sendMessage(message("strong"));
            }
            case "mild" -> {
                record(player, text, "mild");
                ChatRenderer renderer = event.renderer();
                event.renderer((source, displayName, chatMessage, viewer) -> renderer.render(source, displayName,
                        viewer.equals(source) ? chatMessage.color(NamedTextColor.RED) : chatMessage, viewer));
                Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(message("mild")), 1L);
            }
            default -> record(player, text, null);
        }
    }

    private BukkitTask showAnalyzing(Player player, String text) {
        String preview = text.length() > 60 ? text.substring(0, 57) + "..." : text;
        AtomicInteger frame = new AtomicInteger();
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> player.sendActionBar(
                MiniMessage.miniMessage().deserialize(plugin.getConfig().getString("messages.analyzing"),
                        Placeholder.unparsed("spinner", SPINNER[frame.getAndIncrement() % SPINNER.length]),
                        Placeholder.unparsed("message", preview))), 0L, 2L);
    }

    private void punish(Player player, String text, JevClient.Verdict verdict) {
        plugin.mute(player.getUniqueId());
        player.sendMessage(message("severe"));
        String reasons = String.join(", ", verdict.severeReasons());
        plugin.getLogger().warning(player.getName() + " muted for severe message (" + reasons + "): " + text);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("unclejev.staff"))
                .forEach(p -> p.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfig().getString("messages.staff-alert"),
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("reasons", reasons),
                        Placeholder.unparsed("message", text))));
        writeReport(player, text, verdict);
    }

    private void writeReport(Player player, String text, JevClient.Verdict verdict) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder report = new StringBuilder()
                .append("Player: ").append(player.getName()).append(" (").append(player.getUniqueId()).append(")\n")
                .append("Date: ").append(now).append("\n")
                .append("Message: ").append(text).append("\n")
                .append("Reasons: ").append(String.join(", ", verdict.severeReasons())).append("\n\n")
                .append("JEV response:\n")
                .append(new GsonBuilder().setPrettyPrinting().create().toJson(verdict.answers())).append("\n\n")
                .append("Recent chat messages:\n");
        synchronized (history) {
            history.forEach(line -> report.append(line.text()).append("\n"));
        }
        Path file = plugin.getDataFolder().toPath().resolve("reports")
                .resolve(FILE_TIME.format(now) + "_" + player.getName() + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save the report for " + player.getName() + ": " + e);
        }
    }

    private void record(Player player, String text, String status) {
        String line = TIME.format(LocalDateTime.now()) + " " + player.getName() + ": " + text
                + (status == null ? "" : " [" + status + "]");
        int max = plugin.getConfig().getInt("history-size");
        synchronized (history) {
            history.addLast(new ChatLine(player.getUniqueId(), line));
            while (history.size() > max) {
                history.removeFirst();
            }
        }
    }

    private List<String> previousFrom(UUID player) {
        synchronized (history) {
            List<String> lines = history.stream().filter(l -> l.player().equals(player)).map(ChatLine::text).toList();
            return lines.subList(Math.max(0, lines.size() - 5), lines.size());
        }
    }

    private List<String> recentChat() {
        synchronized (history) {
            List<String> lines = history.stream().map(ChatLine::text).toList();
            return lines.subList(Math.max(0, lines.size() - 10), lines.size());
        }
    }

    private Component message(String key) {
        return MiniMessage.miniMessage().deserialize(plugin.getConfig().getString("messages." + key));
    }
}
