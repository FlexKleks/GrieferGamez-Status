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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Status extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Connection connection;
    private LuckPerms luckPerms;
    private MiniMessage miniMessage;
    private LegacyComponentSerializer legacySerializer;
    private Component prefix;
    private Component cachedSuffix;
    private static final long COOLDOWN = Duration.ofMinutes(60).toMillis();
    private final Map<UUID, Long> lastBroadcast = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> pendingStatusSet = new ConcurrentHashMap<>();

    // SQL
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS statuses (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "message TEXT, " +
                    "enabled INTEGER, " +
                    "blocked_until INTEGER DEFAULT 0, " +
                    "last_sent INTEGER DEFAULT 0, " +
                    "sound INTEGER DEFAULT 1)";
    private static final String LOAD_STATUS_SQL = "SELECT message, enabled, blocked_until, last_sent, sound FROM statuses WHERE uuid = ?";
    private static final String UPSERT_MESSAGE_SQL =
            "INSERT INTO statuses(uuid,message,enabled,blocked_until,last_sent,sound) VALUES(?,?,1,0,0,1) " +
                    "ON CONFLICT(uuid) DO UPDATE SET message=excluded.message, enabled=1";
    private static final String SELECT_ENABLED_SQL = "SELECT enabled FROM statuses WHERE uuid = ?";
    private static final String UPDATE_ENABLED_SQL = "UPDATE statuses SET enabled = ? WHERE uuid = ?";
    private static final String DELETE_SQL = "DELETE FROM statuses WHERE uuid = ?";
    private static final String UPSERT_BLOCK_SQL =
            "INSERT INTO statuses(uuid,message,enabled,blocked_until,last_sent,sound) VALUES(?,?,0,?,0,1) " +
                    "ON CONFLICT(uuid) DO UPDATE SET blocked_until = excluded.blocked_until";
    private static final String UNBLOCK_SQL = "UPDATE statuses SET blocked_until = 0 WHERE uuid = ?";

    @Override
    public void onEnable() {
        luckPerms = LuckPermsProvider.get();
        miniMessage = MiniMessage.miniMessage();
        legacySerializer = LegacyComponentSerializer.legacySection();
        prefix = miniMessage.deserialize("<dark_gray>[<gold>GrieferGame</gold><dark_gray>] </dark_gray>");
        cachedSuffix = legacySerializer.deserialize("§-§7§2§5§2§e§9§d§6⚐");

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("status")).setExecutor(this);
        Objects.requireNonNull(getCommand("status")).setTabCompleter(this);

        try {
            File db = new File(getDataFolder(), "status.db");
            db.getParentFile().mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getPath());

            try (Statement s = connection.createStatement()) {
                s.execute(CREATE_TABLE_SQL);
            }

            // best effort: falls alte DB, füge Spalte sound hinzu
            try (Statement s = connection.createStatement()) {
                s.execute("ALTER TABLE statuses ADD COLUMN sound INTEGER DEFAULT 1");
            } catch (SQLException ignored) {
            }

            // best effort: column last_sent existiert durch CREATE, aber falls alte DB, ignorieren
            try (Statement s = connection.createStatement()) {
                s.execute("ALTER TABLE statuses ADD COLUMN last_sent INTEGER DEFAULT 0");
            } catch (SQLException ignored) {
            }

        } catch (Exception e) {
            getLogger().severe("DB error: " + e.getMessage());
            // falls DB fehlt, plugin nicht fatal stoppen - aber weitere DB-Operationen schlagen fehl
        }
    }

    @Override
    public void onDisable() {
        // cancel pending runnables
        pendingStatusSet.values().forEach(BukkitRunnable::cancel);
        pendingStatusSet.clear();

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().severe("DB close error: " + e.getMessage());
            }
        }
    }

    // Hilfsklasse für Statusdaten (kein Record, weil Abwärtskompatibilität)
    private static class StatusRecord {
        final String message;
        final boolean enabled;
        final long blockedUntil;
        final long lastSent;
        final boolean soundEnabled;

        StatusRecord(String message, boolean enabled, long blockedUntil, long lastSent, boolean soundEnabled) {
            this.message = message;
            this.enabled = enabled;
            this.blockedUntil = blockedUntil;
            this.lastSent = lastSent;
            this.soundEnabled = soundEnabled;
        }
    }

    /* ----------------- Helper: DB / Async Utils ----------------- */

    private void runDbAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    private Optional<StatusRecord> loadStatus(UUID id) {
        try (PreparedStatement ps = connection.prepareStatement(LOAD_STATUS_SQL)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new StatusRecord(
                        rs.getString("message"),
                        rs.getInt("enabled") == 1,
                        rs.getLong("blocked_until"),
                        rs.getLong("last_sent"),
                        rs.getInt("sound") == 1
                ));
            }
        } catch (SQLException e) {
            getLogger().severe("Load status error: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void upsertMessage(UUID id, String message) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_MESSAGE_SQL)) {
            ps.setString(1, id.toString());
            ps.setString(2, message);
            ps.executeUpdate();
        }
    }

    private boolean toggleEnabled(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ENABLED_SQL)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                boolean enabled = rs.getInt("enabled") == 1;
                int newState = enabled ? 0 : 1;
                try (PreparedStatement update = connection.prepareStatement(UPDATE_ENABLED_SQL)) {
                    update.setInt(1, newState);
                    update.setString(2, id.toString());
                    update.executeUpdate();
                }
                return true;
            }
        }
    }

    private boolean toggleSound(UUID id) throws SQLException {
        String select = "SELECT sound FROM statuses WHERE uuid = ?";
        String update = "UPDATE statuses SET sound = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(select)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                boolean sound = rs.getInt("sound") == 1;
                int newState = sound ? 0 : 1;
                try (PreparedStatement upd = connection.prepareStatement(update)) {
                    upd.setInt(1, newState);
                    upd.setString(2, id.toString());
                    upd.executeUpdate();
                }
                return true;
            }
        }
    }

    private int deleteStatus(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(DELETE_SQL)) {
            ps.setString(1, id.toString());
            return ps.executeUpdate();
        }
    }

    private void blockStatus(UUID id, long until) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_BLOCK_SQL)) {
            ps.setString(1, id.toString());
            ps.setString(2, "");
            ps.setLong(3, until);
            ps.executeUpdate();
        }
    }

    private int unblockStatus(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UNBLOCK_SQL)) {
            ps.setString(1, id.toString());
            return ps.executeUpdate();
        }
    }

    /* ----------------- Events ----------------- */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastBroadcast.getOrDefault(id, 0L) + COOLDOWN > now) return;

        runDbAsync(() -> {
            Optional<StatusRecord> maybe = loadStatus(id);
            if (!maybe.isPresent()) return;
            StatusRecord record = maybe.get();

            if (record.blockedUntil > System.currentTimeMillis()) return;
            if (!record.enabled) return;

            // Convert and prepare component once
            String conv = translateToMiniMessage(record.message);
            Component msg = miniMessage.deserialize(conv);

            final boolean playSoundForThisStatus = record.soundEnabled;

            // LuckPerms async load and schedule broadcast on main thread
            luckPerms.getUserManager().loadUser(id).thenAccept(user -> {
                boolean allowed = user.getCachedData().getPermissionData()
                        .checkPermission("griefergame.status.use").asBoolean();
                if (!allowed) return;

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Component displayName = legacySerializer.deserialize(p.getDisplayName());
                        Component full = Component.empty()
                                .append(cachedSuffix).append(Component.space())
                                .append(displayName).append(Component.text(" "))
                                .append(msg);

                        for (Player x : Bukkit.getOnlinePlayers()) {
                            x.sendMessage(full);
                            if (playSoundForThisStatus) {
                                x.playSound(x.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1F, 1F);
                            }
                        }

                        lastBroadcast.put(id, System.currentTimeMillis());
                        // saveUser nur wenn nötig - hier nur aufrufen falls modifiziert (aus Kompatibilitätsgründen entfernt)
                    }
                }.runTaskLater(this, 60L);
            });
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
            p.sendMessage(prefix.append(miniMessage.deserialize("<gray>Deine Eingabe wurde abgebrochen.")));
            return;
        }

        runDbAsync(() -> {
            try {
                upsertMessage(id, msg);
                // Feedback auf main thread
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage(prefix.append(miniMessage.deserialize("<green>Dein Status wurde erfolgreich gesetzt."))));
            } catch (SQLException ex) {
                Bukkit.getScheduler().runTask(this, () ->
                        p.sendMessage(prefix.append(miniMessage.deserialize("<red>DB-Fehler: " + ex.getMessage()))));
            }
        });

        pendingStatusSet.remove(id).cancel();
    }

    /* ----------------- Commands ----------------- */

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }

        Player p = (Player) s;
        UUID id = p.getUniqueId();

        // Berechtigungsübersicht: spezifische Subcommands prüfen
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("toggle")) {
                if (!p.hasPermission("griefergame.status.toggle")) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("sound")) {
                if (!p.hasPermission("griefergame.status.sound")) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }
            } else {
                if (!p.hasPermission("griefergame.status.use")) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Du benötigst mindestens den <dark_purple>Supreme-Rang <red>um dieses Feature nutzen zu können.")));
                    return true;
                }
            }
        } else {
            if (!p.hasPermission("griefergame.status.use")) {
                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Du benötigst mindestens den <dark_purple>Supreme-Rang <red>um dieses Feature nutzen zu können.")));
                return true;
            }
        }

        if (args.length == 0) {
            runDbAsync(() -> {
                Optional<StatusRecord> maybe = loadStatus(id);
                if (!maybe.isPresent()) {
                    Bukkit.getScheduler().runTask(this, () -> p.sendMessage(prefix.append(miniMessage.deserialize("<red>Du hast aktuell keinen Status gesetzt. Nutze <yellow>/status set <red>um deinen Status zu setzen. Gebe anschließend deinen Status im Chat ein."))));
                    return;
                }

                StatusRecord rec = maybe.get();

                if (rec.blockedUntil > System.currentTimeMillis()) {
                    long remaining = (rec.blockedUntil - System.currentTimeMillis()) / 1000 / 60;
                    Bukkit.getScheduler().runTask(this, () ->
                            p.sendMessage(prefix.append(miniMessage.deserialize("<red>Dein Status ist blockiert für noch <yellow>" + remaining + " Minuten."))));
                    return;
                }

                // Use loadUser synchronously from luckperms (blocking call may be ok on main thread, aber wir sind async)
                luckPerms.getUserManager().loadUser(id).thenAccept(user -> {
                    if (user == null) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Fehler beim Laden deines Status."))));
                        return;
                    }

                    String conv = translateToMiniMessage(rec.message);
                    Component msg = miniMessage.deserialize(conv);

                    String rawSuffix = Optional.ofNullable(user.getCachedData().getMetaData().getSuffix()).orElse("§-§7§2§5§2§e§9§d§6⚐");
                    rawSuffix = PlaceholderAPI.setPlaceholders(p, rawSuffix);
                    Component suffix = legacySerializer.deserialize(rawSuffix);

                    String rawDisplayName = Optional.ofNullable(user.getCachedData().getMetaData().getPrefix()).orElse("") + p.getName();
                    rawDisplayName = PlaceholderAPI.setPlaceholders(p, rawDisplayName);
                    Component name = legacySerializer.deserialize(rawDisplayName);

                    long remainingCooldown = Math.max(0, COOLDOWN - (System.currentTimeMillis() - lastBroadcast.getOrDefault(id, 0L)));
                    long remainingMin = remainingCooldown / 1000 / 60;

                    String statusLine = rec.enabled ? "<green>aktiviert" : "<red>deaktiviert";
                    String cooldownLine = remainingCooldown > 0
                            ? "<gray>Dein Status wird wieder in <yellow>" + remainingMin + " Minuten <gray> gesendet."
                            : "<gray>Dein Status wird beim nächsten Join gesendet.";
                    String soundLine = rec.soundEnabled ? "<green>Sound aktiviert" : "<red>Sound deaktiviert";

                    Bukkit.getScheduler().runTask(this, () -> {
                        p.sendMessage(prefix.append(miniMessage.deserialize("<gray>Dein Status ist aktuell " + statusLine + "<gray>. Dieser hat eine Abklingzeit von <yellow>" + (COOLDOWN / 1000 / 60) + " Minuten.")));
                        p.sendMessage(prefix.append(miniMessage.deserialize("<gray>Dein Status sieht aktuell so aus:")));
                        p.sendMessage(Component.empty().append(suffix).append(Component.space()).append(name).append(Component.text(" ")).append(msg));
                        p.sendMessage(prefix.append(miniMessage.deserialize(cooldownLine)));
                        p.sendMessage(prefix.append(miniMessage.deserialize("<gray>" + soundLine)));
                        p.sendMessage(prefix.append(miniMessage.deserialize("<gray>Nutze <yellow>/status set<gray>, um deinen Status zu setzen. Mit <yellow>/status toggle<gray> kannst du ihn aktivieren/deaktivieren. Mit <yellow>/status sound<gray> kannst du den Join-Sound umschalten.")));
                    });
                });
            });
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "set":
                if (pendingStatusSet.containsKey(id)) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Du setzt bereits einen Status. Nutze <yellow>-cancel <red>um abzubrechen.")));
                    return true;
                }

                p.sendMessage(prefix.append(miniMessage.deserialize("<gray>Bitte gib deinen Status im Chat ein. Mit <red>-cancel <gray>kannst du abbrechen.")));
                p.sendMessage(prefix.append(miniMessage.deserialize(
                        "<gray><italic>Tipp:</italic> Du kannst <gold>MiniMessage</gold> für Farben, Formatierungen & mehr nutzen.\n"
                                + "<gray>Besuche dafür die Webseite: <click:open_url:'https://shorturl.at/kCTN2'><hover:show_text:'<gray>Öffnet den MiniMessage generator.'><aqua>https://shorturl.at/kCTN2</aqua></hover></click>")));
                BukkitRunnable timeout = new BukkitRunnable() {
                    public void run() {
                        if (pendingStatusSet.containsKey(id)) {
                            pendingStatusSet.remove(id);
                            p.sendMessage(prefix.append(miniMessage.deserialize("<red>Eingabe wurde abgebrochen. (Timeout)")));
                        }
                    }
                };
                pendingStatusSet.put(id, timeout);
                timeout.runTaskLater(this, 40 * 20); // 40 Sekunden Timeout
                return true;

            case "toggle":
                runDbAsync(() -> {
                    try {
                        boolean ok = toggleEnabled(id);
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize(ok ? ("" + "<green>Status umgeschaltet.") : "<red>Kein Eintrag gefunden."))));
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Fehler beim Umschalten."))));
                    }
                });
                return true;

            case "sound":
                // Berechtigung wird oben geprüft
                runDbAsync(() -> {
                    try {
                        boolean ok = toggleSound(id);
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize(ok ? "<green>Sound umgeschaltet." : "<red>Kein Eintrag gefunden. Lege zuerst einen Status an mit <yellow>/status set"))));
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Fehler beim Umschalten des Sounds."))));
                    }
                });
                return true;

            case "delete":
                runDbAsync(() -> {
                    try {
                        int updated = deleteStatus(id);
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize(updated > 0 ? "<gray>Dein Status wurde <red>gelöscht<gray>!" : "<red>Es wurde kein Status gefunden. Setze ihn mit <yellow>/status set"))));
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Fehler beim Löschen."))));
                    }
                });
                return true;

            case "block":
                if (!p.hasPermission("griefergame.status.admin")) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }
                if (args.length < 3) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<gray>/status block <Spieler> <Zeit z.B. 10s, 5m, 2h>")));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                long seconds;
                try {
                    seconds = parseTimeToSeconds(args[2]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Ungültiges Zeitformat.")));
                    return true;
                }
                long until = System.currentTimeMillis() + (seconds * 1000);
                runDbAsync(() -> {
                    try {
                        blockStatus(target.getUniqueId(), until);
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (target.isOnline() && target.getPlayer() != null) {
                                target.getPlayer().sendMessage(prefix.append(miniMessage.deserialize("<red>Du wurdest blockiert für <yellow>" + args[2])));
                            }
                            p.sendMessage(prefix.append(miniMessage.deserialize("<green>Spieler blockiert für <yellow>" + args[2])));
                        });
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Datenbankfehler: " + e.getMessage()))));
                    }
                });
                return true;

            case "unblock":
                if (!p.hasPermission("griefergame.status.admin")) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<red>Keine Rechte.")));
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(prefix.append(miniMessage.deserialize("<gray>/status unblock <Spieler>")));
                    return true;
                }
                OfflinePlayer unblock = Bukkit.getOfflinePlayer(args[1]);
                runDbAsync(() -> {
                    try {
                        int rows = unblockStatus(unblock.getUniqueId());
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (rows > 0) {
                                p.sendMessage(prefix.append(miniMessage.deserialize("<green>Blockierung aufgehoben.")));
                                if (unblock.isOnline() && unblock.getPlayer() != null) {
                                    unblock.getPlayer().sendMessage(prefix.append(miniMessage.deserialize("<green>Du wurdest entblockt.")));
                                }
                            } else {
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Kein Eintrag gefunden.")));
                            }
                        });
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(this, () ->
                                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Fehler beim Entblocken."))));
                    }
                });
                return true;

            default:
                p.sendMessage(prefix.append(miniMessage.deserialize("<red>Unbekannter Subbefehl.")));
                return true;
        }
    }

    private long parseTimeToSeconds(String input) throws NumberFormatException {
        input = input.toLowerCase(Locale.ROOT).trim();
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
            return Arrays.asList("set", "toggle", "delete", "block", "unblock", "sound")
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

    public static String translateToMiniMessage(String input) {
        if (input == null) return "";
        input = input.replace('§', '&');
        // schnelleres Ersetzen durch einmaliges Durchlaufen der Zeichen, statt viele replace-Aufrufe
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                String repl = null;
                switch (code) {
                    case '0': repl = "<black>"; break;
                    case '1': repl = "<dark_blue>"; break;
                    case '2': repl = "<dark_green>"; break;
                    case '3': repl = "<dark_aqua>"; break;
                    case '4': repl = "<dark_red>"; break;
                    case '5': repl = "<dark_purple>"; break;
                    case '6': repl = "<gold>"; break;
                    case '7': repl = "<gray>"; break;
                    case '8': repl = "<dark_gray>"; break;
                    case '9': repl = "<blue>"; break;
                    case 'a': repl = "<green>"; break;
                    case 'b': repl = "<aqua>"; break;
                    case 'c': repl = "<red>"; break;
                    case 'd': repl = "<light_purple>"; break;
                    case 'e': repl = "<yellow>"; break;
                    case 'f': repl = "<white>"; break;
                    case 'l': repl = "<b>"; break;
                    case 'm': repl = "<st>"; break;
                    case 'n': repl = "<u>"; break;
                    case 'o': repl = "<i>"; break;
                    case 'k': repl = "<obf>"; break;
                    case 'r': repl = "<reset>"; break;
                }
                if (repl != null) {
                    out.append(repl);
                    i++; // skip code
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
