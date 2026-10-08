package com.typewritermc.basic.itemcore

import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.entries.get
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.item.CustomItem
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.item.SerializedItem
import com.typewritermc.engine.paper.utils.item.components.ItemAmountComponent
import com.typewritermc.engine.paper.utils.item.components.ItemComponent
import com.typewritermc.engine.paper.utils.item.components.ItemMaterialComponent
import com.typewritermc.engine.paper.utils.item.components.ItemPDCComponent
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.BooleanPdcData
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.BytePdcData
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.IntPdcData
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.LongPdcData
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.PdcDataType
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.ShortPdcData
import com.typewritermc.engine.paper.utils.item.components.pdcTypes.StringPdcData
import dev.departed.itemcore.api.ItemCore
import dev.departed.itemcore.api.item.ItemQuery
import dev.departed.itemcore.api.item.ItemView
import dev.departed.itemcore.api.item.StateCodec
import dev.departed.itemcore.api.item.StateKey
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a page's [Item] matcher into a core [ItemQuery] (DESIGN R-identity: an item is its `departed:id` + state,
 * the legacy `departeditems:` / `departedcooking:` PDC keys are gone from canonical stacks).
 *
 * - A `persistent_data_container` component on a legacy key the core re-homed (01 §5 rows 5, 14, 15) becomes an
 *   identity check on the item's id or state: `departeditems:internal_id` = `gear/<id>`, `departeditems:rarity` =
 *   the core rarity, `departedcooking:dish`/`dish_recipe`/`dish_main`/`dish_weight`/`dish_taste`/`flavor_<f>` = the
 *   `dish/` family and its `dish.*` state, `departedcooking:potion`/`potion_id` = the `potion/` family. Once a
 *   matcher names an identity, its material component is ignored (the type decides the look).
 * - A PDC key the core does not re-home never matches a canonical stack (it cannot carry it) and logs once.
 * - Every other component (material, item name, lore, model, ...) is checked against the stack as before.
 * - The amount component is never part of the match; callers read it as the quantity.
 */
object CoreItemQueries {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    private val dishMain = StateKey.of("dish", "main", StateCodec.STRING, null)
    private val dishWeight = StateKey.of("dish", "weight", StateCodec.STRING, "SNACK")
    private val dishTaste = StateKey.of("dish", "taste", StateCodec.INT, 0)
    private val dishFlavor = StateKey.of("dish", "flavor", StateCodec.INT_MAP, emptyMap())

    fun of(player: Player?, item: Item, context: InteractionContext?): ItemQuery = when (item) {
        is SerializedItem -> serialized(player, item, context)
        is CustomItem -> custom(player, item, context)
    }

    private fun serialized(player: Player?, item: SerializedItem, context: InteractionContext?): ItemQuery {
        val template = item.build(player, context)
        val view = ItemCore.get().items().view(template)
        val id = view.id()
        if (id != null && view.canonical()) {
            val fingerprint = view.fingerprint()
            return ItemQuery.id(id).and { it.fingerprint() == fingerprint }
        }
        return ItemQuery(null, null, null) { it.stack()?.isSimilar(template) == true }
    }

    private fun custom(player: Player?, item: CustomItem, context: InteractionContext?): ItemQuery {
        val identity = mutableListOf<(ItemView) -> Boolean>()
        val plain = mutableListOf<ItemComponent>()
        var namesType = false
        for (component in item.components) {
            if (component is ItemAmountComponent) continue
            val legacy = (component as? ItemPDCComponent)?.let { legacyCheck(player, context, it) }
            if (legacy == null) {
                plain += component
                continue
            }
            identity += legacy.check
            namesType = namesType || legacy.namesType
        }
        val stackChecks = if (namesType) plain.filterNot { it is ItemMaterialComponent } else plain
        return ItemQuery(null, null, null) { view ->
            identity.all { it(view) } && (stackChecks.isEmpty() || stackMatches(player, context, stackChecks, view))
        }
    }

    private fun stackMatches(
        player: Player?,
        context: InteractionContext?,
        checks: List<ItemComponent>,
        view: ItemView,
    ): Boolean {
        val stack = view.stack() ?: return false
        return checks.all { it.matches(player, context, stack) }
    }

    private class LegacyCheck(val namesType: Boolean, val check: (ItemView) -> Boolean)

    private fun legacyCheck(player: Player?, context: InteractionContext?, component: ItemPDCComponent): LegacyCheck? {
        val key = component.dataKey.get(player, context)?.trim()?.lowercase().orEmpty()
        val namespace = key.substringBefore(':', "")
        if (namespace != "departeditems" && namespace != "departedcooking") return null
        val value = component.data.get(player, context)?.raw()
        val name = key.substringAfter(':')
        val text = value?.toString().orEmpty()
        val number = (value as? Number)?.toInt()
        return when {
            namespace == "departeditems" && name == "internal_id" -> LegacyCheck(true) { it.typeId() == "gear/$text" }
            namespace == "departeditems" && name == "rarity" -> LegacyCheck(false) { rarity(it).equals(text, true) }
            name == "dish" -> LegacyCheck(true) { it.id()?.family() == "dish" }
            name == "dish_recipe" -> LegacyCheck(true) { it.typeId() == "dish/$text" }
            name == "dish_main" -> LegacyCheck(false) { it.state().get(dishMain) == text }
            name == "dish_weight" -> LegacyCheck(false) { it.state().get(dishWeight).equals(text, true) }
            name == "dish_taste" -> LegacyCheck(false) { it.state().get(dishTaste) == number }
            name.startsWith("flavor_") -> {
                val flavor = name.removePrefix("flavor_")
                LegacyCheck(false) { (it.state().get(dishFlavor)?.get(flavor) ?: 0) == number }
            }
            name == "potion" -> LegacyCheck(true) { it.id()?.family() == "potion" }
            name == "potion_id" -> LegacyCheck(true) { it.typeId() == "potion/$text" }
            else -> {
                if (warned.add(key)) {
                    logger.warning("[itemcore] Typewriter item matcher uses $key, which no core item carries; it never matches")
                }
                LegacyCheck(false) { false }
            }
        }
    }

    private fun rarity(view: ItemView): String? = ItemCore.get().items().rarityOf(view)

    private fun PdcDataType.raw(): Any? = when (this) {
        is StringPdcData -> value
        is IntPdcData -> value
        is BytePdcData -> value
        is ShortPdcData -> value.toInt()
        is LongPdcData -> value
        is BooleanPdcData -> if (value) 1 else 0
        else -> null
    }
}
