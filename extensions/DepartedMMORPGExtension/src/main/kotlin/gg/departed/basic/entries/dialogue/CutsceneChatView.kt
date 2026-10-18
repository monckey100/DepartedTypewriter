package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.events.AsyncCinematicEndEvent
import com.typewritermc.engine.paper.events.AsyncCinematicStartEvent
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener

/**
 * While a cinematic plays the chat shows the story channel (DepartedRPG `/story` view) and goes back to the
 * player's previous view when it ends (owner, 2026-10-17). The RPG plugin owns the view; this only reports.
 */
@Singleton
class CutsceneChatView : Initializable, Listener {
    private val api: DepartedRpgAPI? by lazy {
        runCatching { Bukkit.getServicesManager().load(DepartedRpgAPI::class.java) }.getOrNull()
    }

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onStart(event: AsyncCinematicStartEvent) {
        val a = api ?: return
        // Older DepartedRPG jars lack the method: NoSuchMethodError is swallowed, nothing else changes.
        runCatching { a.cutsceneChatView(event.player, true) }
    }

    @EventHandler
    fun onEnd(event: AsyncCinematicEndEvent) {
        val a = api ?: return
        runCatching { a.cutsceneChatView(event.player, false) }
    }
}
