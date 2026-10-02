package gg.departed.basic.entries.zone

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MaterialProperties
import com.typewritermc.core.extension.annotations.MaterialProperty
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.toBukkitLocation
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

enum class FakeZoneRemoval {
    // Everything disappears at once with a particle burst.
    INSTANT_PARTICLES,

    // Blocks disappear layer by layer from the top down, like a gate lifting away.
    GATE_TOP_TO_BOTTOM,
}

@Entry("fake_block_zone", "A packet-only barrier/gate zone for an audience", Colors.CYAN, "mdi:wall")
/**
 * The `Fake Block Zone` is an audience entry that fills a cube with client-only (packet) blocks for
 * every player in the audience, without touching the real world. It is used to wall off areas until
 * a player is allowed to progress. When the player leaves the audience (e.g. a gating fact flips) the
 * blocks are removed with the configured animation and the real world blocks are restored to them.
 *
 * ## How could this be used?
 * A door blocks the start area until the player tries cooking; once they do, the fake door instantly
 * pops away (revealing the real open door behind it). A later area is walled by a gate that drops away
 * from the top down.
 */
class FakeBlockZoneAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("One corner of the zone cube.")
    val corner1: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("The opposite corner of the zone cube.")
    val corner2: Var<Position> = ConstVar(Position.ORIGIN),
    @MaterialProperties(MaterialProperty.BLOCK)
    @Help("The fake block shown to players in the zone.")
    val block: Var<Material> = ConstVar(Material.BARRIER),
    @Help("Optional exact block-data string (e.g. minecraft:iron_bars[north=true,south=true]). Overrides the block above when set, so connected blocks like bars/fences render correctly.")
    val blockData: String = "",
    @MaterialProperties(MaterialProperty.BLOCK)
    @Help("Only replace real blocks of this type (e.g. AIR). Leave empty to replace every block in the cube.")
    val mask: Optional<Material> = Optional.empty(),
    @Help("How the zone is removed when a player leaves the audience.")
    val removalStyle: FakeZoneRemoval = FakeZoneRemoval.INSTANT_PARTICLES,
    @Help("The particle shown at each block as it is removed.")
    val removalParticle: Var<Particle> = ConstVar(Particle.CLOUD),
    @Help("Ticks between each layer when using the top-to-bottom gate removal.")
    val gateDelayTicks: Var<Int> = ConstVar(2),
) : AudienceEntry {
    override suspend fun display(): AudienceDisplay = FakeBlockZoneDisplay(this)
}

class FakeBlockZoneDisplay(
    private val entry: FakeBlockZoneAudienceEntry,
) : AudienceDisplay(), TickableDisplay {
    // The exact positions faked for each player, plus the block data used, so we can resend/restore.
    private val fakedPositions = ConcurrentHashMap<UUID, List<Position>>()
    private val fakeBlockData = ConcurrentHashMap<UUID, BlockData>()
    private var tickCounter = 0

    override fun onPlayerAdd(player: Player) {
        val context = player.interactionContext
        val cuboid = BlockCuboid.of(entry.corner1.get(player, context), entry.corner2.get(player, context))
        val data = entry.resolveBlockData(player, context)
        val maskMaterial = entry.mask.orElse(null)

        val positions = cuboid.blockPositions().filter { position ->
            if (maskMaterial == null) return@filter true
            val real = position.toBukkitLocation().block.type
            // An AIR mask matches any air variant (CAVE_AIR/VOID_AIR are common inside builds).
            real == maskMaterial || (maskMaterial.isAir && real.isAir)
        }
        fakedPositions[player.uniqueId] = positions
        fakeBlockData[player.uniqueId] = data
        // Let the click relay know these blocks exist for this player, so hitting one reads as an
        // interaction instead of a swing at thin air.
        FakeBlockTracker.track(player.uniqueId, positions)

        positions.forEach { player.sendFakeBlock(it, data) }
    }

    override fun tick() {
        // Periodically resend so chunk reloads / client desync don't reveal the real blocks.
        tickCounter++
        if (tickCounter < RESEND_INTERVAL_TICKS) return
        tickCounter = 0
        fakedPositions.forEach { (uuid, positions) ->
            val player = server.getPlayer(uuid) ?: return@forEach
            val data = fakeBlockData[uuid] ?: return@forEach
            positions.forEach { player.sendFakeBlock(it, data) }
        }
    }

    @EventHandler
    fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        // A boundary chunk can (re)load for the client after our initial send, wiping the fake
        // blocks in it (e.g. a zone straddling a chunk edge). Re-push this chunk's blocks at once.
        val positions = fakedPositions[event.player.uniqueId] ?: return
        val data = fakeBlockData[event.player.uniqueId] ?: return
        val cx = event.chunk.x
        val cz = event.chunk.z
        positions.forEach { pos ->
            if ((pos.blockX shr 4) == cx && (pos.blockZ shr 4) == cz) {
                event.player.sendFakeBlock(pos, data)
            }
        }
    }

    override fun onPlayerRemove(player: Player) {
        val positions = fakedPositions.remove(player.uniqueId) ?: return
        fakeBlockData.remove(player.uniqueId)
        // Stop relaying clicks here immediately. The gate removal below can take several ticks, and
        // during it the blocks are already gone as far as the quest is concerned.
        FakeBlockTracker.untrack(player.uniqueId, positions)
        val context = player.interactionContext
        val particle = entry.removalParticle.get(player, context)

        when (entry.removalStyle) {
            FakeZoneRemoval.INSTANT_PARTICLES -> positions.forEach { position ->
                player.restoreRealBlock(position)
                player.spawnZoneParticle(position, particle)
            }

            FakeZoneRemoval.GATE_TOP_TO_BOTTOM -> {
                val delay = max(1, entry.gateDelayTicks.get(player, context)).toLong()
                val layers = positions.groupBy { it.blockY }
                    .toSortedMap(compareByDescending { it })
                layers.values.forEachIndexed { index, layer ->
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (!player.isOnline) return@Runnable
                        layer.forEach { position ->
                            player.restoreRealBlock(position)
                            player.spawnZoneParticle(position, particle)
                        }
                    }, delay * index)
                }
            }
        }
    }

    companion object {
        private const val RESEND_INTERVAL_TICKS = 4
    }
}

/** Resolves the fake block data: the exact [FakeBlockZoneAudienceEntry.blockData] string if set, otherwise the material's default. */
private fun FakeBlockZoneAudienceEntry.resolveBlockData(player: Player, context: InteractionContext?): BlockData {
    val raw = blockData.trim()
    if (raw.isNotEmpty()) {
        runCatching { return Bukkit.createBlockData(raw) }
    }
    return block.get(player, context).createBlockData()
}
