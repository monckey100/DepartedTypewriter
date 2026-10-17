package gg.departed.basic.entries.dialogue.bark

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.stripped
import gg.departed.basic.entries.cinematic.subtitleMillisPerCharacter
import gg.departed.basic.entries.dialogue.hud.SubtitleHudRenderer
import gg.departed.basic.entries.dialogue.hud.revealedHudCharacters
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-dismissing subtitle lines ("barks"): a mob or NPC line that shows on the HUD band without a mount,
 * without input, and dismisses on its own. Replaces `speak{chat=true}` chat copies and other chat dialogue.
 *
 * - The line types out at the subtitle pace, then holds for [Bark.holdMillis] with a draining timer bar.
 * - A newer bark for the same player replaces the current one.
 * - While the player is inside a Typewriter interaction (conversation or cinematic) the bark waits, and shows
 *   once they are free if it is still fresh (under [PENDING_MILLIS]); the conversation owns the action bar.
 * - Other plugins' action-bar text (kill counters, countdowns) is not lost: it is captured and drawn in its
 *   normal spot under the band for as long as the client would have shown it.
 */
object SubtitleBarks {
    private const val PENDING_MILLIS = 45_000L
    private const val FOREIGN_MILLIS = 3_000L
    private const val IDLE_RESEND_TICKS = 10

    class Bark(val speaker: Component, val body: String, val holdMillis: Long) {
        val plain: String = body.stripped()
        val characters: Int = plain.codePointCount(0, plain.length)
        val typingMillis: Long = characters.toLong() * subtitleMillisPerCharacter
        var startedAt: Long = -1
        var lastSentTick: Int = Int.MIN_VALUE
        var lastRevealed: Int = -1
        var lastFraction: Double = 1.0
        val createdAt: Long = System.currentTimeMillis()
    }

    private class Foreign(val component: Component, val at: Long)

    private val barks = ConcurrentHashMap<UUID, Bark>()
    private val foreign = ConcurrentHashMap<UUID, Foreign>()
    private var tick = 0

    fun show(player: Player, speaker: Component, body: String, holdMillis: Long) {
        barks[player.uniqueId] = Bark(speaker, body, holdMillis.coerceAtLeast(500L))
    }

    fun isActive(uuid: UUID): Boolean = barks[uuid]?.startedAt?.let { it >= 0 } == true

    fun remove(uuid: UUID) {
        barks.remove(uuid)
        foreign.remove(uuid)
    }

    internal fun captureForeign(uuid: UUID, component: Component) {
        foreign[uuid] = Foreign(component, System.currentTimeMillis())
    }

    internal fun tickAll() {
        tick++
        if (barks.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((uuid, bark) in barks) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) { remove(uuid); continue }
            if (bark.startedAt < 0) {
                if (now - bark.createdAt > PENDING_MILLIS) { remove(uuid); continue }
                // Conversations and cinematics own the action bar; wait until the player is free.
                if (player.interactionContext != null) continue
                bark.startedAt = now
            }
            val elapsed = now - bark.startedAt
            val total = bark.typingMillis + bark.holdMillis
            if (elapsed >= total) {
                remove(uuid)
                player.sendActionBar(Component.empty().font(Key.key("departed:dialogue/space")))
                continue
            }
            val complete = elapsed >= bark.typingMillis
            val revealed = if (complete) bark.characters else
                revealedHudCharacters(bark.body.asMini(), elapsed.toDouble() / bark.typingMillis.coerceAtLeast(1))
            val passthrough = foreign[uuid]?.takeIf { now - it.at < FOREIGN_MILLIS }?.component
            val fraction = if (complete) 1.0 - (elapsed - bark.typingMillis).toDouble() / bark.holdMillis else 1.0
            // Redraw every tick while the timer bar drains so it moves pixel by pixel, not in half-second steps.
            val barMoved = complete && (bark.lastFraction - fraction) * SubtitleHudRenderer.BODY_WIDTH >= 1.0
            if (complete && revealed == bark.lastRevealed && !barMoved && tick - bark.lastSentTick < IDLE_RESEND_TICKS && passthrough == null) continue
            bark.lastFraction = fraction
            player.sendActionBar(SubtitleHudRenderer.render(
                speaker = bark.speaker,
                body = bark.body.asMini(),
                revealedCharacters = revealed,
                footer = SubtitleHudRenderer.Footer.Timer(fraction),
                centered = false,
                trailing = passthrough,
            ))
            bark.lastRevealed = revealed
            bark.lastSentTick = tick
        }
    }

    /** `&`/`§` legacy codes or MiniMessage, whichever the caller wrote. */
    fun parseText(raw: String): Component =
        if (raw.contains('&') || raw.contains('§')) LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§', '&'))
        else raw.asMini()
}

