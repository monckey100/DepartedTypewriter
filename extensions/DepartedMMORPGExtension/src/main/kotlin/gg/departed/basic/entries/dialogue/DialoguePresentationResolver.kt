package gg.departed.basic.entries.dialogue

import java.util.UUID

internal fun resolveDialoguePresentation(
    pageOverride: Boolean?,
    inherited: ConversationPresentation?,
    sessionId: UUID?,
    globallyEnabled: Boolean,
): DialoguePresentation = when {
    inherited != null && inherited.sessionId == sessionId -> inherited.presentation
    pageOverride != null -> if (pageOverride) DialoguePresentation.HUD else DialoguePresentation.CHAT
    globallyEnabled -> DialoguePresentation.HUD
    else -> DialoguePresentation.CHAT
}
