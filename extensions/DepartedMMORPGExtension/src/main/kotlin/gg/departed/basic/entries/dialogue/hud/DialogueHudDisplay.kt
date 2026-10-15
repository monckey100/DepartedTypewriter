package gg.departed.basic.entries.dialogue.hud

import com.typewritermc.engine.paper.interaction.acceptActionBarMessage
import com.typewritermc.engine.paper.utils.plainText
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

internal fun revealedHudCharacters(component: Component, percentage: Double): Int {
    val text = component.plainText()
    val count = text.codePointCount(0, text.length)
    return (count * percentage.coerceIn(0.0, 1.0)).toInt()
}

internal fun Player.showDialogueHud(component: Component) {
    acceptActionBarMessage(component)
    sendActionBar(component)
}

internal fun Player.clearDialogueHud() = showDialogueHud(Component.empty().font(Key.key("departed:dialogue/space")))