/** Owns the bark ticker, the `dsay` command and the action-bar passthrough while a bark is on screen. */
@Singleton
class SubtitleBarkService : Initializable, Listener {
    private var task: BukkitTask? = null
    private var passthrough: PacketListenerAbstract? = null
    private var command: Command? = null

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { SubtitleBarks.tickAll() }, 1L, 1L)
        passthrough = object : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            override fun onPacketSend(event: PacketSendEvent) {
                val uuid = event.user?.uuid ?: return
                if (!SubtitleBarks.isActive(uuid)) return
                val component = when (event.packetType) {
                    PacketType.Play.Server.SYSTEM_CHAT_MESSAGE -> {
                        val packet = WrapperPlayServerSystemChatMessage(event)
                        if (!packet.isOverlay) return
                        packet.message
                    }
                    PacketType.Play.Server.ACTION_BAR -> WrapperPlayServerActionBar(event).actionBarText ?: return
                    else -> return
                }
                if (SubtitleHudRenderer.isDialogueHud(component)) return
                SubtitleBarks.captureForeign(uuid, component)
                event.isCancelled = true
            }
        }
        PacketEvents.getAPI().eventManager.registerListener(passthrough!!)
        command = DsayCommand().also { Bukkit.getCommandMap().register("departed", it) }
    }

    override suspend fun shutdown() {
        task?.cancel()
        task = null
        passthrough?.let { runCatching { PacketEvents.getAPI().eventManager.unregisterListener(it) } }
        passthrough = null
        command?.let { runCatching { it.unregister(Bukkit.getCommandMap()) } }
        command = null
        HandlerList.unregisterAll(this)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = SubtitleBarks.remove(event.player.uniqueId)
}

/**
 * `dsay <player> <holdSeconds> <speaker> <text...>` - show an auto-dismissing subtitle line.
 * `speaker` is one token: `_` becomes a space, `&` codes or MiniMessage set its colour (`&4&lPólemos`,
 * `Traumatized_Villager`, `-` for narration with no name). `text` is the rest of the line.
 */
private class DsayCommand : Command("dsay") {
    init {
        description = "Show an auto-dismissing subtitle line on the dialogue HUD band"
        usage = "/dsay <player> <holdSeconds> <speaker> <text...>"
        permission = "departed.dsay"
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (args.size < 4) {
            sender.sendMessage(usage)
            return true
        }
        val player = Bukkit.getPlayerExact(args[0])
        if (player == null) {
            sender.sendMessage("dsay: no online player named ${args[0]}")
            return true
        }
        val hold = args[1].toDoubleOrNull()
        if (hold == null) {
            sender.sendMessage("dsay: holdSeconds must be a number, got ${args[1]}")
            return true
        }
        val speakerRaw = args[2].replace('_', ' ')
        val speaker = if (speakerRaw == "-") Component.empty() else SubtitleBarks.parseText(speakerRaw)
        val text = args.drop(3).joinToString(" ").parsePlaceholders(player)
        val body = if (text.contains('&') || text.contains('§')) {
            // Normalise legacy colours to MiniMessage so the renderer sees one format.
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(SubtitleBarks.parseText(text))
        } else text
        SubtitleBarks.show(player, speaker, body, (hold * 1000).toLong())
        return true
    }
}
