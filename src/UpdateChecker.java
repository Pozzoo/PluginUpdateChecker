package com.example.myplugin.util; // ← change to your package

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Drop-in update checker for Paper plugins.
 *
 * Usage - call once inside onEnable():
 *   new UpdateChecker(this, "GitHubOwner", "repo-name");
 *
 * Ops (or players with <pluginname>.updatenotify) are notified on join.
 * Add to plugin.yml:
 *   permissions:
 *     myplugin.updatenotify:
 *       description: Receive update notifications on join.
 *       default: op
 */
public class UpdateChecker implements Listener {

    private static final String API_URL     = "https://api.github.com/repos/%s/%s/releases/latest";
    private static final String RELEASE_URL = "https://github.com/%s/%s/releases/latest";

    private final JavaPlugin plugin;
    private final String owner;
    private final String repo;
    private final String permission;

    private volatile boolean updateAvailable = false;
    private volatile String latestVersion    = null;

    public UpdateChecker(JavaPlugin plugin, String owner, String repo) {
        this.plugin     = plugin;
        this.owner      = owner;
        this.repo       = repo;
        this.permission = plugin.getName().toLowerCase() + ".updatenotify";

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::check);
    }

    // -------------------------------------------------------------------------
    // Check logic
    // -------------------------------------------------------------------------

    private void check() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(API_URL, owner, repo)))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", plugin.getName() + "-UpdateChecker")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                plugin.getLogger().warning(
                        "[UpdateChecker] GitHub API returned HTTP " + response.statusCode());
                return;
            }

            String tag = extractJsonField(response.body(), "tag_name");
            if (tag == null) {
                plugin.getLogger().warning(
                        "[UpdateChecker] Could not parse release tag from GitHub response.");
                return;
            }

            latestVersion = stripLeadingV(tag);
            String current = plugin.getDescription().getVersion();

            if (isNewer(latestVersion, current)) {
                updateAvailable = true;
                plugin.getLogger().warning("┌─────────────────────────────────────────┐");
                plugin.getLogger().warning("│  " + plugin.getName() + " update available!");
                plugin.getLogger().warning("│  Running : v" + current);
                plugin.getLogger().warning("│  Latest  : v" + latestVersion);
                plugin.getLogger().warning("│  " + String.format(RELEASE_URL, owner, repo));
                plugin.getLogger().warning("└─────────────────────────────────────────┘");
            } else {
                plugin.getLogger().info(
                        "[UpdateChecker] " + plugin.getName() + " is up to date (v" + current + ").");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[UpdateChecker] Failed to check for updates.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Op join notification
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable) return;
        Player player = event.getPlayer();
        if (!player.hasPermission(permission) && !player.isOp()) return;

        String url = String.format(RELEASE_URL, owner, repo);
        String current = plugin.getDescription().getVersion();

        // 2-second delay so the message isn't buried in login spam
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(
                Component.text()
                        .append(Component.text("[" + plugin.getName() + "] ", NamedTextColor.GOLD))
                        .append(Component.text("Update available  ", NamedTextColor.YELLOW))
                        .append(Component.text("v" + current, NamedTextColor.RED))
                        .append(Component.text(" → ", NamedTextColor.GRAY))
                        .append(Component.text("v" + latestVersion, NamedTextColor.GREEN))
                        .append(Component.newline())
                        .append(Component.text("    ↳ Download", NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(url)))
                        .build()
        ), 40L);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Whether a newer version was found on GitHub. */
    public boolean isUpdateAvailable() { return updateAvailable; }

    /** The latest version string from GitHub (e.g. "1.2.3"), or null if not yet checked. */
    public String getLatestVersion() { return latestVersion; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code remote} is strictly greater than {@code local}
     * using numeric segment comparison (1.10.0 > 1.9.0).
     */
    private boolean isNewer(String remote, String local) {
        int[] r = parseVersion(remote);
        int[] l = parseVersion(local);
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = (i < r.length) ? r[i] : 0;
            int lv = (i < l.length) ? l[i] : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private int[] parseVersion(String v) {
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { nums[i] = 0; }
        }
        return nums;
    }

    /** Minimal JSON field extractor - avoids adding a JSON library dep. */
    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int start = json.indexOf('"', idx + key.length());
        if (start == -1) return null;
        int end = json.indexOf('"', start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    private String stripLeadingV(String tag) {
        return (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
    }
}