package net.lunix.pvptoggle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PvpToggle implements ModInitializer {

    public static final String MOD_ID = "pvptoggle";
    static final Logger LOGGER = LoggerFactory.getLogger("pvpToggle");

    /** Legacy — kept only for migrating existing NBT data to PlayerDataStore. Do not use. */
    public static final AttachmentType<Boolean> PVP_FLAGGED = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(MOD_ID, "pvp_flagged"),
        builder -> builder.persistent(Codec.BOOL).initializer(() -> false)
    );

    // --- Config ---
    public static boolean pvpEnabled   = true;
    static boolean disableRepairInPvP = false;
    static int     cooldownSeconds    = 30;
    static int     warmupSeconds      = 5;
    static boolean broadcastToggle    = true;
    static int     autoUnflagMinutes  = 0;

    // --- Runtime state ---
    private static final Map<UUID, Long> cooldownExpiry   = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> warmupExpiry     = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastActivityTime = new ConcurrentHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    // =========================================================================
    // Init
    // =========================================================================

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            configPath = FabricLoader.getInstance().getConfigDir().resolve("pvptoggle.json");
            loadConfig();
            PlayerDataStore.load();
            applyServerPvp(server, true);
        });

        // Cancel PvP damage if system is off or either player is not flagged
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer defender)) return true;

            Entity responsible = source.getEntity();
            if (!(responsible instanceof ServerPlayer attacker)) return true;

            if (attacker == defender) return true;

            if (!pvpEnabled) return false; // system disabled — block all PvP

            boolean attackerFlagged = PlayerDataStore.isPvpFlagged(attacker.getUUID());
            boolean defenderFlagged = PlayerDataStore.isPvpFlagged(defender.getUUID());

            if (!attackerFlagged || !defenderFlagged) {
                if (!attackerFlagged) {
                    attacker.sendSystemMessage(Component.literal(
                        ChatFormatting.YELLOW + "You are not flagged for PvP. Use /pvptoggle to opt in."
                    ));
                } else {
                    attacker.sendSystemMessage(Component.literal(
                        ChatFormatting.YELLOW + "That player is not flagged for PvP."
                    ));
                }
                return false;
            }

            // Both flagged — reset cooldown and last activity for both
            if (cooldownSeconds > 0) {
                long expiry = System.currentTimeMillis() + (cooldownSeconds * 1000L);
                cooldownExpiry.put(attacker.getUUID(), expiry);
                cooldownExpiry.put(defender.getUUID(), expiry);
            }
            long now = System.currentTimeMillis();
            lastActivityTime.put(attacker.getUUID(), now);
            lastActivityTime.put(defender.getUUID(), now);

            return true;
        });

        // Tick: warmup completion, action bar HUD, auto-unflag
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            long now = System.currentTimeMillis();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();

                // Warmup completion
                Long warmup = warmupExpiry.get(uuid);
                if (warmup != null && now >= warmup) {
                    warmupExpiry.remove(uuid);
                    activateFlag(player, server);
                    player.sendSystemMessage(Component.literal(
                        ChatFormatting.RED + "PvP ENABLED. You can now deal and take damage from other flagged players."
                    ));
                }

                // Action bar HUD
                updateActionBar(player);

                // Auto-unflag idle players
                if (autoUnflagMinutes > 0
                        && PlayerDataStore.isPvpFlagged(uuid)
                        && getRemainingCooldown(uuid) == 0
                        && !warmupExpiry.containsKey(uuid)) {
                    Long lastActivity = lastActivityTime.get(uuid);
                    if (lastActivity != null && (now - lastActivity) > autoUnflagMinutes * 60_000L) {
                        deactivateFlag(player, server);
                        player.sendSystemMessage(Component.literal(
                            ChatFormatting.GREEN + "PvP automatically disabled due to inactivity."
                        ));
                    }
                }
            }
        });

        // Migrate legacy NBT pvpFlagged data to PlayerDataStore and remove from NBT
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            boolean legacyFlagged = player.getAttachedOrElse(PVP_FLAGGED, false);
            if (legacyFlagged && !PlayerDataStore.isPvpFlagged(player.getUUID())) {
                LOGGER.info("Migrating legacy pvpFlagged for player {}", player.getName().getString());
                PlayerDataStore.setPvpFlagged(player.getUUID(), true);
            }
            player.setAttached(PVP_FLAGGED, null);
        });

        // Cleanup on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            cooldownExpiry.remove(uuid);
            warmupExpiry.remove(uuid);
            lastActivityTime.remove(uuid);
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher)
        );

        // Wire expRepair compat if installed
        if (FabricLoader.getInstance().isModLoaded("exprepair")) {
            ExpRepairCompat.init();
        }
    }

    // =========================================================================
    // Action bar HUD
    // =========================================================================

    private static void updateActionBar(ServerPlayer player) {
        UUID uuid = player.getUUID();

        if (warmupExpiry.containsKey(uuid)) {
            long remaining = getRemainingWarmup(uuid);
            player.displayClientMessage(Component.literal(
                ChatFormatting.YELLOW + "⚔ Entering PvP in " + remaining + "s..."
            ), true);
            return;
        }

        if (pvpEnabled && PlayerDataStore.isPvpFlagged(player.getUUID())) {
            long cooldown = getRemainingCooldown(uuid);
            if (cooldown > 0) {
                player.displayClientMessage(Component.literal(
                    ChatFormatting.RED + "⚔ PvP Active" + ChatFormatting.GRAY + " | " +
                    ChatFormatting.YELLOW + "Combat: " + cooldown + "s"
                ), true);
            } else {
                player.displayClientMessage(Component.literal(
                    ChatFormatting.RED + "⚔ PvP Active"
                ), true);
            }
        }
        // Not flagged — send nothing; action bar fades naturally
    }

    // =========================================================================
    // Shared flag helpers
    // =========================================================================

    private static void activateFlag(ServerPlayer player, MinecraftServer server) {
        PlayerDataStore.setPvpFlagged(player.getUUID(), true);
        lastActivityTime.put(player.getUUID(), System.currentTimeMillis());

        if (broadcastToggle) {
            Component msg = Component.literal(
                ChatFormatting.GRAY + "[PvP] " + ChatFormatting.RED + player.getName().getString()
                + ChatFormatting.GRAY + " has entered PvP mode."
            );
            server.getPlayerList().getPlayers().forEach(p -> {
                if (!p.getUUID().equals(player.getUUID())) p.sendSystemMessage(msg);
            });
        }
    }

    private static void deactivateFlag(ServerPlayer player, MinecraftServer server) {
        PlayerDataStore.setPvpFlagged(player.getUUID(), false);
        UUID uuid = player.getUUID();
        cooldownExpiry.remove(uuid);
        lastActivityTime.remove(uuid);

        if (broadcastToggle) {
            Component msg = Component.literal(
                ChatFormatting.GRAY + "[PvP] " + ChatFormatting.GREEN + player.getName().getString()
                + ChatFormatting.GRAY + " has left PvP mode."
            );
            server.getPlayerList().getPlayers().forEach(p -> {
                if (!p.getUUID().equals(player.getUUID())) p.sendSystemMessage(msg);
            });
        }
    }

    // =========================================================================
    // Commands
    // =========================================================================

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("pvptoggle")
            .then(Commands.literal("on")
                .executes(ctx -> setFlag(ctx.getSource(), true)))
            .then(Commands.literal("off")
                .executes(ctx -> setFlag(ctx.getSource(), false)))
            .then(Commands.literal("status")
                .executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("list")
                .executes(ctx -> listFlagged(ctx.getSource())))
            .then(Commands.literal("admin")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> showAdminSettings(ctx.getSource()))
                .then(Commands.literal("enable")
                    .executes(ctx -> setSystemEnabled(ctx.getSource(), true)))
                .then(Commands.literal("disable")
                    .executes(ctx -> setSystemEnabled(ctx.getSource(), false)))
                .then(Commands.literal("warmup")
                    .executes(ctx -> showSetting(ctx.getSource(), "Warmup", warmupSeconds, "second(s)"))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                        .executes(ctx -> setWarmup(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("cooldown")
                    .executes(ctx -> showSetting(ctx.getSource(), "Combat cooldown", cooldownSeconds, "second(s)"))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                        .executes(ctx -> setCooldown(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("autoUnflag")
                    .executes(ctx -> showSetting(ctx.getSource(), "Auto-unflag idle time", autoUnflagMinutes, "minute(s)"))
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                        .executes(ctx -> setAutoUnflag(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "minutes")))))
                .then(Commands.literal("broadcast")
                    .then(Commands.literal("on")
                        .executes(ctx -> setBroadcast(ctx.getSource(), true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setBroadcast(ctx.getSource(), false))))
                .then(Commands.literal("disableRepair")
                    .then(Commands.literal("on")
                        .executes(ctx -> setDisableRepair(ctx.getSource(), true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> setDisableRepair(ctx.getSource(), false))))
                .then(Commands.literal("reload")
                    .executes(ctx -> reloadConfig(ctx.getSource(), false))
                    .then(Commands.literal("silent")
                        .executes(ctx -> reloadConfig(ctx.getSource(), true))))
                .then(Commands.literal("set")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("on")
                            .executes(ctx -> adminSet(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), true)))
                        .then(Commands.literal("off")
                            .executes(ctx -> adminSet(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), false)))))
                .then(Commands.literal("status")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> adminStatus(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))))
            .executes(ctx -> toggle(ctx.getSource()));

        var rootNode = dispatcher.register(root);
        dispatcher.register(Commands.literal("pvp").redirect(rootNode));
    }

    // =========================================================================
    // Player commands
    // =========================================================================

    private int toggle(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean current = PlayerDataStore.isPvpFlagged(player.getUUID());
        boolean inWarmup = warmupExpiry.containsKey(player.getUUID());
        return setFlag(source, !current && !inWarmup);
    }

    private int setFlag(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID uuid = player.getUUID();

        if (!pvpEnabled) {
            player.sendSystemMessage(Component.literal(
                ChatFormatting.RED + "PvP flagging is currently disabled server-wide."
            ));
            return 0;
        }

        if (!enabled) {
            if (warmupExpiry.containsKey(uuid)) {
                warmupExpiry.remove(uuid);
                player.sendSystemMessage(Component.literal(
                    ChatFormatting.YELLOW + "PvP flag warmup cancelled."
                ));
                return 1;
            }
            if (PlayerDataStore.isPvpFlagged(uuid)) {
                long remaining = getRemainingCooldown(uuid);
                if (remaining > 0) {
                    player.sendSystemMessage(Component.literal(
                        ChatFormatting.RED + "You are in combat! Cannot disable PvP for another " + remaining + " second(s)."
                    ));
                    return 0;
                }
                deactivateFlag(player, source.getServer());
                player.sendSystemMessage(Component.literal(
                    ChatFormatting.GREEN + "PvP DISABLED. You are now protected from other players."
                ));
            } else {
                player.sendSystemMessage(Component.literal(ChatFormatting.GRAY + "PvP is already disabled."));
            }
            return 1;
        }

        if (PlayerDataStore.isPvpFlagged(uuid) || warmupExpiry.containsKey(uuid)) {
            player.sendSystemMessage(Component.literal(ChatFormatting.GRAY + "PvP is already enabled (or warming up)."));
            return 1;
        }

        if (warmupSeconds > 0) {
            warmupExpiry.put(uuid, System.currentTimeMillis() + (warmupSeconds * 1000L));
            player.sendSystemMessage(Component.literal(
                ChatFormatting.YELLOW + "Entering PvP mode in " + warmupSeconds + " second(s)... Use /pvp off to cancel."
            ));
        } else {
            activateFlag(player, source.getServer());
            player.sendSystemMessage(Component.literal(
                ChatFormatting.RED + "PvP ENABLED. You can now deal and take damage from other flagged players."
            ));
        }
        return 1;
    }

    private int status(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID uuid = player.getUUID();
        boolean flagged = PlayerDataStore.isPvpFlagged(uuid);
        boolean inWarmup = warmupExpiry.containsKey(uuid);

        if (inWarmup) {
            player.sendSystemMessage(Component.literal(ChatFormatting.YELLOW + "Your PvP status: WARMING UP"));
        } else {
            player.sendSystemMessage(Component.literal(
                "Your PvP status: " + (flagged
                    ? ChatFormatting.RED + "ENABLED"
                    : ChatFormatting.GREEN + "DISABLED")
            ));
        }
        if (flagged) {
            long remaining = getRemainingCooldown(uuid);
            if (remaining > 0) {
                player.sendSystemMessage(Component.literal(
                    ChatFormatting.YELLOW + "  Combat cooldown: " + remaining + " second(s) remaining"
                ));
            }
        }
        return 1;
    }

    private int listFlagged(CommandSourceStack source) {
        var flagged = source.getServer().getPlayerList().getPlayers().stream()
            .filter(p -> PlayerDataStore.isPvpFlagged(p.getUUID()))
            .toList();

        if (flagged.isEmpty()) {
            source.sendSystemMessage(Component.literal(
                ChatFormatting.GRAY + "No players are currently flagged for PvP."
            ));
        } else {
            source.sendSystemMessage(Component.literal(ChatFormatting.GOLD + "--- Players flagged for PvP ---"));
            for (ServerPlayer p : flagged) {
                long remaining = getRemainingCooldown(p.getUUID());
                source.sendSystemMessage(Component.literal(
                    "  " + ChatFormatting.RED + p.getName().getString()
                    + (remaining > 0 ? ChatFormatting.YELLOW + "  (in combat, " + remaining + "s)" : "")
                ));
            }
        }
        return 1;
    }

    // =========================================================================
    // Admin commands
    // =========================================================================

    private int showAdminSettings(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(ChatFormatting.GOLD + "--- pvpToggle Admin Settings ---"));
        source.sendSystemMessage(Component.literal(
            "  system:            " + (pvpEnabled ? ChatFormatting.GREEN + "ENABLED" : ChatFormatting.RED + "DISABLED")
        ));
        source.sendSystemMessage(Component.literal(
            "  warmupSeconds:     " + ChatFormatting.AQUA + warmupSeconds
            + (warmupSeconds == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        source.sendSystemMessage(Component.literal(
            "  cooldownSeconds:   " + ChatFormatting.AQUA + cooldownSeconds
            + (cooldownSeconds == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        source.sendSystemMessage(Component.literal(
            "  autoUnflagMinutes: " + ChatFormatting.AQUA + autoUnflagMinutes
            + (autoUnflagMinutes == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        source.sendSystemMessage(Component.literal(
            "  broadcastToggle:   " + (broadcastToggle ? ChatFormatting.GREEN + "ON" : ChatFormatting.RED + "OFF")
        ));
        source.sendSystemMessage(Component.literal(
            "  disableRepairInPvP:" + (disableRepairInPvP ? ChatFormatting.RED + " ON" : ChatFormatting.GREEN + " OFF")
            + ChatFormatting.GRAY + (FabricLoader.getInstance().isModLoaded("exprepair") ? "" : "  (expRepair not installed)")
        ));
        return 1;
    }

    private int setSystemEnabled(CommandSourceStack source, boolean enabled) {
        pvpEnabled = enabled;
        saveConfig();
        applyServerPvp(source.getServer(), enabled);

        if (!enabled) {
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                if (warmupExpiry.remove(uuid) != null) {
                    player.sendSystemMessage(Component.literal(
                        ChatFormatting.YELLOW + "Your PvP flag warmup was cancelled (system disabled)."
                    ));
                }
                cooldownExpiry.remove(uuid);
            }
        }

        Component msg = Component.literal(
            ChatFormatting.GOLD + "[pvpToggle] PvP flagging has been "
            + (enabled ? ChatFormatting.GREEN + "ENABLED" : ChatFormatting.RED + "DISABLED")
            + ChatFormatting.GOLD + " server-wide by an admin."
        );
        source.getServer().getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(msg));
        return 1;
    }

    private int setWarmup(CommandSourceStack source, int seconds) {
        warmupSeconds = seconds;
        saveConfig();
        source.sendSystemMessage(Component.literal(
            "PvP flag warmup set to " + ChatFormatting.AQUA + seconds + " second(s)"
            + (seconds == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        return 1;
    }

    private int setCooldown(CommandSourceStack source, int seconds) {
        cooldownSeconds = seconds;
        saveConfig();
        source.sendSystemMessage(Component.literal(
            "PvP combat cooldown set to " + ChatFormatting.AQUA + seconds + " second(s)"
            + (seconds == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        return 1;
    }

    private int setAutoUnflag(CommandSourceStack source, int minutes) {
        autoUnflagMinutes = minutes;
        saveConfig();
        source.sendSystemMessage(Component.literal(
            "Auto-unflag idle time set to " + ChatFormatting.AQUA + minutes + " minute(s)"
            + (minutes == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        return 1;
    }

    private int setBroadcast(CommandSourceStack source, boolean enabled) {
        broadcastToggle = enabled;
        saveConfig();
        source.sendSystemMessage(Component.literal(
            "PvP flag toggle broadcast: " + (enabled ? ChatFormatting.GREEN + "ON" : ChatFormatting.RED + "OFF")
        ));
        return 1;
    }

    private int setDisableRepair(CommandSourceStack source, boolean enabled) {
        if (!FabricLoader.getInstance().isModLoaded("exprepair")) {
            source.sendSystemMessage(Component.literal(
                ChatFormatting.RED + "expRepair is not installed on this server."
            ));
            return 0;
        }
        disableRepairInPvP = enabled;
        saveConfig();
        source.sendSystemMessage(Component.literal(
            "Repair suppression for flagged PvP players: " + (enabled
                ? ChatFormatting.RED + "ON"
                : ChatFormatting.GREEN + "OFF")
        ));
        return 1;
    }

    private int reloadConfig(CommandSourceStack source, boolean silent) {
        loadConfig();
        Component msg = Component.literal(ChatFormatting.GREEN + "[pvpToggle] Config reloaded by an admin.");
        if (silent) {
            source.sendSystemMessage(msg);
        } else {
            source.getServer().getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(msg));
        }
        return 1;
    }

    private int adminSet(CommandSourceStack source, ServerPlayer target, boolean enabled) {
        UUID uuid = target.getUUID();
        warmupExpiry.remove(uuid);

        boolean alreadyFlagged = PlayerDataStore.isPvpFlagged(target.getUUID());
        String state = enabled ? ChatFormatting.RED + "ENABLED" : ChatFormatting.GREEN + "DISABLED";

        if (enabled && !alreadyFlagged) {
            activateFlag(target, source.getServer());
        } else if (!enabled && alreadyFlagged) {
            cooldownExpiry.remove(uuid);
            deactivateFlag(target, source.getServer());
        }

        source.sendSystemMessage(Component.literal(
            "Set " + target.getName().getString() + "'s PvP flag to " + state
        ));
        target.sendSystemMessage(Component.literal(
            "An admin has set your PvP flag to " + state
        ));
        return 1;
    }

    private int adminStatus(CommandSourceStack source, ServerPlayer target) {
        UUID uuid = target.getUUID();
        boolean flagged = PlayerDataStore.isPvpFlagged(uuid);
        boolean inWarmup = warmupExpiry.containsKey(uuid);
        long remaining = getRemainingCooldown(uuid);

        String statusStr = inWarmup
            ? ChatFormatting.YELLOW + "WARMING UP"
            : flagged ? ChatFormatting.RED + "ENABLED"
            : ChatFormatting.GREEN + "DISABLED";

        source.sendSystemMessage(Component.literal(
            target.getName().getString() + "'s PvP status: " + statusStr
            + (remaining > 0 ? ChatFormatting.YELLOW + "  (" + remaining + "s cooldown)" : "")
        ));
        return 1;
    }

    private int showSetting(CommandSourceStack source, String label, int value, String unit) {
        source.sendSystemMessage(Component.literal(
            label + ": " + ChatFormatting.AQUA + value + " " + unit
            + (value == 0 ? ChatFormatting.GRAY + "  (disabled)" : "")
        ));
        return 1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static long getRemainingCooldown(UUID uuid) {
        Long expiry = cooldownExpiry.get(uuid);
        if (expiry == null) return 0;
        long remaining = (expiry - System.currentTimeMillis() + 999) / 1000;
        return Math.max(0, remaining);
    }

    private static long getRemainingWarmup(UUID uuid) {
        Long expiry = warmupExpiry.get(uuid);
        if (expiry == null) return 0;
        long remaining = (expiry - System.currentTimeMillis() + 999) / 1000;
        return Math.max(0, remaining);
    }

    /**
     * Persists pvp= to server.properties. In-memory control is handled by DedicatedServerMixin
     * which overrides isPvpAllowed() to return PvpToggle.pvpEnabled at runtime — no restart needed.
     */
    private static void applyServerPvp(MinecraftServer server, boolean pvp) {
        Path propsPath = FabricLoader.getInstance().getGameDir().resolve("server.properties");
        if (!Files.exists(propsPath)) return;

        try {
            String content = Files.readString(propsPath);
            String updated;
            if (content.contains("pvp=true") || content.contains("pvp=false")) {
                updated = content.replaceAll("pvp=(true|false)", "pvp=" + pvp);
            } else {
                updated = content + "\npvp=" + pvp + "\n";
            }
            if (!updated.equals(content)) {
                Files.writeString(propsPath, updated);
                LOGGER.info("[pvpToggle] Set pvp={} in server.properties.", pvp);
            }
        } catch (Exception e) {
            LOGGER.warn("[pvpToggle] Could not update server.properties: {}", e.getMessage());
        }
    }

    private static void loadConfig() {
        if (!Files.exists(configPath)) {
            saveConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            if (obj != null) {
                if (obj.has("pvpEnabled"))         pvpEnabled         = obj.get("pvpEnabled").getAsBoolean();
                if (obj.has("disableRepairInPvP")) disableRepairInPvP = obj.get("disableRepairInPvP").getAsBoolean();
                if (obj.has("cooldownSeconds"))    cooldownSeconds    = obj.get("cooldownSeconds").getAsInt();
                if (obj.has("warmupSeconds"))      warmupSeconds      = obj.get("warmupSeconds").getAsInt();
                if (obj.has("broadcastToggle"))    broadcastToggle    = obj.get("broadcastToggle").getAsBoolean();
                if (obj.has("autoUnflagMinutes"))  autoUnflagMinutes  = obj.get("autoUnflagMinutes").getAsInt();
            }
        } catch (Exception e) {
            // Use defaults on error
        }
    }

    static void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("pvpEnabled",         pvpEnabled);
            obj.addProperty("disableRepairInPvP", disableRepairInPvP);
            obj.addProperty("cooldownSeconds",    cooldownSeconds);
            obj.addProperty("warmupSeconds",      warmupSeconds);
            obj.addProperty("broadcastToggle",    broadcastToggle);
            obj.addProperty("autoUnflagMinutes",  autoUnflagMinutes);
            GSON.toJson(obj, writer);
        } catch (Exception e) {
            // Silently fail
        }
    }
}
