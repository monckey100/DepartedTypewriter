package gg.departed.basic.entries.dialogue.hud

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Couples viewer-only portrait resources to the extension's reload and player lifecycle. */
@Singleton
class DialoguePortraitLifecycle : Initializable, Listener {
    override suspend fun initialize() {
        onMainThread { Bukkit.getPluginManager().registerEvents(this, plugin) }
    }

    override suspend fun shutdown() {
        onMainThread {
            HandlerList.unregisterAll(this)
            closeAll()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        resources.values.filter { it.playerId == event.player.uniqueId }.forEach { it.close() }
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin || event.plugin.name == "ModelEngine") closeAll()
    }

    private suspend fun onMainThread(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            task()
            return
        }
        suspendCoroutine<Unit> { continuation ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                runCatching(task).fold({ continuation.resume(Unit) }, continuation::resumeWithException)
            })
        }
    }

    companion object {
        private data class Resource(val playerId: UUID, val close: () -> Unit)
        private val resources = ConcurrentHashMap<Any, Resource>()

        fun register(owner: Any, playerId: UUID, close: () -> Unit) {
            resources[owner] = Resource(playerId, close)
        }

        fun unregister(owner: Any) {
            resources.remove(owner)
        }

        private fun closeAll() {
            resources.values.toList().forEach { it.close() }
            resources.clear()
        }
    }
}
