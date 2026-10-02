package com.typewritermc.fancynpcs.content

import com.typewritermc.core.entries.Entry
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.failure
import com.typewritermc.core.utils.ok
import com.typewritermc.engine.paper.content.ContentComponent
import com.typewritermc.engine.paper.content.ContentContext
import com.typewritermc.engine.paper.content.ContentMode
import com.typewritermc.engine.paper.content.components.bossBar
import com.typewritermc.engine.paper.content.components.exit
import com.typewritermc.engine.paper.content.entryId
import com.typewritermc.engine.paper.content.fieldPath
import com.typewritermc.engine.paper.entry.entries.InteractionEndTrigger
import com.typewritermc.engine.paper.entry.fieldValue
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.plugin
import de.oliver.fancynpcs.api.events.NpcInteractEvent
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/**
 * A content mode that lets a builder pick a FancyNpcs NPC by clicking it in-game.
 * The clicked NPC's unique name is written straight into the field, so there is no
 * need to type identifiers or create a reference entry per NPC by hand.
 */
class SelectFancyNpcContentMode(context: ContentContext, player: Player) : ContentMode(context, player) {
    override suspend fun setup(): Result<Unit> {
        val entryId = context.entryId
            ?: return failure("No entryId found for SelectFancyNpcContentMode. This is a bug. Please report it.")
        val fieldPath = context.fieldPath
            ?: return failure("No fieldPath found for SelectFancyNpcContentMode. This is a bug. Please report it.")

        bossBar {
            title = "<green>Click a FancyNpc to select it"
            color = BossBar.Color.GREEN
        }
        exit(doubleShiftExits = true)
        +NpcSelectComponent(entryId, fieldPath)

        return ok(Unit)
    }
}

private class NpcSelectComponent(
    private val entryId: String,
    private val fieldPath: String,
) : ContentComponent, Listener {
    private var playerId: UUID? = null

    override suspend fun initialize(player: Player) {
        playerId = player.uniqueId
        plugin.registerEvents(this)
    }

    @EventHandler
    private fun onInteract(event: NpcInteractEvent) {
        if (event.player.uniqueId != playerId) return
        // Don't run the NPC's own click actions while the builder is just picking it.
        event.isCancelled = true

        val identifier = event.npc.data.name
        Ref(entryId, Entry::class).fieldValue(fieldPath, identifier, String::class.java)
        InteractionEndTrigger.triggerFor(event.player, context())
    }

    override suspend fun tick(player: Player) {}

    override suspend fun dispose(player: Player) {
        unregister()
    }
}
