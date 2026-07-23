package gg.departed.basic.entries.dialogue

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitTask

/**
 * Global, always-on guard that pins a player to the invisible dialogue control mount for the whole
 * conversation and only lets go on the legitimate exit conditions.
 *
 * The lock has three layers, because none alone is sufficient on modern Paper:
 *  1. [onDismount] cancels [EntityDismountEvent] — a best-effort first line (leaks through on Paper).
 *  2. [reseat] every tick re-seats any player who slipped off, so a dismount lasts at most one tick.
 *  3. [onMove] pins the player to their frozen anchor during that one-tick window so they can't even
 *     take a step (look direction stays free).
 *
 * The mount is only removed when the player is done with the dialogue (handled by DialogueMountControl's
 * deferred release), logs out ([onQuit]), takes damage ([onDamage]), or warps away ([onTeleport] and the
 * distance check inside [DialogueMountControl.reseatSlippedPlayers]).
 */
@Singleton
class DialogueMountGuard : Initializable, Listener {

    private var reseatTask: BukkitTask? = null
    private var passengerListener: PacketListenerAbstract? = null

    override suspend fun initialize() {
        // Sweep away any mounts a previous unclean run (crash / hard reload) may have left behind.
        DialogueMountControl.purgeAll()
        server.pluginManager.registerEvents(this, plugin)
        reseatTask = server.scheduler.runTaskTimer(plugin, Runnable {
            DialogueMountControl.reseatSlippedPlayers()
        }, 1L, 1L)
        registerPassengerHider()
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
        reseatTask?.cancel()
        reseatTask = null
        passengerListener?.let {
            try { PacketEvents.getAPI().eventManager.unregisterListener(it) } catch (_: Throwable) {}
        }
        passengerListener = null
        // Don't leave control stands in the world when the extension unloads/reloads.
        DialogueMountControl.purgeAll()
    }

    /**
     * Make OTHER players see the mounted player STANDING instead of sitting/floating on the invisible
     * control stand. We strip the seated player out of the outgoing "set passengers" packet for every
     * viewer except the seated player themselves (who keeps the real mount for first-person camera and
     * input). Without the passenger relationship those clients keep rendering the player at their last
     * broadcast position — where they were standing when the dialogue began — so they just appear to be
     * standing there. The seated player still rides normally.
     */
    private fun registerPassengerHider() {
        val hasPacketEvents = Bukkit.getPluginManager().getPlugin("packetevents") != null ||
                Bukkit.getPluginManager().getPlugin("PacketEvents") != null
        if (!hasPacketEvents) return
        val listener = object : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            override fun onPacketSend(event: PacketSendEvent) {
                if (event.packetType != PacketType.Play.Server.SET_PASSENGERS) return
                val receiver = event.user ?: return
                val wrapper = WrapperPlayServerSetPassengers(event)
                val seatedUuid = DialogueMountControl.seatedPlayerOfStand(wrapper.entityId) ?: return
                if (receiver.uuid == seatedUuid) return // the rider must keep the real mount
                val seated = Bukkit.getPlayer(seatedUuid) ?: return
                val seatedId = seated.entityId
                val current = wrapper.passengers
                if (current.none { it == seatedId }) return
                wrapper.passengers = current.filter { it != seatedId }.toIntArray()
            }
        }
        try {
            PacketEvents.getAPI().eventManager.registerListener(listener)
            passengerListener = listener
        } catch (_: Throwable) {}
    }

    // Region protection (WorldGuard) cancels EntityMountEvent inside protected regions, which
    // blocked the control mount outright: the player never seated, no steer packets were sent
    // (WASD dead), and the tick re-seat retried forever — spamming "you can't ride this".
    // The stand is our own plugin-driven, per-player entity, so protection must never veto it.
    // Registered WITHOUT ignoreCancelled so we see (and undo) the cancellation.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMount(event: EntityMountEvent) {
        if (!event.isCancelled) return
        val stand = DialogueMountControl.heldStand(event.entity.uniqueId) ?: return
        if (event.mount == stand) event.isCancelled = false
    }

    // Layer 1: best-effort dismount veto.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDismount(event: EntityDismountEvent) {
        val stand = DialogueMountControl.heldStand(event.entity.uniqueId) ?: return
        if (event.dismounted == stand) event.isCancelled = true
    }

    // Layer 3: pin position during the one-tick window where a player has slipped off the mount.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val held = DialogueMountControl.heldStand(player.uniqueId) ?: return
        if (player.vehicle == held) return // properly seated — the pinned, gravity-less mount holds them
        val anchor = DialogueMountControl.anchorFor(player.uniqueId) ?: return
        val to = event.to
        if (to.x == anchor.x && to.y == anchor.y && to.z == anchor.z) return
        // Keep look free, freeze position back onto the anchor until the tick re-seat re-mounts them.
        event.to = anchor.clone().apply { yaw = to.yaw; pitch = to.pitch }
    }

    // Exit: logged out.
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        DialogueMountControl.forceRemove(event.player.uniqueId)
    }

    // Exit: took damage (got attacked) — don't trap the player in combat.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (DialogueMountControl.heldStand(player.uniqueId) == null) return
        DialogueMountControl.forceRemove(player.uniqueId)
    }

    // Exit: warped away (world change or a long teleport — walking is locked, so any real distance is a warp).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val held = DialogueMountControl.heldStand(event.player.uniqueId) ?: return
        val to = event.to
        if (to.world != held.world || to.distanceSquared(held.location) > WARP_DISTANCE_SQ) {
            DialogueMountControl.forceRemove(event.player.uniqueId)
        }
    }

    private companion object {
        const val WARP_DISTANCE_SQ = 6.0 * 6.0
    }
}
