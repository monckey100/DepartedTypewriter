package com.typewritermc.engine.paper.entry.temporal

import com.typewritermc.core.entries.Query
import com.typewritermc.core.interaction.ContextModifier
import com.typewritermc.core.interaction.Interaction
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.failure
import com.typewritermc.core.utils.ok
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.CinematicEntry
import com.typewritermc.engine.paper.entry.entries.EventTrigger
import com.typewritermc.engine.paper.entry.entries.ScopedCinematicEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.temporal.TemporalState.ENDING
import com.typewritermc.engine.paper.entry.temporal.TemporalState.PLAYING
import com.typewritermc.engine.paper.entry.temporal.TemporalState.STARTING
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.events.AsyncCinematicEndEvent
import com.typewritermc.engine.paper.events.AsyncCinematicStartEvent
import com.typewritermc.engine.paper.events.AsyncCinematicTickEvent
import com.typewritermc.engine.paper.interaction.*
import com.typewritermc.engine.paper.utils.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent
import java.time.Duration

class TemporalInteraction(
    val pageId: String,
    private val player: Player,
    private val startContext: InteractionContext,
    val eventTriggers: List<EventTrigger>,
    private val settings: TemporalSettings,
    internal val scoped: ScopedCinematic? = null,
) : Interaction {
    internal var state = STARTING
        private set
    private var playTime = Duration.ofMillis(-1)
    val frame: Int get() = (playTime.toMillis() / 50).toInt()

    /** While paused the clock stands still: actions keep ticking on the same frame (a subtitle waiting for the player). */
    @Volatile
    var paused: Boolean = false

    override val priority by lazy { Query.findPageById(pageId)?.priority ?: 0 }

    private lateinit var actions: List<CinematicAction>
    private var blockingStarted = false

    override val context: InteractionContext
        get() {
            if (!::actions.isInitialized) return startContext
            return actions.filterIsInstance<ContextModifier>()
                .fold(startContext) { context, modifier -> context.combine(modifier.additionContext) }
        }

    override suspend fun initialize(): Result<Unit> {
        if (state != STARTING) return failure("Temporal interaction is already initialized")
        if (scoped != null && (!scoped.active || !ScopedCinematics.hasScene(pageId))) {
            scoped.requestEnd(ScopedCinematicOutcome.FAILED)
            return failure("Scoped cinematic ${pageId} is unavailable")
        }

        if (settings.blockChatMessages) player.startBlockingMessages()
        if (settings.blockActionBarMessages) player.startBlockingActionBar()
        blockingStarted = true

        actions = Query.findWhereFromPage<CinematicEntry>(pageId) {
            (scoped == null || it is ScopedCinematicEntry) && it.criteria.matches(player, context)
        }.map { it.create(player) }.toList()

        actions.forEach {
            try {
                perform { if (scoped?.active != false) it.setup() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                if (scoped != null) {
                    scoped.requestEnd(ScopedCinematicOutcome.FAILED)
                    return failure("Could not set up scoped cinematic ${pageId}")
                }
            }
        }
        if (scoped?.active == false) return failure("Scoped cinematic ended during setup")

        state = PLAYING

        Dispatchers.UntickedAsync.switchContext {
            AsyncCinematicStartEvent(player, pageId).callEvent()
        }
        return ok(Unit)
    }

    override suspend fun tick(deltaTime: Duration) {
        if (state != PLAYING || scoped?.active == false) return
        if (canEnd) {
            // The clock was moved past the last frame between ticks (TemporalSetFrameTrigger): still end the
            // cinematic, once. Without this the interaction would idle here forever.
            requestStop()
            return
        }

        // Make sure that the first frame is 0
        if (playTime.isNegative) playTime = Duration.ZERO
        else if (!paused) playTime += deltaTime

        coroutineScope {
            actions.map {
                launch {
                    if (scoped?.active == false) return@launch
                    try {
                        perform { if (scoped?.active != false) it.tick(frame) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        e.printStackTrace()
                        scoped?.requestEnd(ScopedCinematicOutcome.FAILED)
                    }
                }
            }.joinAll()
        }
        if (scoped?.active != false) AsyncCinematicTickEvent(player, frame).callEvent()

        if (canEnd) requestStop()
    }

    private val canEnd get() = actions.all { it.canFinish(frame) }
    /** The stop trigger has been fired for this interaction; it must not fire twice (the page triggers would double). */
    private var stopRequested = false

    private suspend fun perform(action: suspend () -> Unit) {
        if (scoped == null) action()
        else Dispatchers.Sync.switchContext { action() }
    }

    private fun requestStop() {
        if (stopRequested) return
        stopRequested = true
        if (scoped == null) TemporalStopTrigger.triggerFor(player, context)
        else scoped.requestEnd(ScopedCinematicOutcome.COMPLETED)
    }

    override suspend fun teardown() {
        if (state == ENDING || state != PLAYING && scoped == null) return
        val started = state == PLAYING
        state = ENDING
        val originalFrame = frame

        try {
            if (blockingStarted) {
                if (settings.blockChatMessages) {
                    player.stopBlockingMessages()
                    player.chatHistory.resendMessages(player)
                }
                if (settings.blockActionBarMessages) player.stopBlockingActionBar()
            }

            if (::actions.isInitialized) actions.forEach {
                try {
                    perform { it.teardown() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    scoped?.requestEnd(ScopedCinematicOutcome.FAILED)
                }
            }

            if (started) Dispatchers.UntickedAsync.switchContext {
                AsyncCinematicEndEvent(player, originalFrame, pageId).callEvent()
            }
        } finally {
            scoped?.finish(ScopedCinematicOutcome.CANCELLED)
        }
    }

    fun playTime(playTime: Duration) {
        if (state != PLAYING) return
        this.playTime = playTime
    }

    fun frame(frame: Int) = playTime(Duration.ofMillis(frame * 50L))
}

internal enum class TemporalState {
    STARTING, PLAYING, ENDING
}

private val Player.temporalInteraction: TemporalInteraction?
    get() = with(KoinJavaComponent.get<PlayerSessionManager>(PlayerSessionManager::class.java)) {
        session?.interaction as? TemporalInteraction
    }

fun Player.currentTemporalFrame(): Int? = temporalInteraction?.frame

/** Freeze or release the running cinematic's clock (no-op when the player is not in one). */
fun Player.setTemporalPaused(paused: Boolean) {
    temporalInteraction?.paused = paused
}

fun Player.isTemporalPaused(): Boolean = temporalInteraction?.paused == true

/** Jump the running cinematic to [frame] (commands between are replayed by the segment runner). */
fun Player.setTemporalFrame(frame: Int) {
    temporalInteraction?.frame(frame)
}

fun Player.isPlayingTemporal(pageId: String): Boolean = temporalInteraction?.pageId == pageId

fun Player.isPlayingTemporal(): Boolean = temporalInteraction != null

data class TemporalStartTrigger(
    val pageId: String,
    val eventTriggers: List<EventTrigger> = emptyList(),
    val settings: TemporalSettings = TemporalSettings(),
) : EventTrigger {
    val priority: Int by lazy(LazyThreadSafetyMode.NONE) { Query.findPageById(pageId)?.priority ?: 0 }

    override val id: String
        get() = "system.temporal.start.$pageId"
}

data class TemporalSettings(
    val blockChatMessages: Boolean = true,
    val blockActionBarMessages: Boolean = true,
)

data object TemporalStopTrigger : EventTrigger {
    override val id: String
        get() = "system.temporal.stop"
}

data class TemporalSetFrameTrigger(
    val frame: Int,
) : EventTrigger {
    override val id: String
        get() = "system.temporal.setFrame"
}
