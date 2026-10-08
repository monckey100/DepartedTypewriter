package com.typewritermc.basic.entries.cinematic

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.typewritermc.basic.entries.cinematic.DisplayCameraAction.Companion.BASE_INTERPOLATION
import com.typewritermc.basic.entries.variables.PlayerPositionOverride
import com.typewritermc.basic.itemcore.InventoryMask
import com.typewritermc.basic.itemcore.ItemCoreBridge
import com.typewritermc.basic.itemcore.MaskHide
import com.typewritermc.basic.itemcore.blockWorldInteraction
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.*
import com.typewritermc.core.interaction.*
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.temporal.SimpleCinematicAction
import com.typewritermc.engine.paper.extensions.packetevents.meta
import com.typewritermc.engine.paper.extensions.packetevents.spectateEntity
import com.typewritermc.engine.paper.extensions.packetevents.stopSpectatingEntity
import com.typewritermc.engine.paper.interaction.*
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.*
import com.typewritermc.engine.paper.utils.GenericPlayerStateProvider.*
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import lirand.api.extensions.events.SimpleListener
import lirand.api.extensions.events.listen
import lirand.api.extensions.events.unregister
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffect.INFINITE_DURATION
import org.bukkit.potion.PotionEffectType.INVISIBILITY
import org.geysermc.geyser.api.connection.GeyserConnection
import java.util.*
import kotlin.math.abs

@Entry("camera_cinematic", "Create a cinematic camera path", Colors.CYAN, "fa6-solid:video")
/**
 * The `Camera Cinematic` entry is used to create a cinematic camera path.
 *
 * Durations for path points calculated based on the total duration of each segment and specified path point's duration.
 * Suppose you have a segment with a duration of 300 ticks, and it has 3 path points.
 * Then we specify the duration on the second path point as 200 ticks.
 * The resulting durations between path points are as follows:
 *
 * | From | To | Duration |
 * |------|----|----------|
 * | 1    | 2  | 100      |
 * | 2    | 3  | 200      |
 *
 * ::: warning
 * Since the duration of a path point is the duration from that point to the next point,
 * the last path point will always have a duration of `0`.
 * Regardless of the duration specified on the last path point.
 * :::
 *
 * ## How could this be used?
 * When you want to direct the player's attention to a specific object/location.
 * Or when you want to show off a build.
 */
class CameraCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    @Segments(icon = "fa6-solid:video")
    @InnerMin(Min(10))
    val segments: List<CameraSegment> = emptyList(),
    val advancedCameraSettings: AdvancedCameraSettings = AdvancedCameraSettings(),
) : PrimaryCinematicEntry {
    override fun create(player: Player): CinematicAction {
        return CameraCinematicAction(
            player,
            this,
            player.interactionContext ?: context()
        )
    }

    override fun createSimulating(player: Player): CinematicAction {
        return SimulatedCameraCinematicAction(
            player,
            this,
        )
    }

}

data class CameraSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    val path: List<PathPoint> = emptyList(),
) : Segment

/**
 * Advanced settings for camera cinematics.
 *
 * @property restoreVelocity When true, restores the player's velocity to its pre-cinematic value
 *                           after the cinematic finishes.
 */
data class AdvancedCameraSettings(
    val restoreVelocity: Boolean = false
)

data class PathPoint(
    @WithRotation
    val location: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("The duration of the path point in frames.")
    /**
     * The duration of the path point in frames.
     * If not set,
     * the duration will be calculated based on the total duration and the number of path points
     * that don't have a duration.
     */
    val duration: Optional<Int> = Optional.empty(),
)

