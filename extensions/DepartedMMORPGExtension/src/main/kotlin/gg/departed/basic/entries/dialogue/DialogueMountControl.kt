package gg.departed.basic.entries.dialogue

import com.typewritermc.engine.paper.snippets.snippet
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Vertical offset (blocks) of the invisible control mount above the player's feet. Tunable live. */
val dialogueMountOffsetY: Double by snippet(
    "dialogue.mount.offsetY",
    0.75,
    "Vertical offset (blocks) of the invisible WASD/spoken dialogue control mount above the player's feet. Raise if the player stands too low / clips into the ground, lower if they float."
)

/**
 * One invisible no-gravity control ArmorStand per player, SHARED across every dialogue messenger
 * (option and spoken) of a single conversation. It anchors the player so they can't walk off and
 * provides the vehicle that the WASD steer packets read.
 *
 * A conversation is a CHAIN of separate messenger instances (spoken -> option -> spoken ...). If
 * each instance spawned and removed its own mount, the player would dismount, fall, and remount on
 * every node — bobbing up and down — and would be completely free during option-less spoken lines.
 * Instead each node CLAIMS the existing mount (or creates it once) and teardown is DEFERRED so the
 * next node re-claims before it fires. Only the final node of the conversation truly dismounts.
 */
object DialogueMountControl {
    private const val TEARDOWN_DELAY_TICKS = 3L

    /** Scoreboard tag stamped on every control mount so orphans (crash/reload) can be swept by tag. */
    const val MOUNT_TAG = "departed_dialogue_mount"

    private val mounts = ConcurrentHashMap<UUID, ControlMount>()

    private class ControlMount(
        @JvmField val stand: ArmorStand,
        @JvmField val anchor: Location,
    ) {
        // Bumped on every claim and every release. A scheduled teardown captures the generation at
        // release time and only fires if it is still current — i.e. no later node re-claimed.
        @JvmField var generation: Long = 0
    }

    data class Claim(val stand: ArmorStand, val anchor: Location)

    /** Claim (or lazily create) the player's mount and seat them on it. MUST run on the main thread. */
    fun claim(player: Player): Claim {
        val handle = mounts[player.uniqueId]?.takeIf { it.stand.isValid } ?: run {
            // Anchor = the player's feet. If airborne when the dialogue opens, snap to the ground
            // below; otherwise their exact spot. Computed ONCE when the mount is created and frozen
            // for the whole conversation, so node-to-node transitions can't drift the height.
            val base = if (player.isOnGround) player.location.clone() else groundedLocation(player.location)
            val stand = spawnStand(player, base.clone().add(0.0, dialogueMountOffsetY, 0.0))
            ControlMount(stand, base.clone()).also { mounts[player.uniqueId] = it }
        }
        handle.generation++ // claim cancels any pending teardown from the previous node
        if (player.vehicle != handle.stand) handle.stand.addPassenger(player)
        return Claim(handle.stand, handle.anchor.clone())
    }

    /** Release the player's mount; deferred teardown unless another node re-claims first. Main thread. */
    fun release(player: Player) {
        val uuid = player.uniqueId
        val handle = mounts[uuid] ?: return
        val releaseGen = ++handle.generation
        Bukkit.getScheduler().runTaskLater(pluginHost(), Runnable {
            val current = mounts[uuid] ?: return@Runnable
            if (current !== handle || handle.generation != releaseGen) return@Runnable // reclaimed
            mounts.remove(uuid, handle)
            try { if (player.isOnline && player.vehicle == handle.stand) player.leaveVehicle() } catch (_: Throwable) {}
            try { if (!handle.stand.isDead) handle.stand.remove() } catch (_: Throwable) {}
            if (player.isOnline) player.fallDistance = 0f
        }, TEARDOWN_DELAY_TICKS)
    }

    /** The stand currently seating this player, if any. Used to veto the vanilla shift-dismount. */
    fun heldStand(player: Player): ArmorStand? = heldStand(player.uniqueId)

    fun heldStand(uuid: UUID): ArmorStand? = mounts[uuid]?.stand?.takeIf { it.isValid }

    /** The frozen standing anchor (feet on the ground) for a held player, if any. */
    fun anchorFor(uuid: UUID): Location? = mounts[uuid]?.anchor?.clone()

