package gg.departed.basic.entries.dialogue.hud

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3f
import com.typewritermc.engine.paper.extensions.packetevents.metas
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.toPacketLocation
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.meta.EntityMeta
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns the viewer-only skin carrier used by the dialogue resource-pack portrait shader. */
class DialogueNpcPortrait(private val player: Player) {
    @Volatile
    private var talking = false
    @Volatile
    private var disposed = false
    private var entity: WrapperEntity? = null
    private var updateTask: BukkitTask? = null
    private var slim = false
    private var backdrop = false
    private var lastMarker = Float.NaN
    private var worldId: UUID? = null
    @Volatile
    private var modelPortrait: DialogueModelPortrait? = null
    private var facingStep = facingStep(DialogueFacing.RIGHT)

    /** Null until [show] has run; then whether a portrait is visible (false = render the HUD without one). */
    @Volatile
    var shown: Boolean? = null
        private set

    /**
     * Resolve the existing NPC skin and show it turned [facingDegrees] (see [DialogueFacing]).
     * Call from the Bukkit main thread.
     */
    fun show(npcId: String?, facingDegrees: Double = DialogueFacing.RIGHT): Boolean {
        check(Bukkit.isPrimaryThread()) { "Dialogue portraits must be shown on the Bukkit main thread" }
        return resolveAndShow(npcId, facingDegrees).also { shown = it }
    }

    private fun resolveAndShow(npcId: String?, facingDegrees: Double): Boolean {
        if (disposed || !player.isOnline || npcId.isNullOrBlank()) return false
        modelPortrait?.dispose()
        modelPortrait = null
        facingStep = facingStep(facingDegrees)
        val profile = DialogueNpcProfile.resolve(player, npcId)
        if (profile != null) {
            backdrop = false
            return showCarrier(profile)
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return false
        val model = DialogueModelPortrait(player)
        if (!model.show(npcId, facingDegrees)) return false
        modelPortrait = model
        return showBackdrop()
    }

    /** Supply an opaque background behind a separately rendered ModelEngine portrait. Main thread only. */
    fun showBackdrop(): Boolean {
        check(Bukkit.isPrimaryThread()) { "Dialogue portraits must be shown on the Bukkit main thread" }
        if (disposed || !player.isOnline) return false
        backdrop = true
        return showCarrier(DialogueNpcProfile(ItemStack(Material.PLAYER_HEAD), false))
    }

    private fun showCarrier(profile: DialogueNpcProfile): Boolean {
        DialoguePortraitLifecycle.register(this, player.uniqueId, ::dispose)
        owners.put(player.uniqueId, this)?.takeIf { it !== this }?.dispose()
        removeEntity()
        slim = profile.slim
        worldId = player.world.uid
        entity = createEntity(profile)
        val location = carrierLocation()
        entity?.spawn(location.toPacketLocation())
        entity?.addViewer(player.uniqueId)
        updateTask?.cancel()
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update() }, 1L, 1L)
        return true
    }

    /** Update speech animation state; this method may be called from the dialogue tick thread. */
    fun tick(talking: Boolean) {
        this.talking = talking
        modelPortrait?.tick(talking)
    }

    /** Safe on either thread and when a newer messenger has already replaced this portrait. */
    fun dispose() {
        disposed = true
        owners.remove(player.uniqueId, this)
        if (Bukkit.isPrimaryThread()) {
            closeResources()
        } else if (plugin.isEnabled) {
            Bukkit.getScheduler().runTask(plugin, Runnable { closeResources() })
        }
    }

    private fun update() {
        if (disposed || !player.isOnline || player.world.uid != worldId || owners[player.uniqueId] !== this) {
            dispose()
            return
        }
        val carrier = entity ?: return
        carrier.teleport(carrierLocation().toPacketLocation())
        val marker = marker()
        if (marker == lastMarker) return
        carrier.metas {
            meta<AbstractDisplayMeta> { translation = Vector3f(0f, marker, 0f) }
        }
        lastMarker = marker
    }

    private fun carrierLocation() = player.eyeLocation.clone().apply {
        add(direction.multiply(2.0))
        yaw = 0f
        pitch = 0f
    }

    // Shader contract (departed_dialogue_portrait_*): state = pose (0..63) + 64 * facing step (0..16), 2047 = backdrop.
    private fun marker(): Float {
        if (backdrop) return -262144f - 256f * BACKDROP_STATE
        val phase = ((System.nanoTime() / 100_000_000L) % 16).toInt()
        val state = phase + (if (talking) 16 else 0) + (if (slim) 32 else 0) + 64 * facingStep
        return -262144f - 256f * state
    }

    private fun createEntity(profile: DialogueNpcProfile): WrapperEntity {
        val uuid = EntityLib.getPlatform().entityUuidProvider.provide(EntityTypes.ITEM_DISPLAY)
        val id = EntityLib.getPlatform().entityIdProvider.provide(uuid, EntityTypes.ITEM_DISPLAY)
        val carrier = WrapperEntity(id, uuid, EntityTypes.ITEM_DISPLAY, EntityMeta.createMeta(id, EntityTypes.ITEM_DISPLAY))
        carrier.entityMeta.setNotifyAboutChanges(false)
        carrier.metas {
            meta<ItemDisplayMeta> {
                item = SpigotConversionUtil.fromBukkitItemStack(profile.item)
                displayType = ItemDisplayMeta.DisplayType.FIXED
            }
            meta<AbstractDisplayMeta> {
                billboardConstraints = AbstractDisplayMeta.BillboardConstraints.FIXED
                translation = Vector3f(0f, marker(), 0f)
                scale = Vector3f(1f, 1f, 1f)
                width = 8f
                height = 8f
                viewRange = 8f
                shadowRadius = 0f
                shadowStrength = 0f
                brightnessOverride = 0x00F000F0
                transformationInterpolationDuration = 0
                positionRotationInterpolationDuration = 0
            }
        }
        lastMarker = marker()
        carrier.entityMeta.setNotifyAboutChanges(true)
        return carrier
    }

    private fun removeEntity() {
        updateTask?.cancel()
        updateTask = null
        entity?.let { carrier ->
            carrier.despawn()
            carrier.remove()
        }
        entity = null
    }

    private fun closeResources() {
        modelPortrait?.dispose()
        modelPortrait = null
        removeEntity()
        DialoguePortraitLifecycle.unregister(this)
    }

    private companion object {
        const val BACKDROP_STATE = 2047
        val owners = ConcurrentHashMap<UUID, DialogueNpcPortrait>()

        /** 11.25 degree steps; 8 looks at the viewer. */
        fun facingStep(degrees: Double): Int = (8 + Math.round(degrees / 11.25).toInt()).coerceIn(0, 16)
    }
}
