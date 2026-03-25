package net.lunix.pvptoggle;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;

public class ExpRepairCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("pvpToggle");

    @SuppressWarnings("unchecked")
    public static void init() {
        try {
            Class<?> exprepairClass = Class.forName("net.lunix.exprepair.Exprepair");
            Field hooksField = exprepairClass.getField("REPAIR_SUPPRESSION_HOOKS");
            List<Predicate<ServerPlayer>> hooks = (List<Predicate<ServerPlayer>>) hooksField.get(null);
            hooks.add(player ->
                PvpToggle.disableRepairInPvP && PlayerDataStore.isPvpFlagged(player.getUUID())
            );
            LOGGER.info("[pvpToggle] expRepair integration enabled.");
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[pvpToggle] expRepair found but does not expose REPAIR_SUPPRESSION_HOOKS " +
                        "(requires expRepair 1.7+). PvP repair suppression disabled.");
        } catch (Exception e) {
            LOGGER.warn("[pvpToggle] expRepair integration failed: {}", e.getMessage());
        }
    }
}
