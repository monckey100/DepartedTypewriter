package com.typewritermc.engine.paper.entry.dialogue

import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.interaction.GlobalContextKey
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.interaction.RandomSeedContextKey
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.interaction.ChatHistory
import com.typewritermc.engine.paper.interaction.chatHistory
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.interaction.startBlockingActionBar
import com.typewritermc.engine.paper.interaction.stopBlockingActionBar
import com.typewritermc.engine.paper.snippets.SnippetDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import org.bukkit.entity.Player
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class DialogueChatOwnershipTest : FunSpec({
    val staticMocks = arrayOf(
        "com.typewritermc.engine.paper.interaction.ChatHistoryHandlerKt",
        "com.typewritermc.engine.paper.interaction.ActionBarBlockerKt",
        "com.typewritermc.engine.paper.interaction.PlayerSessionKt",
        "com.typewritermc.core.utils.CachedThreadPoolDispatcherKt",
    )

    beforeSpec {
        startKoin {
            modules(module { single<SnippetDatabase> { mockk(relaxed = true) } })
        }
    }

    afterSpec {
        stopKoin()
    }

    beforeTest {
        mockkStatic(*staticMocks)
        every { any<CoroutineContext>().launch(any()) } returns mockk<Job>(relaxed = true)
    }

    afterTest {
        unmockkStatic(*staticMocks)
    }

    test("HUD initialization and final cleanup never touch chat history") {
        val fixture = DialogueFixture()
        val node = fixture.node(capturesChat = false)
        val interaction = DialogueInteraction(fixture.player, context(), node.entry)

        interaction.initialize().isSuccess shouldBe true
        interaction.teardown()

        verify { fixture.history wasNot Called }
        verify(exactly = 1) { fixture.player.startBlockingActionBar() }
        verify(exactly = 1) { fixture.player.stopBlockingActionBar() }
        node.messengers.single().initializations shouldBe 1
        node.messengers.single().disposals shouldBe 1
        node.messengers.single().state shouldBe MessengerState.FINISHED
    }

    test("linked HUD nodes keep chat untouched while disposing every messenger") {
        val fixture = DialogueFixture()
        val first = fixture.node(capturesChat = false)
        val second = fixture.node(capturesChat = false)
        val interaction = DialogueInteraction(fixture.player, context(), first.entry)

        interaction.initialize()
        interaction.next(second.entry, context())
        interaction.teardown()

        verify { fixture.history wasNot Called }
        first.messengers.single().disposals shouldBe 1
        second.messengers.single().disposals shouldBe 1
        first.messengers.single().state shouldBe MessengerState.RUNNING
        second.messengers.single().state shouldBe MessengerState.FINISHED
    }

    test("chat dialogue preserves blocking and restores its history at final cleanup") {
        val fixture = DialogueFixture()
        val node = fixture.node(capturesChat = true)
        val interaction = DialogueInteraction(fixture.player, context(), node.entry)

        interaction.initialize()
        interaction.teardown()

        verify(exactly = 1) { fixture.history.startBlocking() }
        verify(exactly = 1) { fixture.history.stopBlocking() }
        verify(exactly = 1) { fixture.history.resendMessages(fixture.player, true) }
        node.messengers.single().disposals shouldBe 1
    }

    test("switching from chat to HUD restores the old chat once and never at HUD teardown") {
        val fixture = DialogueFixture()
        val chat = fixture.node(capturesChat = true)
        val hud = fixture.node(capturesChat = false)
        val interaction = DialogueInteraction(fixture.player, context(), chat.entry)

        interaction.initialize()
        interaction.next(hud.entry, context())

        verify(exactly = 1) { fixture.history.stopBlocking() }
        verify(exactly = 1) { fixture.history.resendMessages(fixture.player, true) }

        interaction.teardown()

        verify(exactly = 1) { fixture.history.startBlocking() }
        verify(exactly = 1) { fixture.history.stopBlocking() }
        verify(exactly = 1) { fixture.history.resendMessages(fixture.player, true) }
        chat.messengers.single().disposals shouldBe 1
        hud.messengers.single().disposals shouldBe 1
    }

    test("switching from HUD to chat acquires chat ownership and restores it on exit") {
        val fixture = DialogueFixture()
        val hud = fixture.node(capturesChat = false)
        val chat = fixture.node(capturesChat = true)
        val interaction = DialogueInteraction(fixture.player, context(), hud.entry)

        interaction.initialize()
        verify { fixture.history wasNot Called }

        interaction.next(chat.entry, context())
        interaction.teardown()

        verify(exactly = 1) { fixture.history.startBlocking() }
        verify(exactly = 1) { fixture.history.stopBlocking() }
        verify(exactly = 1) { fixture.history.resendMessages(fixture.player, true) }
    }

    test("the base messenger defaults to chat and replays history when it ends") {
        val fixture = DialogueFixture()
        val entry = mockk<DialogueEntry>()
        val messenger = DialogueMessenger(fixture.player, context(), entry)

        messenger.capturesChat shouldBe true
        messenger.end()

        messenger.state shouldBe MessengerState.FINISHED
        verify(exactly = 1) { fixture.history.resendMessages(fixture.player, true) }
    }

    test("session identity and presentation context survive linked nodes and loops") {
        val fixture = DialogueFixture()
        val first = fixture.node(capturesChat = false)
        val second = fixture.node(capturesChat = false)
        val interaction = DialogueInteraction(fixture.player, context(), first.entry)
        val sessionId = interaction.context[DialogueSessionId]
        sessionId shouldNotBe null
        first.messengers.single()[TestPresentationKey] = true

        interaction.initialize()
        interaction.next(second.entry, context { DialogueSessionId withValue UUID.randomUUID() })

        interaction.context[DialogueSessionId] shouldBe sessionId
        interaction.context[TestPresentationKey] shouldBe true

        interaction.next(first.entry, context())

        interaction.context[DialogueSessionId] shouldBe sessionId
        interaction.context[TestPresentationKey] shouldBe true
        first.messengers.size shouldBe 2
        interaction.teardown()
        verify { fixture.history wasNot Called }
    }

    test("a later conversation receives a fresh identity even when its incoming context is stale") {
        val fixture = DialogueFixture()
        val first = fixture.node(capturesChat = false)
        val second = fixture.node(capturesChat = false)
        val earlier = DialogueInteraction(fixture.player, context(), first.entry)
        val later = DialogueInteraction(fixture.player, earlier.context, second.entry)

        earlier.context[DialogueSessionId] shouldNotBe null
        later.context[DialogueSessionId] shouldNotBe null
        later.context[DialogueSessionId] shouldNotBe earlier.context[DialogueSessionId]
    }

    test("adding dialogue identity preserves incoming random seeds and normal linked context merging") {
        val fixture = DialogueFixture()
        val first = fixture.node(capturesChat = false)
        val second = fixture.node(capturesChat = false)
        val incoming = context { RandomSeedContextKey withValue 1234L }
        val interaction = DialogueInteraction(fixture.player, incoming, first.entry)
        val sessionId = interaction.context[DialogueSessionId]

        interaction.context[RandomSeedContextKey] shouldBe 1234L
        interaction.initialize()
        interaction.next(second.entry, InteractionContext(emptyMap()))
        interaction.context[RandomSeedContextKey] shouldBe 1234L

        interaction.next(first.entry, context { RandomSeedContextKey withValue 5678L })
        interaction.context[RandomSeedContextKey] shouldBe 5678L
        interaction.context[DialogueSessionId] shouldBe sessionId
        interaction.teardown()
    }
})

