package com.typewritermc.fancynpcs.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.ContentEditor
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.startDialogueWithOrNextDialogue
import com.typewritermc.fancynpcs.FancyNpcInteractionType
import com.typewritermc.fancynpcs.content.SelectFancyNpcContentMode
import com.typewritermc.fancynpcs.entries.entity.ReferenceFancyNpcEntry
import com.typewritermc.fancynpcs.matchesFancyIdentifier
import de.oliver.fancynpcs.api.Npc
import de.oliver.fancynpcs.api.events.NpcInteractEvent

@Entry("on_fancynpc_interact", "When a player clicks a FancyNpcs NPC", Colors.YELLOW, "fa6-solid:people-robbery")
/**
 * The `FancyNpcs Interact Event` is fired when a player interacts with a FancyNpcs NPC.
 */
class FancyNpcInteractEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The FancyNpcs name or id to listen for. Click the capture button and click an NPC in-game to fill this in. Takes priority over the NPC reference below.")
    @ContentEditor(SelectFancyNpcContentMode::class)
    val npcIdentifier: String = "",
    @Help("A reference NPC entry to listen for. Only used when the identifier above is empty. Leave both empty to listen for all FancyNpcs NPCs.")
    val identifier: Ref<ReferenceFancyNpcEntry> = emptyRef(),
    @Help("The click type to listen for.")
    val interactionType: FancyNpcInteractionType = FancyNpcInteractionType.ANY_CLICK,
) : EventEntry

@EntryListener(FancyNpcInteractEventEntry::class)
fun onFancyNpcInteract(event: NpcInteractEvent, query: Query<FancyNpcInteractEventEntry>) {
    val referenceIds = event.npc.referenceIds()

    query.findWhere { entry ->
        if (!entry.interactionType.matches(event.interactionType)) return@findWhere false
        when {
            entry.npcIdentifier.isNotBlank() -> event.npc.matchesFancyIdentifier(entry.npcIdentifier)
            entry.identifier.isSet -> entry.identifier.id in referenceIds
            else -> true // Neither set: listen for all FancyNpcs NPCs.
        }
    }.startDialogueWithOrNextDialogue(event.player, context())
}

private fun Npc.referenceIds(): Set<String> {
    return Query.findWhere<ReferenceFancyNpcEntry> {
        it.matches(this)
    }.map { it.id }.toSet()
}