class CameraCinematicAction(
    private val player: Player,
    private val entry: CameraCinematicEntry,
    context: InteractionContext,
) : CinematicAction, ContextModifier(context) {
    private var previousSegment: CameraSegment? = null
    private lateinit var action: CameraAction

    private var originalState: PlayerState? = null
    private var interceptor: InterceptionBundle? = null
    private var listener: Listener? = null
    private var boundStateSubscription: InteractionBoundStateOverrideSubscription? = null

    // With DepartedItemCore the inventory is hidden by a core VIEW lease instead of Typewriter's packet rewrite.
    private var inventoryMask: InventoryMask? = null

    // Exactly who we hid, so teardown can un-hide exactly those. Do NOT derive this from a
    // VISIBLE_PLAYERS/SHOWING_PLAYER snapshot: when a cinematic and a lock bound are both active
    // (every dialogue), whichever starts second snapshots a world where the first already hid
    // everyone, so its snapshot is empty and its restore un-hides nobody. The hides then survive
    // until relog. Same class of bug as the flight snapshot pollution handled in teardown below.
    private val hiddenPlayers = mutableSetOf<UUID>()

    private var lastFrame: Int = 0

    // The UUID of the world the camera is currently rendering in. A camera cinematic teleports the
    // player across worlds to render the camera, which fires a PlayerChangedWorldEvent. We track the
    // expected world so the safety-net listener below can tell our OWN cross-world teleport apart
    // from an external move (e.g. MythicDungeons ending the dungeon) that should abort the cinematic.
    private var cameraWorldUid: UUID? = null

    override suspend fun setup() {
        action = if (player.isFloodgate) {
            val geyserConnection = player.geyserConnection
            if (geyserConnection != null) {
                BedrockCameraAction(player, geyserConnection)
            } else {
                TeleportCameraAction(player)
            }
        } else {
            DisplayCameraAction(player)
        }
        super.setup()
    }

    override suspend fun tick(frame: Int) {
        super.tick(frame)

        val segment = (entry.segments activeSegmentAt frame)

        if (segment != previousSegment) {
            // Record the world this segment's camera lives in BEFORE the action teleports the
            // player there, so the PlayerChangedWorldEvent safety net can recognise our own move.
            if (segment != null) {
                cameraWorldUid = segment.path.firstOrNull()?.location?.get(player)?.world
                    ?.let { runCatching { UUID.fromString(it.identifier) }.getOrNull() }
            }
            if (previousSegment == null && segment != null) {
                player.setup()
                action.startSegment(segment)
            } else if (segment != null) {
                action.switchSegment(segment)
            } else {
                action.stop()
                player.teardown()
            }

            previousSegment = segment
        }

        if (segment != null) {
            val baseFrame = frame - segment.startFrame
            if (abs(frame - lastFrame) > 5 && baseFrame > 0) {
                action.skipToFrame(baseFrame)
            } else {
                action.tickSegment(baseFrame)
            }
        }
        lastFrame = frame
    }

    private suspend fun Player.setup() {
        // Because we are teleporting the player away,
        // we don't want to quit the temporal interaction because we went out of bounds.
        if (boundStateSubscription == null) {
            boundStateSubscription = overrideBoundState(InteractionBoundState.IGNORING, priority = Int.MAX_VALUE)
        }

        if (originalState == null) {
            originalState = state(
                listOfNotNull(
                    LOCATION,
                    ALLOW_FLIGHT,
                    FLYING,
                    EffectStateProvider(INVISIBILITY),
                    VELOCITY.takeIf { entry.advancedCameraSettings.restoreVelocity }
                )
            )
            context[PlayerPositionOverride] = position
        }

        Dispatchers.Sync.switchContext {
            allowFlight = true
            isFlying = true
            addPotionEffect(PotionEffect(INVISIBILITY, INFINITE_DURATION, 0, false, false))

            server.onlinePlayers.filter { it.uniqueId != uniqueId }.forEach {
                it.hidePlayer(plugin, this@setup)
                this@setup.hidePlayer(plugin, it)
                hiddenPlayers += it.uniqueId
            }

            // In creative mode, when the player opens the inventory while their inventory is fake cleared,
            // The actual inventory will be cleared.
            // To prevent this, we only fake clear the inventory when the player is not in creative mode.
            if (gameMode != GameMode.CREATIVE && !isFloodgate) {
                if (!ItemCoreBridge.active) {
                    fakeClearInventory()
                } else if (inventoryMask == null) {
                    inventoryMask = ItemCoreBridge.mask(this@setup, MaskHide.ALL)
                }
            }
        }

        listener = SimpleListener()
        plugin.listen<EntityDamageEvent>(listener = listener!!) {
            if (it.entity.uniqueId != player?.uniqueId) return@listen
            it.isCancelled = true
        }
        plugin.listen<EntityDamageByEntityEvent>(listener = listener!!) {
            if (it.entity.uniqueId != player?.uniqueId) return@listen
            it.isCancelled = true
        }
        plugin.listen<EntityTargetEvent>(listener = listener!!) {
            if (it.target?.uniqueId != player?.uniqueId) return@listen
            it.isCancelled = true
        }
        plugin.listen<EntityTargetLivingEntityEvent>(listener = listener!!) {
            if (it.target?.uniqueId != player?.uniqueId) return@listen
            it.isCancelled = true
        }
        // Safety net: a normal camera cinematic only fakes the player's position via packets,
        // so it never triggers a real world change. If the player IS moved to another world
        // (e.g. MythicDungeons ending the dungeon mid-cinematic) or quits, the cinematic — which
        // ignores bound-state changes — would otherwise keep them invisible and flying forever.
        plugin.listen<PlayerChangedWorldEvent>(listener = listener!!) {
            if (it.player.uniqueId != player?.uniqueId) return@listen
            // The camera cinematic teleports the player across worlds to render the camera, which
            // fires this event. Ignore changes INTO the camera's own world (our own teleport); only
            // a change to a DIFFERENT world is an external move (e.g. MythicDungeons) that must end it.
            if (cameraWorldUid != null && it.player.world.uid == cameraWorldUid) return@listen
            player?.emergencyEndCinematic()
        }
        plugin.listen<PlayerQuitEvent>(listener = listener!!) {
            if (it.player.uniqueId != player?.uniqueId) return@listen
            player?.emergencyEndCinematic()
        }
        // A player joining mid-cinematic missed the hide loop in setup. Hide them too (so they don't
        // pop into the shot) and record them, so teardown un-hides them like everyone else.
        plugin.listen<PlayerJoinEvent>(listener = listener!!) {
            val self = player ?: return@listen
            val joiner = it.player
            if (joiner.uniqueId == self.uniqueId) return@listen
            joiner.hidePlayer(plugin, self)
            self.hidePlayer(plugin, joiner)
            hiddenPlayers += joiner.uniqueId
        }
        interceptor = this.interceptPackets {
            // If the player is a bedrock player, we don't want to modify the location.
            if (isFloodgate) return@interceptPackets
            if (ItemCoreBridge.active) blockWorldInteraction() else keepFakeInventory()
            PacketType.Play.Server.PLAYER_POSITION_AND_LOOK { event ->
                val packet = WrapperPlayServerPlayerPositionAndLook(event)
                packet.y += 500
            }
            PacketType.Play.Client.PLAYER_POSITION { event ->
                val packet = WrapperPlayClientPlayerPosition(event)
                packet.position = packet.position.withY(packet.position.y - 500)
            }
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION { event ->
                val packet = WrapperPlayClientPlayerPositionAndRotation(event)
                packet.position = packet.position.withY(packet.position.y - 500)
            }
            // We want to fake the player's location on the client because otherwise they will interact with
            // themselves crash kicking themselves off the server.
            PacketType.Play.Client.INTERACT_ENTITY { event ->
                val packet = WrapperPlayClientInteractEntity(event)
                // Don't allow the player to interact with themselves.
                if (this@setup.entityId != packet.entityId) return@INTERACT_ENTITY
                event.isCancelled = true
            }

        }
    }

    /**
     * Cleans up the cinematic-applied state in place when the player is pulled out of the
     * cinematic by an external force (world change / quit). Unlike [teardown] it does NOT
     * restore the original location — the player was intentionally moved elsewhere, so
     * teleporting them back (into a now-ending dungeon) would be wrong. Clearing
     * [originalState] also prevents the eventual [teardown] from doing that restore.
     */
    /**
     * Lifts every hide this cinematic applied, in both directions. Paper keys hidden entities per
     * plugin, so this only ever undoes Typewriter's own hides — a vanish plugin's hide survives.
     */
    private fun Player.showHiddenPlayers() {
        hiddenPlayers.forEach { uuid ->
            val other = server.getPlayer(uuid) ?: return@forEach
            other.showPlayer(plugin, this)
            this.showPlayer(plugin, other)
        }
        hiddenPlayers.clear()
    }

    private fun Player.emergencyEndCinematic() {
        if (originalState == null) return

        removePotionEffect(INVISIBILITY)
        isFlying = false
        allowFlight = gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR
        showHiddenPlayers()

        interceptor?.cancel()
        interceptor = null
        originalState = null
        inventoryMask?.release()
        inventoryMask = null

        // End the interaction so the normal teardown lifecycle runs (now a no-op for state).
        interruptInteraction()
    }

    private suspend fun Player.teardown() {
        listener?.unregister()
        listener = null

        Dispatchers.Sync.switchContext {
            // Un-hide before anything that can fail: if the hides outlive this block,
            // the player stays invisible to everyone until each observer relogs.
            showHiddenPlayers()

            interceptor?.cancel()
            interceptor = null

            originalState?.let {
                restore(it)
            }
            originalState = null

            // Do not trust the snapshot for invisibility either. When cinematics chain, this
            // cinematic's snapshot can hold the previous cinematic's infinite invisibility, and
            // restore() above faithfully re-applies it — leaving the player permanently invisible
            // (DepartedPlayerModels also hides the whole player model while the potion is active).
            // Infinite duration is the camera's own signature; a class ability's is finite.
            val leftoverInvisibility = getPotionEffect(INVISIBILITY)
            if (leftoverInvisibility != null && leftoverInvisibility.duration < 0) {
                removePotionEffect(INVISIBILITY)
            }

            // Do not trust the snapshot for flight. When one cinematic triggers another (the tutorial
            // outro chains wormhole -> lumbridge), the next cinematic can capture its "original" state
            // while the previous one still has camera flight forced on — and restore() would then
            // faithfully hand flight back to a survival player. Flight is only ever legitimate in
            // creative/spectator, which is the same invariant emergencyEndCinematic already applies.
            if (gameMode != GameMode.CREATIVE && gameMode != GameMode.SPECTATOR) {
                isFlying = false
                allowFlight = false
            }

            val mask = inventoryMask
            inventoryMask = null
            if (mask != null) {
                mask.release()
            } else if (gameMode != GameMode.CREATIVE && !isFloodgate && !ItemCoreBridge.active) {
                restoreInventory()
            }
        }

        context.clear(PlayerPositionOverride)

        // Do this after restoring the player's state.
        // To make sure the player is where they were before the bound got overridden.
        boundStateSubscription?.cancel()
        boundStateSubscription = null
    }

    override suspend fun teardown() {
        super.teardown()
        action.stop()
        player.teardown()
    }

    override fun canFinish(frame: Int): Boolean = entry.segments canFinishAt frame
}

