package gg.departed.basic.entries.zone

import com.github.retrooper.packetevents.protocol.particle.Particle as PacketParticle
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.toBukkitLocation
import com.typewritermc.engine.paper.utils.toPacketVector3d
import com.typewritermc.engine.paper.utils.toPacketVector3i
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player

/**
 * Which hand(s) the player must hold the required item in. Mirrors the Basic extension so the
 * Departed extension stays self-contained (it does not depend on BasicExtension).
 */
enum class HoldingHand(val main: Boolean, val off: Boolean) {
    BOTH(true, true),
    MAIN(true, false),
    OFF(false, true),
}

/**
 * Whether the player is holding [item] in the configured [hand]. An empty [Item] matches any hand,
 * so leaving the tool unset means "no requirement".
 */
fun hasItemInHand(player: Player, hand: HoldingHand, item: Item, context: InteractionContext?): Boolean {
    if (hand.main && item.isSameAs(player, player.inventory.itemInMainHand, context)) return true
    if (hand.off && item.isSameAs(player, player.inventory.itemInOffHand, context)) return true
    return false
}

/** Sends a client-only block change to a single player (does not modify the world). */
fun Player.sendFakeBlock(position: Position, blockData: BlockData) {
    if (!isInPositionWorld(position)) return
    WrapperPlayServerBlockChange(
        position.toPacketVector3i(),
        SpigotConversionUtil.fromBukkitBlockData(blockData),
    ) sendPacketTo this
}

/** Re-sends the real world block at [position] to a single player, clearing any fake block there. */
fun Player.restoreRealBlock(position: Position) {
    if (!isInPositionWorld(position)) return
    sendFakeBlock(position, position.toBukkitLocation().block.blockData)
}

/** Spawns a small particle burst at the center of [position] for a single player. */
fun Player.spawnZoneParticle(position: Position, particle: Particle) {
    if (!isInPositionWorld(position)) return
    WrapperPlayServerParticle(
        PacketParticle(SpigotConversionUtil.fromBukkitParticle(particle)),
        true,
        position.center().toPacketVector3d(),
        Vector3f(0.2f, 0.2f, 0.2f),
        0.02f,
        10,
        true,
    ) sendPacketTo this
}

private fun Player.isInPositionWorld(position: Position): Boolean =
    position.world.identifier == world.uid.toString()