private object TestPresentationKey : GlobalContextKey<Boolean>(Boolean::class)

private class DialogueFixture {
    val player = mockk<Player>(relaxed = true)
    val history = mockk<ChatHistory>(relaxed = true)
    private val speaker = emptyRef<SpeakerEntry>()

    init {
        every { player.chatHistory } returns history
        every { player.interactionContext } returns context()
        every { player.startBlockingActionBar() } just Runs
        every { player.stopBlockingActionBar() } just Runs
    }

    fun node(capturesChat: Boolean): DialogueNode {
        val entry = mockk<DialogueEntry>()
        val messengers = mutableListOf<RecordingDialogueMessenger>()
        every { entry.speaker } returns speaker
        every { entry.messenger(player, any()) } answers {
            RecordingDialogueMessenger(player, secondArg(), entry, capturesChat).also(messengers::add)
        }
        return DialogueNode(entry, messengers)
    }
}

private data class DialogueNode(
    val entry: DialogueEntry,
    val messengers: List<RecordingDialogueMessenger>,
)

private class RecordingDialogueMessenger(
    player: Player,
    context: InteractionContext,
    entry: DialogueEntry,
    override val capturesChat: Boolean,
) : DialogueMessenger<DialogueEntry>(player, context, entry) {
    var initializations = 0
        private set
    var disposals = 0
        private set

    override fun init() {
        initializations++
    }

    override fun dispose() {
        disposals++
    }
}
