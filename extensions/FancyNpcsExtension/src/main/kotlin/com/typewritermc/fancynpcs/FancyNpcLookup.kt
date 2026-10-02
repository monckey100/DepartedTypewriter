package com.typewritermc.fancynpcs

import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.Npc
import de.oliver.fancynpcs.api.actions.ActionTrigger as FancyActionTrigger

internal fun findFancyNpc(identifier: String): Npc? {
    val trimmed = identifier.trim()
    if (trimmed.isBlank()) return null

    val manager = FancyNpcsPlugin.get().npcManager
    return manager.getNpc(trimmed)
        ?: manager.getNpcById(trimmed)
        ?: trimmed.toIntOrNull()?.let { manager.getNpc(it) }
}

internal fun Npc.matchesFancyIdentifier(identifier: String): Boolean {
    val trimmed = identifier.trim()
    if (trimmed.isBlank()) return false

    // Match only on the unique FancyNpcs id/name — never displayName, which is shared
    // across NPCs (e.g. several "Benny") and would fire the wrong NPC's dialogue.
    return data.id.equals(trimmed, ignoreCase = true)
        || data.name.equals(trimmed, ignoreCase = true)
}

enum class FancyNpcInteractionType {
    ANY_CLICK,
    LEFT_CLICK,
    RIGHT_CLICK,
    CUSTOM;

    fun matches(trigger: FancyActionTrigger): Boolean = this == ANY_CLICK || trigger.name == name

    fun toFancyTrigger(): FancyActionTrigger = FancyActionTrigger.valueOf(name)
}