    /**
     * If [vehicleEntityId] is one of our control stands, the UUID of the player seated on it. Used
     * by the passenger-hiding packet listener so OTHER clients don't render the player sitting.
     */
    fun seatedPlayerOfStand(vehicleEntityId: Int): UUID? {
        for ((uuid, handle) in mounts) {
            if (handle.stand.entityId == vehicleEntityId) return uuid
        }
        return null
    }

    /**
     * Immediately tear down a player's mount (used when they log out, take damage, or warp away —
     * the cases where the lock must let go). Removes from the map FIRST so the re-seat loop, move
     * lock and dismount veto all stop matching, then dismounts and removes the stand. MAIN THREAD.
     */
    fun forceRemove(uuid: UUID) {
        val handle = mounts.remove(uuid) ?: return
        val player = Bukkit.getPlayer(uuid)
        try { if (player != null && player.vehicle == handle.stand) player.leaveVehicle() } catch (_: Throwable) {}
        try { if (!handle.stand.isDead) handle.stand.remove() } catch (_: Throwable) {}
        if (player != null && player.isOnline) player.fallDistance = 0f
    }

    /**
     * Remove EVERY dialogue mount — the ones we're tracking AND any orphan tagged stands left behind
     * by a crash, an exception, or an extension reload where our in-memory map was lost. Call on
     * extension startup (clears anything a previous unclean run left) and on shutdown. Idempotent.
     */
    fun purgeAll() {
        val work = Runnable {
            for (uuid in mounts.keys.toList()) forceRemove(uuid)
            mounts.clear()
            for (world in Bukkit.getWorlds()) {
                // toList: removing entities while iterating the live view can CME
                for (entity in world.entities.toList()) {
                    if (entity is ArmorStand && entity.scoreboardTags.contains(MOUNT_TAG)) {
                        try { entity.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }
        if (Bukkit.isPrimaryThread()) work.run() else Bukkit.getScheduler().runTask(pluginHost(), work)
    }

    /**
     * Runs every tick as the real movement lock. Cancelling the dismount event isn't reliable on
     * modern Paper (space/shift dismounts leak through), so we force any held player who has slipped
     * off their mount back onto it — a dismount survives at most one tick. If a player is no longer
     * near their mount (warped away / changed world), we let go instead of dragging them back. MAIN THREAD.
     */
    fun reseatSlippedPlayers() {
        if (mounts.isEmpty()) return
        for ((uuid, handle) in mounts) {
            if (!handle.stand.isValid) continue
            val player = Bukkit.getPlayer(uuid) ?: continue
            val standLoc = handle.stand.location
            if (player.world != standLoc.world ||
                player.location.distanceSquared(standLoc) > WARP_AWAY_DISTANCE_SQ
            ) {
                forceRemove(uuid) // warped away — release the lock
                continue
            }
            if (player.vehicle != handle.stand) handle.stand.addPassenger(player)
        }
    }

    private const val WARP_AWAY_DISTANCE_SQ = 6.0 * 6.0 // movement is locked, so >6 blocks = a warp

    private fun spawnStand(player: Player, loc: Location): ArmorStand =
        (player.world.spawnEntity(loc, EntityType.ARMOR_STAND) as ArmorStand).apply {
            isSilent = true
            isInvisible = true
            isInvulnerable = true
            setGravity(false)
            isMarker = true
            isCollidable = false
            isPersistent = false // never save to disk — must not survive a restart if teardown is missed
            addScoreboardTag(MOUNT_TAG) // identifiable so orphans can be swept even if our map is lost
            customName = null
            setBasePlate(false)
            setArms(false)
            setSmall(true)
        }

    /** Snaps [loc]'s Y down to the exact surface of the first block below (handles slabs/stairs). */
    fun groundedLocation(loc: Location): Location {
        val world = loc.world ?: return loc.clone()
        val hit = world.rayTraceBlocks(loc, Vector(0.0, -1.0, 0.0), 16.0, FluidCollisionMode.NEVER, true)
            ?.hitPosition ?: return loc.clone()
        return loc.clone().apply { y = hit.y }
    }

    fun pluginHost(): JavaPlugin {
        (Bukkit.getPluginManager().getPlugin("PacketEvents") as? JavaPlugin)?.let { return it }
        (Bukkit.getPluginManager().getPlugin("packetevents") as? JavaPlugin)?.let { return it }
        Bukkit.getPluginManager().plugins.firstOrNull { it is JavaPlugin }?.let { return it as JavaPlugin }
        throw IllegalStateException("No JavaPlugin found to schedule tasks")
    }
}
