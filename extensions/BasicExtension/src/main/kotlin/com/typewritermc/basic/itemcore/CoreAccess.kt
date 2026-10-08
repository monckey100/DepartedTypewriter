package com.typewritermc.basic.itemcore

import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.item.Item
import dev.departed.itemcore.api.ItemCore
import dev.departed.itemcore.api.equip.EquipmentPolicy
import dev.departed.itemcore.api.item.Intake
import dev.departed.itemcore.api.lease.ClickMode
import dev.departed.itemcore.api.lease.HideMode
import dev.departed.itemcore.api.lease.LeaseRefused
import dev.departed.itemcore.api.lease.MaskSpec
import dev.departed.itemcore.api.lease.ViewLease
import dev.departed.itemcore.api.presentation.IconSpec
import dev.departed.itemcore.api.stock.ConsumeResult
import dev.departed.itemcore.api.stock.Requirement
import dev.departed.itemcore.api.stock.StockSources
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.OptionalInt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every call into DepartedItemCore's API. Only [ItemCoreBridge] reaches this object, and only after
 * [ItemCoreBridge.active] said the core is there, so the JVM never resolves a core class on a server without it.
 */
internal object CoreAccess {
    private val core: ItemCore get() = ItemCore.get()

    fun custody(): Boolean = runCatching {
        core.transfers()
        true
    }.getOrDefault(false)

    fun give(player: Player, stack: ItemStack, source: String): GiveSummary {
        val requested = stack.amount
        val canonical = when (val outcome = core.intake().canonicalize(stack.clone())) {
            is Intake.Outcome.Canonical -> outcome.stack()
            else -> {
                logger.warning("[itemcore] $source: ${stack.type} is not a catalogued item ($outcome); nothing given")
                return GiveSummary(0, requested, "INVALID_ITEM")
            }
        }
        var given = 0
        var refusal: String? = null
        var left = requested
        val max = canonical.maxStackSize.coerceAtLeast(1)
        while (left > 0) {
            val part = canonical.clone().apply { amount = minOf(left, max) }
            val result = core.transfers().give(player, part, source)
            given += result.given()
            refusal = refusal ?: result.refusal()?.code()
            left -= part.amount
        }
        return GiveSummary(given, requested - given, refusal)
    }

    fun count(player: Player, item: Item, context: InteractionContext?): Int {
        val index = core.equipment().index(player.uniqueId)
        return index.countWhere(CoreItemQueries.of(player, item, context))
    }

    fun available(player: Player, item: Item, context: InteractionContext?): Int =
        core.stock().count(player, CoreItemQueries.of(player, item, context), StockSources.INVENTORY)

    fun consume(player: Player, item: Item, context: InteractionContext?, amount: Int, source: String): Int {
        if (amount <= 0) return 0
        val query = CoreItemQueries.of(player, item, context)
        return when (val r = core.stock().consume(player, listOf(Requirement(query, amount)), StockSources.INVENTORY, source)) {
            is ConsumeResult.Consumed -> amount
            is ConsumeResult.Missing -> 0
            is ConsumeResult.Unavailable -> {
                logger.warning("[itemcore] $source: take of $amount refused for ${player.name} (${r.reason()})")
                0
            }
        }
    }

    /**
     * One core VIEW lease per player for all of Typewriter's masks: a camera cinematic, a pumpkin hat and an editor
     * preview can overlap in any start order, and the core shows only the innermost lease, so the requests are
     * merged here (hide ALL beats EQUIPMENT, icons of later requests win) into a single lease.
     */
    private val composites = ConcurrentHashMap<UUID, Composite>()

    fun mask(player: Player, hide: MaskHide, icons: Map<Int, ItemStack>, allowHeldItemUse: Boolean): InventoryMask? {
        val composite = composites.computeIfAbsent(player.uniqueId) { Composite(player) }
        val request = Request(composite, hide, icons, allowHeldItemUse)
        composite.requests += request
        if (composite.refresh()) return request
        composite.requests -= request
        composite.refresh()
        return null
    }

    private class Request(
        val composite: Composite,
        val hide: MaskHide,
        var icons: Map<Int, ItemStack>,
        val allowHeldItemUse: Boolean,
    ) : InventoryMask {
        override fun update(icons: Map<Int, ItemStack>) {
            this.icons = icons
            if (this in composite.requests) composite.refresh()
        }

        override fun release() {
            if (composite.requests.remove(this)) composite.refresh()
        }
    }

    private class Composite(val player: Player) {
        val requests = LinkedHashSet<Request>()
        var lease: ViewLease? = null

        /** Re-renders the merged mask; false when the core refused the lease. */
        fun refresh(): Boolean {
            if (requests.isEmpty()) {
                lease?.release()
                lease = null
                composites.remove(player.uniqueId, this)
                return true
            }
            val spec = merged()
            val held = lease
            if (held != null && held.active()) {
                held.update(spec)
                return true
            }
            return when (val r = ItemCore.get().leases().acquireView(plugin, player, spec)) {
                is ViewLease -> {
                    lease = r
                    true
                }
                is LeaseRefused -> {
                    logger.info("[itemcore] inventory mask refused for ${player.name}: ${r.code()}")
                    lease = null
                    false
                }
                else -> false
            }
        }

        private fun merged(): MaskSpec {
            val hide = if (requests.any { it.hide == MaskHide.ALL }) MaskHide.ALL else MaskHide.EQUIPMENT
            val icons = LinkedHashMap<Int, ItemStack>()
            requests.forEach { icons.putAll(it.icons) }
            return spec(hide, icons, requests.all { it.allowHeldItemUse })
        }
    }

    private fun spec(hide: MaskHide, icons: Map<Int, ItemStack>, allowHeldItemUse: Boolean): MaskSpec {
        val layout = icons.mapValues { (_, stack) -> icon(stack) }
        val mode = when (hide) {
            MaskHide.ALL -> if (layout.isEmpty()) HideMode.ALL else HideMode.LAYOUT
            MaskHide.EQUIPMENT -> HideMode.EQUIPMENT
        }
        return MaskSpec(
            mode,
            if (layout.isEmpty()) null else java.util.function.Function { _ -> layout },
            ClickMode.BLOCK_ALL,
            emptySet(),
            allowHeldItemUse,
            hide == MaskHide.EQUIPMENT,
            false,
            EquipmentPolicy.ACTIVE,
            null,
        )
    }

    private fun icon(stack: ItemStack): IconSpec {
        val cmd = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.firstOrNull()
        return IconSpec(
            null,
            stack.type,
            stack.getData(DataComponentTypes.ITEM_MODEL),
            if (cmd == null) OptionalInt.empty() else OptionalInt.of(cmd.toInt()),
            stack.effectiveName(),
            emptyList(),
            stack.amount.coerceIn(1, 99),
            false,
            true,
            null,
        )
    }
}
