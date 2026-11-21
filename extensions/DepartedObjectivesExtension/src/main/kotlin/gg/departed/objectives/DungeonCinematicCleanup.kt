package gg.departed.objectives

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import dev.departed.itemcore.api.spi.InstancedRuns
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.potion.PotionEffectType

/**
 * A Typewriter camera cinematic at the end of the tutorial dungeon applies an infinite
 * invisibility potion + flight for the camera. When the run ends and the player is sent out,
 * the cinematic can fail to tear down, leaving the player permanently invisible and flying.
 *
 * Every DepartedDungeons run plays in a copy inside a host world, so leaving a run, finished or
 * not, is leaving a host world (asked of the run host through ItemCore's [InstancedRuns]); we
 * strip that leftover state a short moment later (after the cinematic should be done). We only
 * touch the *infinite* invisibility (the cinematic's signature), so a finite class-ability
 * invisibility is left alone.
 */
@Singleton
class DungeonCinematicCleanup : Initializable, Listener {

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLeaveDungeon(event: PlayerChangedWorldEvent) {
        if (InstancedRuns.get().hostWorld(event.from)) scheduleCleanup(event.player)
    }

    private fun scheduleCleanup(player: Player?) {
        player ?: return
        server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) player.clearLeftoverCinematicState()
        }, CLEANUP_DELAY_TICKS)
    }

    private fun Player.clearLeftoverCinematicState() {
        val invisibility = getPotionEffect(PotionEffectType.INVISIBILITY)
        // Infinite duration is represented as a negative value; that's the cinematic's effect.
        if (invisibility != null && invisibility.duration < 0) {
            removePotionEffect(PotionEffectType.INVISIBILITY)
        }
        if (gameMode != GameMode.CREATIVE && gameMode != GameMode.SPECTATOR) {
            isFlying = false
            allowFlight = false
        }
    }

    private companion object {
        const val CLEANUP_DELAY_TICKS = 40L // ~2s: let the cinematic finish first
    }
}
