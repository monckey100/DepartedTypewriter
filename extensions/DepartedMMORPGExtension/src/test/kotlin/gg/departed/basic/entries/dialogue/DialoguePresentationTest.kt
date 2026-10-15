package gg.departed.basic.entries.dialogue

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class DialoguePresentationTest {
    private val session = UUID.randomUUID()

    @Test
    fun `existing conversations use chat by default`() {
        assertEquals(DialoguePresentation.CHAT, resolve(null, enabled = false))
    }

    @Test
    fun `global toggle applies when no conversation override exists`() {
        assertEquals(DialoguePresentation.HUD, resolve(null, enabled = true))
    }

    @Test
    fun `one test conversation can use HUD while global toggle is off`() {
        assertEquals(DialoguePresentation.HUD, resolve(true, enabled = false))
    }

    @Test
    fun `explicit chat overrides globally enabled HUD`() {
        assertEquals(DialoguePresentation.CHAT, resolve(false, enabled = true))
    }

    @Test
    fun `linked entries retain the conversation choice even if global config changes`() {
        val inherited = ConversationPresentation(session, DialoguePresentation.HUD)
        assertEquals(DialoguePresentation.HUD, resolve(null, inherited, false))
        assertEquals(DialoguePresentation.HUD, resolve(false, inherited, true))
    }

    @Test
    fun `an override does not leak to an unrelated conversation`() {
        val inherited = ConversationPresentation(UUID.randomUUID(), DialoguePresentation.HUD)
        assertEquals(DialoguePresentation.CHAT, resolve(null, inherited, false))
    }

    private fun resolve(
        pageOverride: Boolean?,
        inherited: ConversationPresentation? = null,
        enabled: Boolean,
    ) = resolveDialoguePresentation(pageOverride, inherited, session, enabled)
}
