package com.typewritermc.engine.paper.entry.dialogue

import com.typewritermc.core.entries.Query
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.ScopedDialogueEntry
import com.typewritermc.engine.paper.entry.temporal.ScopedCinematicOutcome
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.toBukkitLocation
import com.typewritermc.engine.paper.utils.toPosition
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.function.Predicate

/** Returns the selected entry references to the caller that owns story progression. */
data class ScopedDialogueResult(val outcome: ScopedCinematicOutcome, val choices: List<String> = emptyList())

/** Plays one existing dialogue node with native run ownership and caller-owned control mounts. */
object ScopedDialogues {
    private val executions = ConcurrentHashMap<UUID, ScopedDialogue>()

    @JvmStatic
    fun hasDialogue(id: String): Boolean = Query.findById<ScopedDialogueEntry>(id) != null

    @JvmStatic
    fun play(
        player: Player,
        id: String,
        execution: UUID,
        resolve: Function<Location, Location?>,
        permits: Predicate<Location>,
        spawn: Function<Location, ArmorStand>,
    ): CompletionStage<ScopedDialogueResult> {
        val entry = Query.findById<ScopedDialogueEntry>(id)
            ?: return CompletableFuture.completedFuture(ScopedDialogueResult(ScopedCinematicOutcome.FAILED))
        val manager = get<PlayerSessionManager>(PlayerSessionManager::class.java)
        val session = with(manager) { player.session }
        if (!player.isOnline || session == null || !permits.test(player.location)) {
            return CompletableFuture.completedFuture(ScopedDialogueResult(ScopedCinematicOutcome.FAILED))
        }
        val playback = ScopedDialogue(entry, resolve, permits, spawn)
        if (executions.putIfAbsent(execution, playback) != null) {
            return CompletableFuture.completedFuture(ScopedDialogueResult(ScopedCinematicOutcome.FAILED))
        }
        playback.result.whenComplete { _, _ -> executions.remove(execution, playback) }
        if (!session.offerScopedDialogue(playback)) playback.finish(ScopedCinematicOutcome.FAILED)
        return playback.result
    }

    @JvmStatic
    fun cancel(execution: UUID) {
        executions[execution]?.let { playback ->
            playback.end(ScopedCinematicOutcome.CANCELLED)
            if (Bukkit.isPrimaryThread()) playback.clearControls()
            else Bukkit.getScheduler().runTask(plugin, Runnable { playback.clearControls() })
        }
    }
}

class ScopedDialogue internal constructor(
    internal val entry: ScopedDialogueEntry,
    private val resolve: Function<Location, Location?>,
    private val permits: Predicate<Location>,
    private val spawn: Function<Location, ArmorStand>,
) {
    internal val result = CompletableFuture<ScopedDialogueResult>()
    @Volatile private var outcome: ScopedCinematicOutcome? = null
    private var choices: List<String> = emptyList()
    private var cleanup: (() -> Unit)? = null
    val active: Boolean get() = outcome == null && !result.isDone

    fun valid(player: Player): Boolean = active && player.isOnline && permits.test(player.location)

    fun spawnControl(location: Location): ArmorStand {
        check(active && permits.test(location)) { "Dialogue control mount left its run" }
        return spawn.apply(location)
    }

    @Synchronized
    fun registerControls(dispose: () -> Unit) {
        check(active && cleanup == null)
        cleanup = dispose
    }

    @Synchronized
    fun clearControls() {
        val dispose = cleanup
        cleanup = null
        dispose?.invoke()
    }

    fun resolve(local: Position): Position =
        checkNotNull(resolve.apply(Location(null, local.x, local.y, local.z, local.yaw, local.pitch))).toPosition()

    @Synchronized
    fun render(position: Position, send: () -> Unit) {
        check(active && permits.test(position.toBukkitLocation())) { "Dialogue left its run" }
        send()
    }

    @Synchronized
    internal fun end(value: ScopedCinematicOutcome, selected: List<String> = emptyList()) {
        val previous = outcome
        if (previous == null || value.ordinal > previous.ordinal) {
            outcome = value
            choices = selected.toList()
        }
    }

    internal fun finish(fallback: ScopedCinematicOutcome) {
        result.complete(ScopedDialogueResult(outcome ?: fallback, choices))
    }
}

val Player.scopedDialogue: ScopedDialogue?
    get() = with(get<PlayerSessionManager>(PlayerSessionManager::class.java)) {
        (session?.interaction as? DialogueInteraction)?.scoped
    }
