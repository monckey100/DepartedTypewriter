package gg.departed.basic.entries.interact

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes an item frame, and whatever is displayed in it, clickable by Typewriter.
 *
 * An item frame is an ENTITY, so clicking one fires `PlayerInteractEntityEvent` (right click) or
 * `EntityDamageByEntityEvent` (left click). Every interact entry in the engine listens to
 * `PlayerInteractEvent`, which is a BLOCK event and never fires for a frame. That made a note or a
 * map pinned to a wall completely inert: there was no way to hang a readable prop in the world.
 *
 * This relay converts either click on a frame into a normal [PlayerInteractEvent] at the frame's own
 * block position, so the ordinary entries work:
 *
 *  - `interact_event_entry` with the position set. This is the one to reach for: it filters on
 *    location only, so there is no material to line up.
 *  - `on_interact_with_block` also works, but its `block` field is matched against the real block at
 *    the frame's position, which for a frame hanging on a wall is `AIR`.
 *
 * The position to use is the frame's own block, not the wall behind it.
 *
 * **Cancelling.** Vanilla reacts to these clicks by rotating the displayed item, or by breaking the
 * frame and dropping its contents. If any entry that matched asks to cancel (`cancel: true` on the
 * entry), the original frame event is cancelled too, so a quest prop stays put and stays intact.
 * Frames nothing matches behave exactly as they always did.
 *
 * **WorldGuard.** In a protected region WorldGuard cancels the frame click before it reaches us
 * (`build`/`entity-item-frame-destroy` deny), which used to make a quest prop unreadable inside
 * towns. The handlers therefore run at HIGHEST without `ignoreCancelled`, so a WG-cancelled click
 * is still relayed and the quest entry still fires. The relay only ever cancels the original event,
 * never un-cancels it, so WG's protection of the frame itself — and of every frame no entry
 * matches — stays fully in force.
 */
@Singleton
class ItemFrameClickRelay : Initializable, Listener {

    // Right clicking a frame fires for both hands, and a left click can arrive as more than one
    // event. Without this, one player action would trigger the interaction twice.
    private val lastRelay = ConcurrentHashMap<UUID, Long>()

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
        lastRelay.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRightClick(event: PlayerInteractEntityEvent) {
        // Off hand fires a second identical event for the same click.
        if (event.hand != EquipmentSlot.HAND) return
        val frame = event.rightClicked as? ItemFrame ?: return
        if (relay(event.player, frame)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLeftClick(event: EntityDamageByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        val player = event.damager as? Player ?: return
        // Cancelling here is what stops the frame being broken and its contents dropping.
        if (relay(player, frame)) event.isCancelled = true
    }

    /** Dispatches the frame click as a block interaction. Returns whether the click should be cancelled. */
    private fun relay(player: Player, frame: ItemFrame): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastRelay[player.uniqueId] ?: 0L) < RELAY_COOLDOWN_MS) {
            // Still swallow the vanilla reaction for the duplicate half of a click we already
            // relayed, otherwise the item rotates anyway on the second event.
            return lastRelay.containsKey(player.uniqueId) && cancelledLast.contains(player.uniqueId)
        }
        lastRelay[player.uniqueId] = now

        val block = frame.location.block
        val interact = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            player.inventory.itemInMainHand,
            block,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )
        Bukkit.getPluginManager().callEvent(interact)

        if (interact.isCancelled) cancelledLast += player.uniqueId else cancelledLast -= player.uniqueId
        return interact.isCancelled
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastRelay.remove(event.player.uniqueId)
        cancelledLast -= event.player.uniqueId
    }

    // Whether the last relayed click was cancelled, so the duplicate event of the same click is
    // cancelled the same way.
    private val cancelledLast = ConcurrentHashMap.newKeySet<UUID>()

    companion object {
        private const val RELAY_COOLDOWN_MS = 250L
    }
}
