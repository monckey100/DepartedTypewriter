package com.typewritermc.engine.paper.entry.temporal

import com.typewritermc.core.books.pages.PageType
import com.typewritermc.core.entries.Query
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Plays a cinematic page for one player on behalf of another plugin and says how it ended, the way a dungeon run's
 * scene step waits for its cutscene. The page plays as any page does, with every entry it has; where its positions
 * stand is decided by [com.typewritermc.engine.paper.utils.MapPlaces], not here.
 */
object CinematicPlayback {
    /** Typewriter has loaded its sessions and pages. */
    @JvmStatic
    fun ready(): Boolean = get<PlayerSessionManager>(PlayerSessionManager::class.java).loaded

    @JvmStatic
    fun isCinematic(pageId: String): Boolean {
        val page = Query.findPageById(pageId) ?: return false
        return page.type == PageType.CINEMATIC && page.entries.isNotEmpty()
    }

    /**
     * Starts the page for the player. Completes with true when it played to its last frame, false when it was
     * stopped or could not start; a page that never gets to start (a more important interaction holds the player)
     * leaves the stage open, so the caller bounds the wait.
     */
    @JvmStatic
    fun play(player: Player, pageId: String): CompletionStage<Boolean> {
        val ended = CompletableFuture<Boolean>()
        if (!player.isOnline || !isCinematic(pageId)) {
            ended.complete(false)
            return ended
        }
        TemporalStartTrigger(pageId, ended = ended).triggerFor(player, context())
        return ended
    }

    /** Stops the page if the player is playing it. */
    @JvmStatic
    fun stop(player: Player, pageId: String) {
        if (player.isPlayingTemporal(pageId)) TemporalStopTrigger.triggerFor(player, context())
    }
}
