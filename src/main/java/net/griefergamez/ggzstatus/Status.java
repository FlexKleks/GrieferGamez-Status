package net.griefergamez.ggzstatus;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Status extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Connection connection;
    private LuckPerms luckPerms;
    private MiniMessage miniMessage;
    private static final long COOLDOWN = 30 * 60 * 1000; // 30 Minuten
    private final Map<UUID, Long> lastBroadcast = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> pendingStatusSet = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        luckPerms = LuckPermsProvider.get();
        miniMessage = MiniMessage.miniMessage();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("status")).setExecutor(this);
        Objects.requireNonNull(getCommand("status")).setTabCompleter(this);

        try {
            File db = new File(getDataFolder(), "status.db");
            db.getParentFile().mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getPath());

            connection.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS statuses (" +
                            "uuid TEXT PRIMARY KEY, " +
                            "message TEXT, " +
                            "enabled INTEGER, " +
                            "blocked_until INTEGER DEFAULT 0, " +
                            "last_sent INTEGER DEFAULT 0)"
            );

            try {
                connection.createStatement().execute("ALTER TABLE statuses ADD COLUMN last_sent INTEGER DEFAULT 0");
            } catch (SQLException ignored) {
            }

        } catch (Exception e) {
            getLogger().severe("DB error: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().severe("DB close error: " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastBroadcast.containsKey(id) && now - lastBroadcast.get(id) < COOLDOWN) return;

        // Status laden
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT message, enabled, blocked_until FROM statuses WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                ResultSet rs = ps.executeQuery();

                if (!rs.next()) return;

                long blockedUntil = rs.getLong("blocked_until");
                if (blockedUntil > System.currentTimeMillis()) return;
                if (rs.getInt("enabled") != 1) return;

                String raw = rs.getString("message");
                String conv = translateToMiniMessage(raw);
                Component msg = miniMessage.deserialize(conv);

                // LuckPerms: Permission-Check
                luckPerms.getUserManager().loadUser(id).thenAcceptAsync(user -> {
                    boolean allowed = user.getCachedData().getPermissionData()
                            .checkPermission("griefergamez.status.use").asBoolean();
                    if (!allowed) return;

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Component suffixComponent = LegacyComponentSerializer.legacySection()
                                    .deserialize("§-§7§2§5§2§e§9§d§6⚐");
                            Component displayName = LegacyComponentSerializer.legacySection()
                                    .deserialize(p.getDisplayName());
                            Component full = Component.empty()
                                    .append(suffixComponent).append(Component.space())
                                    .append(displayName).append(Component.text(" "))
                                    .append(msg);

                            for (Player x : Bukkit.getOnlinePlayers()) {
                                x.sendMessage(full);
                                x.playSound(x.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 5F, 1F);
                            }

                            lastBroadcast.put(id, System.currentTimeMillis());
                            luckPerms.getUserManager().saveUser(user);
                        }
                    }.runTaskLater(this, 60L);
                });
            } catch (SQLException ex) {
                getLogger().severe("Load status error: " + ex.getMessage());
            }
        });
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (!pendingStatusSet.containsKey(id)) return;

        e.setCancelled(true);
        String msg = e.getMessage();

        if (msg.equalsIgnoreCase("-cancel")) {
            pendingStatusSet.remove(id).cancel();
            p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>Deine Eingabe wurde abgebrochen.")));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO statuses(uuid,message,enabled,blocked_until) VALUES(?,?,1,0) " +
                            "ON CONFLICT(uuid) DO UPDATE SET message=excluded.message,enabled=1;")) {
                ps.setString(1, id.toString());
                ps.setString(2, msg);
                ps.executeUpdate();
            } catch (SQLException ex) {
                p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>DB-Fehler: " + ex.getMessage())));
            }
        });

        pendingStatusSet.remove(id).cancel();
        p.sendMessage(getPrefix().append(miniMessage.deserialize("<green>Dein Status wurde erfolgreich gesetzt.")));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }

        Player p = (Player) s;
        UUID id = p.getUniqueId();

        if (!p.hasPermission("griefergamez.status.use")) {
            p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Dazu hast du keine Rechte.")));
            return true;
        }
        if (args.length == 0) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT message, enabled, blocked_until FROM statuses WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Du hast aktuell keinen Status gesetzt. Nutze <yellow>/status set <red>um deinen Status zu setzen.")));
                        return;
                    }

                    String msgRaw = rs.getString("message");
                    boolean enabled = rs.getInt("enabled") == 1;
                    long blockedUntil = rs.getLong("blocked_until");
                    long remainingCooldown = COOLDOWN - (System.currentTimeMillis() - lastBroadcast.getOrDefault(id, 0L));
                    long remainingMin = Math.max(0, remainingCooldown / 1000 / 60);

                    String statusLine = enabled ? "<green>aktiviert" : "<red>deaktiviert";
                    String cooldownLine = remainingCooldown > 0
                            ? "<gray>Dein Status wird wieder in <yellow>" + remainingMin + " Minuten <gray> gesendet."
                            : "<gray>Dein Status wird beim nächsten Join gesendet.";

                    if (blockedUntil > System.currentTimeMillis()) {
                        long remaining = (blockedUntil - System.currentTimeMillis()) / 1000 / 60;
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Dein Status ist blockiert für noch <yellow>" + remaining + " Minuten.")));
                        return;
                    }

                    User user = luckPerms.getUserManager().getUser(id);
                    if (user == null) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Fehler beim Laden deines Status.")));
                        return;
                    }

                    String conv = translateToMiniMessage(msgRaw);
                    Component msg = miniMessage.deserialize(conv);
                    String suffixRaw = user.getCachedData().getMetaData().getSuffix();
                    if (suffixRaw == null) suffixRaw = "";

                    String displayName = user.getCachedData().getMetaData().getPrefix();
                    if (displayName == null) displayName = "";
                    displayName += p.getName();

                    String rawSuffix = luckPerms.getUserManager().getUser(id).getCachedData().getMetaData().getSuffix();
                    if (rawSuffix == null) rawSuffix = "§-§7§2§5§2§e§9§d§6⚐";
                    rawSuffix = PlaceholderAPI.setPlaceholders(p, rawSuffix); // <-- PlaceholderAPI verarbeiten
                    Component suffix = LegacyComponentSerializer.legacySection().deserialize("§-§7§2§5§2§e§9§d§6⚐");
                    String rawDisplayName = PlaceholderAPI.setPlaceholders(p, p.getDisplayName());
                    Component name = LegacyComponentSerializer.legacySection().deserialize(rawDisplayName);

                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>Dein Status ist aktuell " + statusLine + "<gray>. Dieser hat eine Abklingzeit von <yellow>" + (COOLDOWN / 1000 / 60) + " Minuten.")));
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>Dein Status sieht aktuell so aus:")));
                    p.sendMessage(Component.empty().append(suffix).append(Component.space()).append(name).append(Component.text(" ")).append(msg));
                    p.sendMessage(getPrefix().append(miniMessage.deserialize(cooldownLine)));
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>Nutze <yellow>/status set<gray>, um deinen Status zu setzen. Mit <yellow>/status toggle<gray> kannst du ihn aktivieren/deaktivieren.")));
                } catch (Exception ex) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Fehler beim Laden deines Status.")));
                }
            });
            return true;
        }
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "set":
                if (pendingStatusSet.containsKey(id)) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Du setzt bereits einen Status. Nutze <yellow>-cancel <red>um abzubrechen.")));
                    return true;
                }

                p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>Bitte gib deinen Status im Chat ein. Mit <yellow>-cancel <gray>kannst du abbrechen.")));

                BukkitRunnable timeout = new BukkitRunnable() {
                    public void run() {
                        if (pendingStatusSet.containsKey(id)) {
                            pendingStatusSet.remove(id);
                            p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Eingabe wurde abgebrochen. (Timeout)")));
                        }
                    }
                };
                pendingStatusSet.put(id, timeout);
                timeout.runTaskLater(this, 20 * 20); // 20 Sekunden Timeout
                return true;

            case "toggle":
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT enabled FROM statuses WHERE uuid = ?")) {
                        ps.setString(1, id.toString());
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Du hast aktuell keinen Status gesetzt. Nutze <yellow>/status set <red>um deinen Status zu setzen.")));
                            return;
                        }

                        boolean enabled = rs.getInt("enabled") == 1;
                        int newState = enabled ? 0 : 1;

                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE statuses SET enabled = ? WHERE uuid = ?")) {
                            update.setInt(1, newState);
                            update.setString(2, id.toString());
                            update.executeUpdate();
                        }

                        p.sendMessage(getPrefix().append(miniMessage.deserialize(newState == 1 ? "Status <green>aktiviert." : "Status <red>deaktiviert.")));
                    } catch (SQLException e) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Fehler beim Umschalten.")));
                    }
                });
                return true;

            case "delete":
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM statuses WHERE uuid = ?")) {
                        ps.setString(1, id.toString());
                        int updated = ps.executeUpdate();
                        p.sendMessage(getPrefix().append(miniMessage.deserialize(updated > 0 ? "<gray>Status <green>gelöscht." : "<red>Kein Status gefunden.")));
                    } catch (SQLException e) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Fehler beim Löschen.")));
                    }
                });
                return true;

            case "block":
                if (!p.hasPermission("griefergamez.status.admin")) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }

                if (args.length < 3) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>/status block <Spieler> <Zeit z.B. 10s, 5m, 2h>")));
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                long seconds;

                try {
                    seconds = parseTimeToSeconds(args[2]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Ungültiges Zeitformat.")));
                    return true;
                }

                long until = System.currentTimeMillis() + (seconds * 1000);

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO statuses(uuid,message,enabled,blocked_until) VALUES(?,?,0,?) " +
                                    "ON CONFLICT(uuid) DO UPDATE SET blocked_until = ?")) {
                        ps.setString(1, target.getUniqueId().toString());
                        ps.setString(2, "");
                        ps.setLong(3, until);
                        ps.setLong(4, until);
                        ps.executeUpdate();

                        if (target.isOnline()) {
                            Player onlineTarget = target.getPlayer();
                            if (onlineTarget != null) {
                                onlineTarget.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Du wurdest blockiert für <yellow>" + args[2])));
                            }
                        }

                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<green>Spieler blockiert für <yellow>" + args[2])));
                    } catch (SQLException e) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Datenbankfehler: " + e.getMessage())));
                    }
                });
                return true;

            case "unblock":
                if (!p.hasPermission("griefergamez.status.admin")) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }

                if (args.length < 2) {
                    p.sendMessage(getPrefix().append(miniMessage.deserialize("<gray>/status unblock <Spieler>")));
                    return true;
                }

                OfflinePlayer unblock = Bukkit.getOfflinePlayer(args[1]);

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE statuses SET blocked_until = 0 WHERE uuid = ?")) {
                        ps.setString(1, unblock.getUniqueId().toString());
                        int rows = ps.executeUpdate();

                        if (rows > 0) {
                            p.sendMessage(getPrefix().append(miniMessage.deserialize("<green>Blockierung aufgehoben.")));

                            if (unblock.isOnline()) {
                                unblock.getPlayer().sendMessage(getPrefix().append(miniMessage.deserialize("<green>Du wurdest entblockt.")));
                            }
                        } else {
                            p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Kein Eintrag gefunden.")));
                        }
                    } catch (SQLException e) {
                        p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Fehler beim Entblocken.")));
                    }
                });
                return true;

            default:
                p.sendMessage(getPrefix().append(miniMessage.deserialize("<red>Unbekannter Subbefehl.")));
                return true;
        }
    }

    private long parseTimeToSeconds(String input) throws NumberFormatException {
        input = input.toLowerCase();
        long multiplier;
        if (input.endsWith("d")) multiplier = 86400;
        else if (input.endsWith("h")) multiplier = 3600;
        else if (input.endsWith("m")) multiplier = 60;
        else if (input.endsWith("s")) multiplier = 1;
        else throw new NumberFormatException("Ungültiges Zeitformat");
        return Long.parseLong(input.substring(0, input.length() - 1)) * multiplier;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (args.length == 1)
            return Arrays.asList("set", "toggle", "delete", "block", "unblock")
                    .stream().filter(x -> x.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());

        if (args.length == 2 && args[0].equalsIgnoreCase("block"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());

        if (args.length == 2 && args[0].equalsIgnoreCase("unblock"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());

        return List.of();
    }

    private Component getPrefix() {
        return miniMessage.deserialize("<dark_gray>[<gold>GrieferGamez</gold><dark_gray>] </dark_gray>");
    }

    public static String translateToMiniMessage(String input) {
        List<String> legacy = Arrays.asList(
                "&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7",
                "&8", "&9", "&a", "&b", "&c", "&d", "&e", "&f",
                "&l", "&m", "&n", "&o", "&k", "&r"
        );
        List<String> colors = Arrays.asList(
                "<black>", "<dark_blue>", "<dark_green>", "<dark_aqua>", "<dark_red>", "<dark_purple>",
                "<gold>", "<gray>", "<dark_gray>", "<blue>", "<green>", "<aqua>", "<red>", "<light_purple>",
                "<yellow>", "<white>", "<b>", "<st>", "<u>", "<i>", "<obf>", "<reset>"
        );

        input = input.replace('§', '&');
        for (int i = 0; i < legacy.size(); i++) {
            input = input.replace(legacy.get(i), colors.get(i));
        }

        return input;
    }
}