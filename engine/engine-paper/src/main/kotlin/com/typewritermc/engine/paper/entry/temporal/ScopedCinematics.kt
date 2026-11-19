package com.typewritermc.engine.paper.entry.temporal

import com.typewritermc.core.books.pages.PageType
import com.typewritermc.core.entries.Query
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.ScopedCameraEntry
import com.typewritermc.engine.paper.entry.entries.ScopedCinematicEntry
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.toBukkitLocation
import com.typewritermc.engine.paper.utils.toPosition
import kotlinx.coroutines.future.await
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.function.Predicate

/** Plays an opted-in Typewriter page inside a caller-owned coordinate and movement scope. */
object ScopedCinematics {
    private val executions = ConcurrentHashMap<UUID, ScopedCinematic>()

    @JvmStatic
    fun ready(): Boolean = get<PlayerSessionManager>(PlayerSessionManager::class.java).loaded

    @JvmStatic
    fun hasScene(pageId: String): Boolean {
        val page = Query.findPageById(pageId) ?: return false
        return page.type == PageType.CINEMATIC && page.entries.isNotEmpty() &&
            page.entries.all { it is ScopedCinematicEntry } && page.entries.count { it is ScopedCameraEntry } <= 1
    }

    @JvmStatic
    fun play(
        player: Player,
        pageId: String,
        executionId: UUID,
        resolve: Function<Location, Location?>,
        permits: Predicate<Location>,
        move: Function<Location, CompletionStage<Boolean>>,
    ): CompletionStage<ScopedCinematicOutcome> {
        val manager = get<PlayerSessionManager>(PlayerSessionManager::class.java)
        val session = with(manager) { player.session }
        if (!player.isOnline || session == null || !hasScene(pageId)) {
            return CompletableFuture.completedFuture(ScopedCinematicOutcome.FAILED)
        }
        val playback = ScopedCinematic(pageId, resolve, permits, move)
        if (executions.putIfAbsent(executionId, playback) != null) {
            return CompletableFuture.completedFuture(ScopedCinematicOutcome.FAILED)
        }
        playback.result.whenComplete { _, _ -> executions.remove(executionId, playback) }
        if (!session.offerScopedCinematic(playback)) playback.finish(ScopedCinematicOutcome.FAILED)
        return playback.result
    }

    @JvmStatic
    fun cancel(executionId: UUID) {
        executions[executionId]?.let { playback ->
            playback.requestEnd(ScopedCinematicOutcome.CANCELLED)
            if (Bukkit.isPrimaryThread()) playback.clearCamera()
            else Bukkit.getScheduler().runTask(plugin, Runnable { playback.clearCamera() })
        }
    }
}

enum class ScopedCinematicOutcome { COMPLETED, FAILED, CANCELLED }

/** Camera actions use the caller's bounds and teleport authority for every rendered position. */
class ScopedCinematic internal constructor(
    internal val pageId: String,
    private val resolve: Function<Location, Location?>,
    private val permits: Predicate<Location>,
    private val move: Function<Location, CompletionStage<Boolean>>,
) {
    internal val result = CompletableFuture<ScopedCinematicOutcome>()
    @Volatile internal var requestedOutcome: ScopedCinematicOutcome? = null
        private set

    private var cameraCleanup: (() -> Unit)? = null

    val active: Boolean get() = requestedOutcome == null && !result.isDone

    fun resolve(local: Position): Position {
        val location = resolve.apply(Location(null, local.x, local.y, local.z, local.yaw, local.pitch))
        return checkNotNull(location) { "Cinematic point is outside its dungeon slot" }.toPosition()
    }

    private fun checked(position: Position): Location {
        check(active) { "Scoped cinematic has ended" }
        val location = position.toBukkitLocation()
        check(permits.test(location)) { "Cinematic path left its dungeon slot" }
        return location
    }

    suspend fun move(position: Position) {
        check(move.apply(checked(position)).toCompletableFuture().await()) { "Dungeon denied a cinematic move" }
    }

    @Synchronized
    fun render(position: Position, send: () -> Unit) {
        checked(position)
        send()
    }

    @Synchronized
    fun registerCamera(cleanup: () -> Unit) {
        check(active)
        check(cameraCleanup == null)
        cameraCleanup = cleanup
    }

    @Synchronized
    fun clearCamera() {
        val cleanup = cameraCleanup
        cameraCleanup = null
        cleanup?.invoke()
    }

    @Synchronized
    internal fun requestEnd(outcome: ScopedCinematicOutcome) {
        val previous = requestedOutcome
        if (previous == null || outcome.ordinal > previous.ordinal) requestedOutcome = outcome
    }

    internal fun finish(fallback: ScopedCinematicOutcome) {
        result.complete(requestedOutcome ?: fallback)
    }
}

val Player.scopedCinematic: ScopedCinematic?
    get() = with(get<PlayerSessionManager>(PlayerSessionManager::class.java)) {
        (session?.interaction as? TemporalInteraction)?.scoped
    }