// The max distance the entity can be from the player before it gets teleported.
private const val MAX_DISTANCE_SQUARED = 25 * 25

/**
 * Teleports the player to the given location if needed.
 * To render chunks correctly, we need to teleport the player to the entity.
 * Though to prevent lag, we only do this every 10 frames or when the player is too far away.
 *
 * @param frame The current frame.
 * @param location The location to teleport to.
 */
private suspend inline fun Player.teleportIfNeeded(
    frame: Int,
    location: Location,
) {
    if (frame % 10 == 0 || (location.distanceSqrt(location)
            ?: Double.MAX_VALUE) > MAX_DISTANCE_SQUARED
    )
        teleportAsync(location).await()
}

private data class PointSegment(
    override val startFrame: Int,
    override val endFrame: Int,
    val position: Position,
) : Segment

private interface CameraAction {
    suspend fun startSegment(segment: CameraSegment)
    suspend fun tickSegment(frame: Int)
    suspend fun skipToFrame(frame: Int)
    suspend fun switchSegment(newSegment: CameraSegment)
    suspend fun stop()
}

private class DisplayCameraAction(
    val player: Player,
) : CameraAction {
    private var isActive: Boolean = false
    private var entity = createEntity()
    private var path = emptyList<PointSegment>()

    companion object {
        const val BASE_INTERPOLATION = 10
    }

    private fun createEntity(): WrapperEntity {
        return WrapperEntity(EntityTypes.TEXT_DISPLAY)
            .meta<TextDisplayMeta> {
                positionRotationInterpolationDuration = BASE_INTERPOLATION
            }
    }

    private fun setupPath(segment: CameraSegment) {
        path = segment.path.transform(player, segment.duration - BASE_INTERPOLATION) {
            // We want this to be static as we assume the user was the correct height when capturing.
            val playerDefaultEyeHeight = 1.6
            it.add(y = playerDefaultEyeHeight)
        }
    }

    override suspend fun startSegment(segment: CameraSegment) {
        if (isActive) return
        isActive = true
        setupPath(segment)

        player.teleportAsync(path.first().position.toBukkitLocation()).await()
        player.allowFlight = true
        player.isFlying = true

        entity.spawn(path.first().position.toPacketLocation())
        entity.addViewer(player.uniqueId)
        player.spectateEntity(entity)
    }

    override suspend fun tickSegment(frame: Int) {
        val location = path.interpolate(frame)
        entity.teleport(location.toPacketLocation())
        player.teleportIfNeeded(frame, location.toBukkitLocation())
    }

    override suspend fun skipToFrame(frame: Int) {
        val location = path.interpolate(frame)
        switchSeamless(location)
    }

    override suspend fun switchSegment(newSegment: CameraSegment) {
        val oldWorld = path.first().position.world.identifier
        val newWorld = newSegment.path.first().location.get(player).world.identifier

        setupPath(newSegment)
        if (oldWorld == newWorld) {
            switchSeamless()
        } else {
            switchWithStop()
        }
    }

    private suspend fun switchSeamless(position: Position = path.first().position) {
        val newEntity = createEntity()
        newEntity.spawn(position.toPacketLocation())
        newEntity.addViewer(player.uniqueId)

        player.teleportAsync(path.first().position.toBukkitLocation()).await()
        player.spectateEntity(newEntity)

        entity.despawn()
        entity.remove()
        entity = newEntity
    }

    private suspend fun switchWithStop() {
        player.stopSpectatingEntity()
        entity.despawn()
        player.teleportAsync(path.first().position.toBukkitLocation()).await()
        entity.spawn(path.first().position.toPacketLocation())
        player.spectateEntity(entity)
    }

    override suspend fun stop() {
        if (!isActive) return
        isActive = false
        player.stopSpectatingEntity()
        entity.despawn()
        entity.remove()
    }
}

