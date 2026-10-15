package gg.departed.basic.entries.dialogue

import com.typewritermc.core.entries.Entry
import com.typewritermc.core.entries.pageId
import com.typewritermc.core.entries.ref
import com.typewritermc.core.interaction.ContextModifier
import com.typewritermc.core.interaction.GlobalContextKey
import com.typewritermc.engine.paper.entry.dialogue.DialogueSessionId
import com.typewritermc.engine.paper.utils.config
import com.typewritermc.engine.paper.plugin
import java.util.UUID

private val defaultHudEnabled by config(
    "dialogue.hud.enabled",
    false,
    "Use the Departed resource-pack action-bar HUD for NPC conversations by default. " +
        "Override individual Typewriter pages with dialogue.hud.pages.<page-id>: true or false.",
)

enum class DialoguePresentation {
    HUD,
    CHAT,
}

internal data class ConversationPresentation(val sessionId: UUID?, val presentation: DialoguePresentation)

internal object ConversationPresentationKey : GlobalContextKey<ConversationPresentation>(ConversationPresentation::class)

/** A page selects the presentation once; all linked dialogue nodes retain that choice. */
internal fun ContextModifier.useDialogueHud(entry: Entry): Boolean {
    val sessionId = context[DialogueSessionId]
    val pageId = entry.ref().pageId
    val pageOverride = pageId?.let {
        plugin.config.getConfigurationSection("dialogue.hud.pages")?.getValues(false)?.get(it) as? Boolean
    }
    val presentation = resolveDialoguePresentation(
        pageOverride,
        context[ConversationPresentationKey],
        sessionId,
        defaultHudEnabled,
    )
    this[ConversationPresentationKey] = ConversationPresentation(sessionId, presentation)
    return presentation == DialoguePresentation.HUD
}
