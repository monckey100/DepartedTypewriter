package com.typewritermc.basic.entries.cinematic

import com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Segments
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.PrimaryCinematicEntry
import com.typewritermc.engine.paper.entry.entries.Segment
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.temporal.SimpleCinematicAction
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.extensions.packetevents.toPacketItem
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.name
import com.typewritermc.engine.paper.utils.unClickable
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack

@Entry("pumpkin_hat_cinematic", "Show a pumpkin hat during a cinematic", Colors.CYAN, "mingcute:hat-fill")
/**
 * The `Pumpkin Hat Cinematic` is a cinematic that shows a pumpkin hat on the player's head.
 *
 * ## How could this be used?
 * When you have a resource pack, you can re-texture the pumpkin overlay to make it look like cinematic black bars.
 */
class PumpkinHatCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    val customItem: Optional<Var<Item>> = Optional.empty(),
    @Segments(icon = "mingcute:hat-fill")
    val segments: List<PumpkinHatSegment> = emptyList(),
) : PrimaryCinematicEntry {
    override fun create(player: Player): CinematicAction {
        return PumpkinHatCinematicAction(
            player,
            this,
        )
    }

    override fun createSimulating(player: Player): CinematicAction? = null
}

data class PumpkinHatSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
) : Segment

class PumpkinHatCinematicAction(
    private val player: Player,
    private val entry: PumpkinHatCinematicEntry,
) : SimpleCinematicAction<PumpkinHatSegment>() {
    override val segments: List<PumpkinHatSegment> = entry.segments

    // The fake helmet item currently shown, or null when no segment is active.
    // This is purely a client-side packet illusion; the real inventory is never touched.
    private var helmetItem: PacketItemStack? = null

    override suspend fun startSegment(segment: PumpkinHatSegment) {
        super.startSegment(segment)
        val item = entry.customItem.map {
            it.get(player).build(player).apply {
                editMeta { meta ->
                    meta.unClickable()
                }
            }
        }.orElseGet {
            ItemStack(Material.CARVED_PUMPKIN)
                .apply {
                    editMeta { meta ->
                        meta.name = " "
                        meta.unClickable()
                    }
                }
        }
            .toPacketItem()
        helmetItem = item

        // The first-person pumpkin overlay is rendered from the client's OWN inventory helmet
        // slot, so we have to fake that slot for the player. Third-person (F5 / other viewers)
        // reads the entity equipment instead, so we send that too.
        sendHelmetSlot(item)
        sendHelmetEquipment(item)
    }

    override suspend fun tickSegment(segment: PumpkinHatSegment, frame: Int) {
        super.tickSegment(segment, frame)
        // Re-assert every tick. A Camera Cinematic running in parallel fake-clears the inventory
        // (see keepFakeInventory), which would otherwise wipe the fake helmet depending on the
        // order the cinematics tick in. Re-sending keeps the overlay stable regardless.
        helmetItem?.let { sendHelmetSlot(it) }
    }

    override suspend fun stopSegment(segment: PumpkinHatSegment) {
        super.stopSegment(segment)
        helmetItem = null
        // Restore the client's view of the real helmet via packets. We only read the real
        // inventory here; we never write to it.
        val realHelmet = player.inventory.helmet?.toPacketItem() ?: EMPTY
        sendHelmetSlot(realHelmet)
        sendHelmetEquipment(realHelmet)
    }

    // Slot 39 is the helmet in the raw player-inventory numbering used with window id -2,
    // matching how the engine restores inventories (see restoreInventory).
    private fun sendHelmetSlot(item: PacketItemStack) {
        WrapperPlayServerSetSlot(-2, 0, 39, item) sendPacketTo player
    }

    private fun sendHelmetEquipment(item: PacketItemStack) {
        WrapperPlayServerEntityEquipment(
            player.entityId,
            listOf(Equipment(EquipmentSlot.HELMET, item))
        ) sendPacketTo player
    }
}