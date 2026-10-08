package com.typewritermc.basic.itemcore

import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.server
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Soft link from Typewriter's generic extensions to DepartedItemCore (DESIGN R15: the core is the only inventory
 * writer and the only inventory hider).
 *
 * Check [active] before any other call. While it is false (the core is not installed, or it runs in
 * `mode: observe`, where DepartedStorage still owns custody) the entries keep Typewriter's own inventory code and no
 * class of the core is ever loaded, so the engine and BasicExtension still run on a server without the core. While
 * it is true every give, take, count and inventory mask goes through the core. Every other method runs on the
 * Server thread unless its KDoc says otherwise.
 */
object ItemCoreBridge {
    const val PLUGIN = "DepartedItemCore"

    /** [any] True while DepartedItemCore is enabled in custody mode. */
    val active: Boolean
        get() = server.pluginManager.isPluginEnabled(PLUGIN) && CoreAccess.custody()

    /**
     * Gives [stack] into the player's custody: inventory, then the overflow backpack, then the durable delivery
     * queue; never dropped on the ground. A stack the core cannot canonicalise (an uncatalogued legacy item) is
     * refused and stays with the caller.
     */
    fun give(player: Player, stack: ItemStack, source: String): GiveSummary = CoreAccess.give(player, stack, source)

    /** [any] Units of [item] in the player's live inventory, matched on the core's item identity. */
    fun count(player: Player, item: Item, context: InteractionContext?): Int = CoreAccess.count(player, item, context)

    /** Units of [item] the player could hand over (hotbar and bag, never equipment, armor, offhand or cursor). */
    fun available(player: Player, item: Item, context: InteractionContext?): Int =
        CoreAccess.available(player, item, context)

    /**
     * Takes exactly [amount] units of [item] from the hotbar and bag in one all-or-nothing core plan. Returns
     * [amount] when taken, 0 when the player holds fewer or the core refused (nothing changed then).
     */
    fun consume(player: Player, item: Item, context: InteractionContext?, amount: Int, source: String): Int =
        CoreAccess.consume(player, item, context, amount, source)

    /**
     * Hides live slots behind a core VIEW lease (MASK: packets only, the real items never move). [icons] are shown
     * in their live slot (36-39 armor, 0-35 inventory) instead of the hidden item. Returns null when the core
     * refused the lease (dead player, session not ready); the caller then shows nothing special.
     */
    fun mask(
        player: Player,
        hide: MaskHide,
        icons: Map<Int, ItemStack> = emptyMap(),
        allowHeldItemUse: Boolean = false,
    ): InventoryMask? = CoreAccess.mask(player, hide, icons, allowHeldItemUse)
}

/** Which live slots a [ItemCoreBridge.mask] hides. The offhand (the DepartedMaps minimap) always stays visible. */
enum class MaskHide {
    /** Hotbar, inventory, equipment and armor (0-39). */
    ALL,

    /** Equipment and armor only (9-17, 36-39); hotbar and bag stay usable. */
    EQUIPMENT,
}

/** A held core mask. [release] is idempotent and must run on the Server thread. */
interface InventoryMask {
    fun update(icons: Map<Int, ItemStack>)
    fun release()
}

/** Outcome of [ItemCoreBridge.give]; [given] counts units placed or queued for delivery. */
data class GiveSummary(val given: Int, val notPlaced: Int, val refusal: String?)
