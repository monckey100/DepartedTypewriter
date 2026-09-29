package gg.departed.objectives

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.dialogue.currentDialogue
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.events.AsyncDialogueEndEvent
import com.typewritermc.engine.paper.events.AsyncDialogueStartEvent
import com.typewritermc.engine.paper.events.AsyncDialogueSwitchEvent
import org.koin.java.KoinJavaComponent.get
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val activeDialogues = ConcurrentHashMap<UUID, DialogueSnapshot>()

@Entry("complete_dialogue_objective", "Complete a Typewriter dialogue", Colors.YELLOW, "mingcute:message-4-fill")
class CompleteDialogueObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Finish the conversation."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("The dialogue entry that must finish. Leave empty to accept any dialogue.")
    val dialogue: Ref<DialogueEntry> = emptyRef(),
    @Help("Optional dialogue id or name text. Used only when the dialogue reference is empty.")
    val dialogueIdentifier: String = "",
) : DepartedObjectiveEntry

@EntryListener(CompleteDialogueObjectiveEntry::class)
fun onDialogueObjectiveStart(event: AsyncDialogueStartEvent, query: Query<CompleteDialogueObjectiveEntry>) {
    rememberDialogue(event.player)
}

@EntryListener(CompleteDialogueObjectiveEntry::class)
fun onDialogueObjectiveSwitch(event: AsyncDialogueSwitchEvent, query: Query<CompleteDialogueObjectiveEntry>) {
    rememberDialogue(event.player)
}

@EntryListener(CompleteDialogueObjectiveEntry::class)
fun onDialogueObjectiveEnd(event: AsyncDialogueEndEvent, query: Query<CompleteDialogueObjectiveEntry>) {
    val snapshot = activeDialogues.remove(event.player.uniqueId) ?: return
    query.findWhere { it.matches(snapshot) }
        .forEach { objectives().increment(event.player, it) }
}

private fun rememberDialogue(player: org.bukkit.entity.Player) {
    val dialogue = player.currentDialogue ?: return
    activeDialogues[player.uniqueId] = DialogueSnapshot(dialogue.id, dialogue.name)
}

private fun CompleteDialogueObjectiveEntry.matches(snapshot: DialogueSnapshot): Boolean {
    if (dialogue.isSet) {
        return dialogue.id == snapshot.id
    }
    val identifier = dialogueIdentifier.trim()
    if (identifier.isBlank()) return true
    return snapshot.id.equals(identifier, ignoreCase = true) || snapshot.name.equals(identifier, ignoreCase = true)
}

private data class DialogueSnapshot(val id: String, val name: String)

private fun objectives(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
