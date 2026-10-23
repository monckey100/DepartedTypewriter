package gg.departed.basic.entries.dialogue.bark

import gg.departed.basic.entries.dialogue.StoryLog
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
import com.typewritermc.engine.paper.entry.temporal.isPlayingTemporal
import gg.departed.basic.entries.dialogue.DialogueMountControl
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.stripped
import gg.departed.basic.entries.cinematic.subtitleMillisPerCharacter
import gg.departed.basic.entries.dialogue.DepartedLang
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
 * - Owner 2026-10-22: story is never cut. A STORY line (the default) that arrives while another story line is
 *   pending or on screen QUEUES behind it and plays when it ends, in order. An ATTACK line (`dsay p:0`, a mob's
 *   attack shout) is dropped while story is up, and replaces only another attack line. A story line replaces
 *   an attack line. A line with a HIGHER priority than the one showing (`p:2`) cuts in at once: a deliberate
 *   interruption (someone talking over someone); the queue behind it is kept.
 * - While the player is inside a Typewriter interaction (conversation or cinematic) the bark waits, and shows
 *   once they are free if it is still fresh (under [PENDING_MILLIS]); the conversation owns the action bar.
 * - Other plugins' action-bar text (kill counters, countdowns) is not lost: it is captured and drawn in its
 *   normal spot under the band for as long as the client would have shown it.
 */
object SubtitleBarks {
    private const val PENDING_MILLIS = 45_000L
    private const val FOREIGN_MILLIS = 3_000L
    private const val IDLE_RESEND_TICKS = 10

    /** Story / dialogue: the default. Replaces anything. */
    const val STORY = 1
    /** A mob's attack shout: dropped while a story line is pending or showing. */
    const val ATTACK = 0

    class Bark(val speaker: Component, val body: String, val holdMillis: Long, val priority: Int = STORY) {
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

    private const val QUEUE_LIMIT = 8

    private val barks = ConcurrentHashMap<UUID, Bark>()
    /** Story lines waiting behind the current one, oldest first. */
    private val queued = ConcurrentHashMap<UUID, java.util.ArrayDeque<Bark>>()
    private val foreign = ConcurrentHashMap<UUID, Foreign>()
    private var tick = 0

    /** @return false when the line was dropped (an attack line while story is up). */
    fun show(player: Player, speaker: Component, body: String, holdMillis: Long, priority: Int = STORY): Boolean {
        val uuid = player.uniqueId
        val bark = Bark(speaker, body, holdMillis.coerceAtLeast(500L), priority)
        val current = barks[uuid]
        val storyUp = current != null && current.priority >= STORY
        if (priority < STORY && (storyUp || queued[uuid]?.isNotEmpty() == true)) return false
        StoryLog.record(player, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(speaker), body)
        // a HIGHER priority cuts in (a deliberate interruption, e.g. `p:2` talking over someone mid-word)
        if (priority >= STORY && storyUp && priority <= current!!.priority) {
            val q = queued.computeIfAbsent(uuid) { java.util.ArrayDeque() }
            if (q.size >= QUEUE_LIMIT) q.pollFirst()
            q.addLast(bark)
            return true
        }
        barks[uuid] = bark
        return true
    }

    /** The current line is done: the next queued story line takes the band (stale ones are skipped). */
    private fun next(uuid: UUID): Boolean {
        val q = queued[uuid] ?: return false
        val now = System.currentTimeMillis()
        while (true) {
            val bark = q.pollFirst() ?: run { queued.remove(uuid); return false }
            if (now - bark.createdAt <= PENDING_MILLIS) { barks[uuid] = bark; return true }
        }
    }

    fun isActive(uuid: UUID): Boolean = barks[uuid]?.startedAt?.let { it >= 0 } == true

    fun remove(uuid: UUID) {
        barks.remove(uuid)
        queued.remove(uuid)
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
                if (now - bark.createdAt > PENDING_MILLIS) { barks.remove(uuid); next(uuid); continue }
                // Conversations (dialogue mount held) and cinematics own the action bar; wait until free.
                if (player.isPlayingTemporal() || DialogueMountControl.heldStand(player) != null) continue
                bark.startedAt = now
            }
            val elapsed = now - bark.startedAt
            val total = bark.typingMillis + bark.holdMillis
            if (elapsed >= total) {
                barks.remove(uuid)
                if (!next(uuid)) {
                    foreign.remove(uuid)
                    player.sendActionBar(Component.empty().font(Key.key("departed:dialogue/space")))
                }
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
                footer = SubtitleHudRenderer.Footer(timer = fraction),
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
 * `dsay <player> [p:<priority>] <holdSeconds> <speaker> <text...>` - show an auto-dismissing subtitle line.
 * `p:0` marks a mob's attack shout: it never cuts a story line (no `p:`, priority 1) that is still showing.
 * `speaker` is one token: `_` becomes a space, `&` codes or MiniMessage set its colour (`&4&lPólemos`,
 * `Traumatized_Villager`, `-` for narration with no name), or `[words with spaces]` (e.g. `[<caster.name>]`).
 * `text` is the rest of the line.
 */
private class DsayCommand : Command("dsay") {
    init {
        description = "Show an auto-dismissing subtitle line on the dialogue HUD band"
        usage = "/dsay <player> [p:<priority>] <holdSeconds> <speaker> <text...>"
        permission = "departed.dsay"
    }

    override fun execute(sender: CommandSender, label: String, raw: Array<out String>): Boolean {
        // optional p:<priority> right after the player
        val priority = raw.getOrNull(1)?.takeIf { it.startsWith("p:", ignoreCase = true) }?.substring(2)?.toIntOrNull()
        val args: List<String> = if (priority != null) raw.toList().filterIndexed { i, _ -> i != 1 } else raw.toList()
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
        // Speaker: one token (`_` = space), or `[several words]` for names MythicMobs expands with spaces.
        var textStart = 3
        val speakerRaw = if (args[2].startsWith("[")) {
            val end = args.indices.firstOrNull { it >= 2 && args[it].endsWith("]") } ?: (args.size - 1)
            textStart = end + 1
            args.slice(2..end).joinToString(" ").removePrefix("[").removeSuffix("]")
        } else args[2].replace('_', ' ')
        // Translate like Typewriter dialogue (DepartedLanguage dictionaries key on the authored text).
        val speaker = if (speakerRaw == "-") Component.empty() else SubtitleBarks.parseText(DepartedLang.tr(player, speakerRaw))
        val text = DepartedLang.tr(player, args.drop(textStart).joinToString(" ")).parsePlaceholders(player)
        if (text.isBlank()) {
            sender.sendMessage(usage)
            return true
        }
        val body = if (text.contains('&') || text.contains('§')) {
            // Normalise legacy colours to MiniMessage so the renderer sees one format.
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(SubtitleBarks.parseText(text))
        } else text
        SubtitleBarks.show(player, speaker, body, (hold * 1000).toLong(), priority ?: SubtitleBarks.STORY)
        return true
    }
}
