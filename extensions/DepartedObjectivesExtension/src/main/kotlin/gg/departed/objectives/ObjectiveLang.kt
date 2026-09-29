package gg.departed.objectives

import dev.departed.language.api.DepartedLanguageAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Objective text translation through DepartedLanguage. Translates the authored
 * text BEFORE placeholders and {progress} tokens are filled in and before the
 * scoreboard wraps it into lines, since wrapped fragments can't be matched.
 */
object ObjectiveLang {
    private val available: Boolean by lazy { Bukkit.getPluginManager().isPluginEnabled("DepartedLanguage") }

    fun tr(player: Player, text: String): String {
        if (text.isBlank() || !available) return text
        return try {
            DepartedLanguageAPI.translate(player, text, "objectives")
        } catch (_: LinkageError) {
            text
        }
    }
}
