package gg.departed.objectives

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.utils.server
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

@Singleton
class ObjectivePlaceholderExpansion(
    private val objectives: DepartedObjectiveManager,
) : Initializable {
    private var expansion: PlaceholderExpansion? = null

    override suspend fun initialize() {
        if (!server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        expansion = object : PlaceholderExpansion() {
            override fun getIdentifier(): String = "objective"
            override fun getAuthor(): String = "Departed"
            override fun getVersion(): String = "1.0.0"
            override fun persist(): Boolean = true

            override fun onRequest(player: OfflinePlayer?, params: String): String? {
                val online = player as? Player ?: return ""
                return objectives.resolvePlaceholder(online, params)
            }

            override fun onPlaceholderRequest(player: Player?, params: String): String? {
                return player?.let { objectives.resolvePlaceholder(it, params) } ?: ""
            }
        }.also { it.register() }
    }

    override suspend fun shutdown() {
        expansion?.unregister()
        expansion = null
    }

    private fun DepartedObjectiveManager.resolvePlaceholder(player: Player, params: String): String {
        val normalized = params.lowercase()
        return when {
            normalized.isBlank() || normalized == "raw" -> objectiveText(player)
            normalized == "short" -> objectiveShortText(player)
            normalized == "line_count" || normalized == "lines" -> objectiveLineCount(player).toString()
            normalized.startsWith("line_") -> objectiveLine(player, normalized.substringAfter("line_").toIntOrNull())
            normalized.startsWith("line") -> objectiveLine(player, normalized.substringAfter("line").toIntOrNull())
            normalized.toIntOrNull() != null -> objectiveLine(player, normalized.toIntOrNull())
            else -> objectiveText(player)
        }
    }
}