private class TeleportCameraAction(
    private val player: Player,
) : CameraAction {
    private var path = emptyList<PointSegment>()

    override suspend fun startSegment(segment: CameraSegment) {
        path = segment.path.transform(player, segment.duration, Position::copy)
    }

    override suspend fun tickSegment(frame: Int) {
        val position = path.interpolate(frame)
        Dispatchers.Sync.switchContext {
            player.teleport(position.toBukkitLocation())
            player.allowFlight = true
            player.isFlying = true
        }
    }

    override suspend fun skipToFrame(frame: Int) {
        tickSegment(frame)
    }

    override suspend fun switchSegment(newSegment: CameraSegment) {
        path = newSegment.path.transform(player, newSegment.duration, Position::copy)
    }

    override suspend fun stop() {
    }
}

private class BedrockCameraAction(
    private val player: Player,
    private val geyserConnection: GeyserConnection,
) : CameraAction {
    private val cameraLockId = UUID.randomUUID()
    private var path = emptyList<PointSegment>()

    private fun setupPath(segment: CameraSegment) {
        path = segment.path.transform(player, segment.duration - BASE_INTERPOLATION) {
            // We want this to be static as we assume the user was the correct height when capturing.
            val playerDefaultEyeHeight = 1.6
            it.add(y = playerDefaultEyeHeight)
        }
    }

    override suspend fun startSegment(segment: CameraSegment) {
        setupPath(segment)
        val position = path.first().position
        player.teleportAsync(position.toBukkitLocation()).await()
        geyserConnection.setupCamera(cameraLockId)
        geyserConnection.forceCameraPosition(position)
    }

    override suspend fun tickSegment(frame: Int) {
        val position = path.interpolate(frame)
        geyserConnection.interpolateCameraPosition(position)
    }

    override suspend fun skipToFrame(frame: Int) {
        geyserConnection.forceCameraPosition(path.interpolate(frame))
    }

    override suspend fun switchSegment(newSegment: CameraSegment) {
        val oldPosition = path.last().position
        setupPath(newSegment)
        val position = path.first().position
        if (oldPosition.world != position.world) {
            player.teleportAsync(position.toBukkitLocation()).await()
        }
        geyserConnection.forceCameraPosition(position)
    }

    override suspend fun stop() {
        geyserConnection.resetCamera(cameraLockId)
    }
}

