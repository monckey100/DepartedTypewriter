package com.typewritermc.departednpcs

import dev.departed.departednpc.api.DepartedNpcApi
import dev.departed.departednpc.api.DepartedNpcProvider

/** Fetch the DepartedNPC API, or null if the plugin isn't loaded yet. */
internal fun departedNpcApi(): DepartedNpcApi? = DepartedNpcProvider.get()

/** Whether an NPC with the given id exists. */
internal fun departedNpcExists(identifier: String): Boolean {
    val trimmed = identifier.trim()
    if (trimmed.isBlank()) return false
    return departedNpcApi()?.exists(trimmed) == true
}

/** Case-insensitive id match used to link reference entries to a live NPC id. */
internal fun matchesDepartedNpc(npcId: String, identifier: String): Boolean {
    val trimmed = identifier.trim()
    return trimmed.isNotBlank() && npcId.equals(trimmed, ignoreCase = true)
}

enum class DepartedNpcInteractionType {
    ANY_CLICK,
    LEFT_CLICK,
    RIGHT_CLICK;

    fun matches(leftClick: Boolean): Boolean = when (this) {
        ANY_CLICK -> true
        LEFT_CLICK -> leftClick
        RIGHT_CLICK -> !leftClick
    }
}
