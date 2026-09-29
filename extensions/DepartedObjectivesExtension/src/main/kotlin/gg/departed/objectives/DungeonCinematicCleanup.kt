package gg.departed.objectives

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import net.playavalon.mythicdungeons.api.events.dungeon.PlayerFinishDungeonEvent
import net.playavalon.mythicdungeons.api.events.dungeon.PlayerLeaveDungeonEvent
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.potion.PotionEffectType

/**
 * A Typewriter camera cinematic at the end of the tutorial dungeon applies an infinite
 * invisibility potion + flight for the camera. When MythicDungeons ends the dungeon and
 * teleports the player out (within the same world), the cinematic can fail to tear down,
 * leaving the player permanently invisible and flying.
 *
 * MythicDungeons fires [PlayerFinishDungeonEvent] when the run ends — the correct, plugin-aware
 * signal — so we strip that leftover state a short moment later (after the cinematic should be
 * done). We only touch the *infinite* invisibility (the cinematic's signature), so a finite
 * class-ability invisibility is left alone.
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
    fun onFinishDungeon(event: PlayerFinishDungeonEvent) {
        scheduleCleanup(event.player)
    }

    // The player is teleported out here even if they didn't "finish" — this is the main path.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onLeaveDungeon(event: PlayerLeaveDungeonEvent) {
        scheduleCleanup(event.player)
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
