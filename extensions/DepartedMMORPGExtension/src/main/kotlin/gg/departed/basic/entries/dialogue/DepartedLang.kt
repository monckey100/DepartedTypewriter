package gg.departed.basic.entries.dialogue

import dev.departed.language.api.DepartedLanguageAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Dialogue translation through DepartedLanguage. Call on the AUTHORED text,
 * before PlaceholderAPI parsing, so the dictionary key is the line as written
 * in the page. Returns the text unchanged if DepartedLanguage is missing, the
 * player reads English, or the line has no translation yet.
 */
object DepartedLang {
    private val available: Boolean by lazy { Bukkit.getPluginManager().isPluginEnabled("DepartedLanguage") }

    fun tr(player: Player, text: String): String {
        if (text.isBlank() || !available) return text
        return try {
            DepartedLanguageAPI.translate(player, text, "dialogue")
        } catch (_: LinkageError) {
            text
        }
    }

    /**
     * The line the NPC voice reads. A translated voicetext wins; if only the
     * dialogue text has a translation, the voice follows the translated text so
     * it runs as long as what the player is reading.
     */
    fun voice(player: Player, voiceText: String?, sourceText: String, localText: String): String {
        if (voiceText.isNullOrBlank()) return localText
        val localVoice = tr(player, voiceText)
        if (localVoice != voiceText) return localVoice
        return if (localText != sourceText) localText else voiceText
    }
}