class SimulatedCameraCinematicAction(
    private val player: Player,
    private val entry: CameraCinematicEntry,
) : SimpleCinematicAction<CameraSegment>() {
    override val segments: List<CameraSegment> = entry.segments

    private val paths = entry.segments.associateWith { segment ->
        segment.path.transform(player, segment.duration) {
            it.add(y = player.eyeHeight)
        }
    }

    private var entity: WrapperEntity? = null

    override suspend fun startSegment(segment: CameraSegment) {
        super.startSegment(segment)
        entity?.despawn()
        entity?.remove()
        entity = WrapperEntity(EntityTypes.ITEM_DISPLAY)
            .meta<ItemDisplayMeta> {
                positionRotationInterpolationDuration = 3
                displayType = ItemDisplayMeta.DisplayType.HEAD
                item = SpigotConversionUtil.fromBukkitItemStack(
                    ItemStack(Material.PLAYER_HEAD)
                        .apply {
                            editMeta(SkullMeta::class.java) { meta ->
                                meta.applySkinUrl("https://textures.minecraft.net/texture/427066e899358b1185460f867fc6dc434c7b4c82fbe70e1919ce74b8bacf80a1")
                            }
                        }
                )
            }
        val path = paths[segment] ?: return
        val location = path.interpolate(lastFrame - segment.startFrame)
        entity?.spawn(location.toPacketLocation().apply { yaw += 180; pitch = -pitch })
        entity?.addViewer(player.uniqueId)
    }

    override suspend fun tickSegment(segment: CameraSegment, frame: Int) {
        super.tickSegment(segment, frame)
        val path = paths[segment] ?: return
        val location = path.interpolate(frame - segment.startFrame)
        entity?.teleport(location.toPacketLocation().apply { yaw += 180; pitch = -pitch })
    }

    override suspend fun stopSegment(segment: CameraSegment) {
        super.stopSegment(segment)
        entity?.despawn()
        entity?.remove()
        entity = null
    }

    override fun canFinish(frame: Int): Boolean = entry.segments canFinishAt frame
}

