package com.typewritermc.departednpcs.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.departednpcs.DepartedNpcInteractionType
import com.typewritermc.departednpcs.QuestOfferGate
import com.typewritermc.departednpcs.entries.entity.ReferenceDepartedNpcEntry
import com.typewritermc.departednpcs.matchesDepartedNpc
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.dialogue.isInDialogue
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.startDialogueWithOrNextDialogue
import dev.departed.departednpc.api.event.DepartedNpcInteractEvent
import org.bukkit.Bukkit

@Entry("on_departednpc_interact", "When a player clicks a DepartedNPC NPC", Colors.YELLOW, "fa6-solid:people-robbery")
/**
 * The `DepartedNPC Interact Event` is fired when a player interacts with a DepartedNPC NPC.
 */
class DepartedNpcInteractEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The DepartedNPC id to listen for. Takes priority over the NPC reference below.")
    val npcIdentifier: String = "",
    @Help("A reference NPC entry to listen for. Only used when the identifier above is empty. Leave both empty to listen for all DepartedNPC NPCs.")
    val identifier: Ref<ReferenceDepartedNpcEntry> = emptyRef(),
    @Help("The click type to listen for.")
    val interactionType: DepartedNpcInteractionType = DepartedNpcInteractionType.ANY_CLICK,
) : EventEntry

@EntryListener(DepartedNpcInteractEventEntry::class)
fun onDepartedNpcInteract(event: DepartedNpcInteractEvent, query: Query<DepartedNpcInteractEventEntry>) {
    val npcId = event.npcId
    val referenceIds = Query.findWhere<ReferenceDepartedNpcEntry> { it.matches(npcId) }.map { it.id }.toSet()

    val entries = query.findWhere { entry ->
        if (!entry.interactionType.matches(event.isLeftClick)) return@findWhere false
        when {
            entry.npcIdentifier.isNotBlank() -> matchesDepartedNpc(npcId, entry.npcIdentifier)
            entry.identifier.isSet -> entry.identifier.id in referenceIds
            else -> true // Neither set: listen for all DepartedNPC NPCs.
        }
    }.toList()
    val context = context()

    // Quest-offer gate: if the conversation this click would start runs into a quest activation
    // the concurrency policy rejects, don't play the offer at all — the NPC says the "you're
    // busy" line right away instead (see QuestOfferGate). Clicks that merely advance an ongoing
    // dialogue are never gated. The plugin check keeps this extension loadable without
    // DepartedRPG: QuestOfferGate is only classloaded when the plugin is present.
    if (!event.player.isInDialogue && Bukkit.getPluginManager().isPluginEnabled("DepartedRPG")) {
        val blocked = QuestOfferGate.findBlockedOffer(event.player, entries, context)
        if (blocked != null) {
            QuestOfferGate.notifyBlocked(event.player, blocked)
            return
        }
    }

    entries.startDialogueWithOrNextDialogue(event.player, context)
}
