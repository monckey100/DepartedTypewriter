package com.typewritermc.engine.paper.entry.temporal

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

/** Implemented by the subtitle extension so a caller can own a line's entire lifetime. */
interface ScopedNarrationProvider {
    fun say(
        player: Player,
        executionId: UUID,
        speaker: String,
        text: String,
        holdMillis: Long,
        priority: Int,
        active: BooleanSupplier,
    ): CompletionStage<ScopedCinematicOutcome>

    fun cancel(executionId: UUID)
}

/** Java API used by dungeon story actions to present existing dsay subtitles. */
object ScopedNarration {
    @JvmStatic
    fun available(): Boolean = provider() != null

    @JvmStatic
    fun say(
        player: Player,
        executionId: UUID,
        speaker: String,
        text: String,
        holdMillis: Long,
        priority: Int,
        active: BooleanSupplier,
    ): CompletionStage<ScopedCinematicOutcome> =
        provider()?.say(player, executionId, speaker, text, holdMillis, priority, active)
            ?: CompletableFuture.completedFuture(ScopedCinematicOutcome.FAILED)

    @JvmStatic
    fun cancel(executionId: UUID) {
        provider()?.cancel(executionId)
    }

    private fun provider(): ScopedNarrationProvider? =
        Bukkit.getServicesManager().load(ScopedNarrationProvider::class.java)
}
