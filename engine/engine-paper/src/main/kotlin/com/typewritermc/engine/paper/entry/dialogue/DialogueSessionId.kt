package com.typewritermc.engine.paper.entry.dialogue

import com.typewritermc.core.interaction.GlobalContextKey
import java.util.UUID

/** Identifies one linked dialogue interaction so presentation overrides cannot leak to later conversations. */
object DialogueSessionId : GlobalContextKey<UUID>(UUID::class)