private fun List<PathPoint>.transform(
    player: Player,
    totalDuration: Int,
    locationTransformer: (Position) -> Position
): List<PointSegment> {
    if (isEmpty()) {
        throw IllegalArgumentException("The path points cannot be empty.")
    }

    if (size == 1) {
        val pathPoint = first()
        val location = pathPoint.location.get(player).run(locationTransformer)
        return listOf(PointSegment(0, totalDuration, location))
    }


    val allocatedDuration = sumOf { it.duration.orElse(0) }
    if (allocatedDuration > totalDuration) {
        throw IllegalArgumentException("The total duration of the path points is greater than the total duration of the cinematic.")
    }

    val remainingDuration = totalDuration - allocatedDuration

    // The last segment should never have a duration. As the last segment will be reached when the cinematic ends.
    val leftSegments = take(size - 1).count { it.duration.isEmpty }

    if (leftSegments == 0) {
        if (remainingDuration > 0) {
            logger.warning("The sum duration of the path points is less than the total duration of the cinematic. The remaining duration will be still frames.")
        }

        var currentFrame = 0
        return map {
            val endFrame = currentFrame + it.duration.orElse(0)
            val segment = PointSegment(
                currentFrame,
                endFrame,
                it.location.get(player).run(locationTransformer)
            )
            currentFrame = endFrame
            segment
        }
    }

    val durationPerSegment = remainingDuration / leftSegments
    var leftOverDuration = remainingDuration % leftSegments

    var currentFrame = 0

    return map { pathPoint ->
        val duration = pathPoint.duration.orElseGet {
            if (leftOverDuration > 0) {
                leftOverDuration--
                durationPerSegment + 1
            } else {
                durationPerSegment
            }
        }
        val endFrame = currentFrame + duration
        val segment = PointSegment(
            currentFrame,
            endFrame,
            pathPoint.location.get(player).run(locationTransformer)
        )
        currentFrame = endFrame
        segment
    }
}

/**
 * Use catmull-rom interpolation to get a point between a list of points.
 */
private fun List<PointSegment>.interpolate(frame: Int): Position {
    val index = indexOfFirst { it isActiveAt frame }
    if (index == -1) {
        return last().position
    }

    val segment = this[index]
    val totalFrames = segment.endFrame - segment.startFrame
    val currentFrame = frame - segment.startFrame
    val percentage = currentFrame.toDouble() / totalFrames

    val currentLocation = segment.position
    val previousLocation = getOrNull(index - 1)?.position ?: currentLocation
    val nextLocation = getOrNull(index + 1)?.position ?: currentLocation
    val nextNextLocation = getOrNull(index + 2)?.position ?: nextLocation

    return interpolatePoints(previousLocation, currentLocation, nextLocation, nextNextLocation, percentage)
}