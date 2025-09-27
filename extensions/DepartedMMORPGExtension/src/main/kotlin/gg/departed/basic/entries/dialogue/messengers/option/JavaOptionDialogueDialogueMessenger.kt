package gg.departed.basic.entries.dialogue.messengers.option

import gg.departed.basic.entries.dialogue.Option
import gg.departed.basic.entries.dialogue.OptionContextKeys
import gg.departed.basic.entries.dialogue.OptionDialogueEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.around
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.dialogue.*
import com.typewritermc.engine.paper.entry.dialogue.MessengerState
import com.typewritermc.engine.paper.entry.entries.EventTrigger
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.chatHistory
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.plugin.java.JavaPlugin

// PacketEvents 2.x
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle

import java.time.Duration
import kotlin.math.abs
import kotlin.math.min

// -----------------------------------------------------------------------------
// Option dialogue formatting (unchanged UI, updated hint to WASD/Jump/Shift)
// -----------------------------------------------------------------------------
val optionFormat: String by snippet(
    "dialogue.option.format", """
        |<gray><st>${" ".repeat(60)}</st>
        |<white> <speaker><reset>: <text>
        |
        |<options>
        |<#5d6c78>[ <grey><white>Use</white> WASD <grey>to choose,</grey> <white>Jump/Shift</white> to select <#5d6c78>]</#5d6c78>
        |<gray><st>${" ".repeat(60)}</st>
    """.trimMargin()
)

private val selectedPrefix: String by snippet("dialogue.option.prefix.selected", "<#78ff85>>>")
private val upPrefix: String by snippet("dialogue.option.prefix.up", "<white> ↑")
private val downPrefix: String by snippet("dialogue.option.prefix.down", "<white> ↓")
private val unselectedPrefix: String by snippet("dialogue.option.prefix.unselected", "  ")

private val selectedOption: String by snippet(
    "dialogue.option.selected",
    " <prefix> <#5d6c78>[ <white><option_text> <#5d6c78>]\n"
)
private val unselectedOption: String by snippet(
    "dialogue.option.unselected",
    " <prefix> <#5d6c78>[ <grey><option_text> <#5d6c78>]\n"
)

val optionMaxLineLength: Int by snippet("dialogue.option.maxLineLength", 40)

private val inputCooldownMs: Long by snippet(
    "dialogue.option.inputCooldownMs",
    160,
    "Minimum ms between repeated menu inputs."
)

private val delayOptionShow: Int by snippet(
    "dialogue.option.delay",
    100,
    "The delay in milliseconds between each option being shown."
)

private val debugInput: Boolean by snippet("dialogue.option.debugInput", true)

class JavaOptionDialogueDialogueMessenger(
    player: Player,
    context: InteractionContext,
    entry: OptionDialogueEntry
) : DialogueMessenger<OptionDialogueEntry>(player, context, entry) {

    // --- Core state ---
    private var confirmationKeyHandler: ConfirmationKeyHandler? = null
    private val typeDuration = entry.duration.get(player)

    private var selectedIndex = 0
        set(value) {
            field = value
            this.context[entry, OptionContextKeys.SELECTED_OPTION] = value + 1 // 1-based in context
        }
    private val selected get() = usableOptions.getOrNull(selectedIndex)

    private var usableOptions: List<Option> = emptyList()
    private var speakerDisplayName = ""
    private var parsedText = ""
    private var playTime = Duration.ZERO
    private var totalDuration = Duration.ZERO
    private var completedAnimation = false

    override val eventTriggers: List<EventTrigger>
        get() = entry.eventTriggers + (selected?.eventTriggers ?: emptyList())

    override val modifiers: List<Modifier>
        get() = entry.modifiers + (selected?.modifiers ?: emptyList())

    override var animationComplete: Boolean
        get() = playTime >= totalDuration
        set(value) {
            playTime = if (!value) Duration.ZERO else totalDuration
        }

    // --- ArmorStand mount + PacketEvents input ---
    private var stand: ArmorStand? = null
    private var packetListener: PacketListenerAbstract? = null
    private val pluginRef: JavaPlugin by lazy { resolveAnyJavaPlugin() }

    private val steerDeadzone = 0.08f
    private var nextInputAtMs: Long = 0

    override fun init() {
        usableOptions = entry.options.filter { it.criteria.matches(player, context) }

        speakerDisplayName = entry.speakerDisplayName.get(player).parsePlaceholders(player)
        parsedText = entry.text.get(player).parsePlaceholders(player)

        val rawText = parsedText.stripped()
        val typingDuration = typingDurationType.totalDuration(rawText, typeDuration)
        val optionsShowingDuration = Duration.ofMillis(usableOptions.size * delayOptionShow.toLong())
        totalDuration = typingDuration + optionsShowingDuration

        super.init()
        confirmationKeyHandler = confirmationKey.handler(player) {
            confirmAndClose()
        }

        // Start mount-based control (ensure main thread for entity ops)
        startMountControl()
    }

    override fun tick(context: TickContext) {
        super.tick(context)
        val isFirst = playTime == Duration.ZERO
        playTime += context.deltaTime
        if (state != MessengerState.RUNNING) return

        var forceSend = false
        val newOptions = entry.options.filter { it.criteria.matches(player, this.context) }
        if (newOptions != usableOptions) {
            usableOptions = newOptions
            selectedIndex = 0
            forceSend = true
        }

        // No options? End immediately
        if (usableOptions.isEmpty()) {
            animationComplete = true
            state = MessengerState.FINISHED
            return
        }

        if (playTime.toTicks() % 100 > 0 && completedAnimation && !isFirst && !forceSend) {
            // throttle message updates
            return
        }
        displayMessage(playTime)
    }

    // --------------------- Display/UI helpers ---------------------
    private fun displayMessage(playTime: Duration) {
        val rawText = parsedText.stripped()

        val typePercentage =
            if (typeDuration.isZero) 1.0
            else typingDurationType.calculatePercentage(playTime, typeDuration, rawText)

        val resultingLines = rawText.limitLineLength(optionMaxLineLength).lineCount
        val text = parsedText.asPartialFormattedMini(
            typePercentage,
            minLines = resultingLines,
            padding = "",
            maxLineLength = optionMaxLineLength
        )

        val message = optionFormat.asMiniWithResolvers(
            Placeholder.parsed("speaker", speakerDisplayName),
            Placeholder.component("text", text),
            Placeholder.component("options", formatOptions(rawText)),
        )

        val component = player.chatHistory.composeDarkMessage(message)
        player.sendMessage(component)

        // Hide "Press Shift to dismount"
        player.sendActionBar(Component.text(" "))
    }

    private fun formatOptions(rawText: String): Component {
        val around = usableOptions.around(selectedIndex, 1, 2)

        val lines = mutableListOf<Component>()

        val typingDuration = typingDurationType.totalDuration(rawText, typeDuration)
        val timeAfterTyping = playTime - typingDuration
        val limitedOptions = (timeAfterTyping.toMillis() / delayOptionShow).toInt().coerceAtLeast(0)

        val maxOptions = min(4, around.size)
        val showingOptions = min(maxOptions, limitedOptions)

        completedAnimation = maxOptions == showingOptions

        for (i in 0 until showingOptions) {
            val option = around[i]
            val isSelected = selected == option

            val prefix = if (isSelected) selectedPrefix
            else if (i == 0 && selectedIndex > 1 && usableOptions.size > 4) upPrefix
            else if (i == 3 && selectedIndex < usableOptions.size - 3 && usableOptions.size > 4) downPrefix
            else unselectedPrefix

            val format = if (isSelected) selectedOption else unselectedOption
            lines += format.asMiniWithResolvers(
                Placeholder.parsed("prefix", prefix),
                Placeholder.parsed("option_text", option.text.get(player).parsePlaceholders(player))
            )
        }

        for (i in showingOptions until maxOptions) {
            lines += Component.text(" \n")
        }

        return Component.join(JoinConfiguration.noSeparators(), lines)
    }

    private fun moveSelection(delta: Int) {
        val size = usableOptions.size
        if (size <= 1) return
        var newIndex = (selectedIndex + delta) % size
        while (newIndex < 0) newIndex += size
        selectedIndex = newIndex
    }

    // --------------------- Mount control ---------------------
    private fun startMountControl() {
        if (Bukkit.isPrimaryThread()) {
            startMountControlSync()
        } else {
            Bukkit.getScheduler().runTask(pluginRef, Runnable { startMountControlSync() })
        }
    }

    private fun startMountControlSync() {
        if (stand?.isValid == true) {
            if (player.vehicle != stand) stand!!.addPassenger(player)
        } else {
            val base: Location = player.location.clone()
            val spawnLoc = base.clone().add(0.0, -0.4, 0.0)
            val asStand = player.world.spawnEntity(spawnLoc, EntityType.ARMOR_STAND) as ArmorStand
            asStand.setGravity(false)
            asStand.isVisible = false
            asStand.isInvulnerable = true
            asStand.isMarker = true
            asStand.isCollidable = false
            asStand.customName = null
            asStand.setBasePlate(false)
            asStand.setArms(false)
            asStand.setSmall(true)
            asStand.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 0.0
            asStand.addPassenger(player)
            stand = asStand
        }

        registerPacketEventsListener()
    }

    private fun stopMountControl() {
        if (Bukkit.isPrimaryThread()) {
            stopMountControlSync()
        } else {
            Bukkit.getScheduler().runTask(pluginRef, Runnable { stopMountControlSync() })
        }
    }

    private fun stopMountControlSync() {
        unregisterPacketEventsListener()

        stand?.let { s ->
            try {
                if (player.vehicle == s) player.leaveVehicle()
            } catch (_: Throwable) {}
            try {
                if (!s.isDead) s.remove()
            } catch (_: Throwable) {}
        }
        stand = null
    }

    private fun registerPacketEventsListener() {
        if (packetListener != null) return

        packetListener = object : PacketListenerAbstract(PacketListenerPriority.LOWEST) {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                val pt = event.packetType
                if (pt != PacketType.Play.Client.STEER_VEHICLE && pt != PacketType.Play.Client.PLAYER_INPUT) return

                val user = event.user ?: return
                if (user.uuid != player.uniqueId) return
                if (state != MessengerState.RUNNING) return

                val s = stand ?: return
                if (!s.isValid || player.vehicle != s) return

                val input = readPlayerInput(event)
                // Cancel so no real dismount/move happens
                event.setCancelled(true)

                handleMenuInput(input.forward, input.sideways, input.jump, input.unmount)
            }
        }

        if (Bukkit.isPrimaryThread()) {
            PacketEvents.getAPI().eventManager.registerListener(packetListener!!)
        } else {
            Bukkit.getScheduler().runTask(pluginRef, Runnable {
                PacketEvents.getAPI().eventManager.registerListener(packetListener!!)
            })
        }
    }

    private fun unregisterPacketEventsListener() {
        val listener = packetListener ?: return
        packetListener = null
        if (Bukkit.isPrimaryThread()) {
            PacketEvents.getAPI().eventManager.unregisterListener(listener)
        } else {
            Bukkit.getScheduler().runTask(pluginRef, Runnable {
                PacketEvents.getAPI().eventManager.unregisterListener(listener)
            })
        }
    }

    private data class Input(val forward: Float, val sideways: Float, val jump: Boolean, val unmount: Boolean)

    private fun readPlayerInput(event: PacketReceiveEvent): Input {
        val pt = event.packetType
        // Prefer SteerVehicle wrapper
        if (pt == PacketType.Play.Client.STEER_VEHICLE) {
            val w = WrapperPlayClientSteerVehicle(event)
            return coerceInputFromWrapper(w)
        }

        // Try WrapperPlayClientPlayerInput reflectively (not all builds include it)
        if (pt == PacketType.Play.Client.PLAYER_INPUT) {
            try {
                val cls = Class.forName("com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput")
                val ctor = cls.getConstructor(PacketReceiveEvent::class.java)
                val w = ctor.newInstance(event)
                return coerceInputFromAnyWrapper(w)
            } catch (_: Throwable) {
                // Fall back to treating it as SteerVehicle shape
                try {
                    val wAlt = WrapperPlayClientSteerVehicle(event)
                    return coerceInputFromWrapper(wAlt)
                } catch (_: Throwable) { /* fallthrough */ }
            }
        }

        return Input(0f, 0f, false, false)
    }

    // Uses typed SteerVehicle wrapper
    private fun coerceInputFromWrapper(w: WrapperPlayClientSteerVehicle): Input {
        var sideways = w.sideways
        var forward = w.forward
        var jump = w.isJump
        var unmount = w.isUnmount

        // flags fallback
        try {
            val flagsMethod = w.javaClass.getMethod("getFlags")
            val flags = (flagsMethod.invoke(w) as? Number)?.toInt()
            if (flags != null) {
                val res = deriveFromFlags(sideways, forward, flags)
                sideways = res.first; forward = res.second; jump = jump || res.third; unmount = unmount || res.fourth
            }
        } catch (_: Throwable) {
            // boolean accessors fallback
            var flags = 0
            try { if (w.javaClass.getMethod("isLeft").invoke(w) as Boolean) flags = flags or 0x01 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isRight").invoke(w) as Boolean) flags = flags or 0x02 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isForward").invoke(w) as Boolean) flags = flags or 0x04 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isBackward").invoke(w) as Boolean) flags = flags or 0x08 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isJump").invoke(w) as Boolean) flags = flags or 0x10 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isUnmount").invoke(w) as Boolean) flags = flags or 0x20 } catch (_: Throwable) {}
            if (flags != 0) {
                val res = deriveFromFlags(sideways, forward, flags)
                sideways = res.first; forward = res.second; jump = jump || res.third; unmount = unmount || res.fourth
            }
        }
        return Input(forward, sideways, jump, unmount)
    }

    // Uses reflection against any wrapper (PLAYER_INPUT or otherwise)
    private fun coerceInputFromAnyWrapper(w: Any): Input {
        var sideways = try { (w.javaClass.getMethod("getSideways").invoke(w) as Number).toFloat() } catch (_: Throwable) { 0f }
        var forward  = try { (w.javaClass.getMethod("getForward").invoke(w) as Number).toFloat() } catch (_: Throwable) { 0f }
        var jump     = try { w.javaClass.getMethod("isJump").invoke(w) as Boolean } catch (_: Throwable) { false }
        var unmount  = try { w.javaClass.getMethod("isUnmount").invoke(w) as Boolean } catch (_: Throwable) { false }

        // flags (preferred)
        try {
            val flagsMethod = try { w.javaClass.getMethod("getFlags") } catch (_: Throwable) { null }
            val flags = when {
                flagsMethod != null -> (flagsMethod.invoke(w) as? Number)?.toInt()
                else -> null
            }
            if (flags != null) {
                val res = deriveFromFlags(sideways, forward, flags)
                sideways = res.first; forward = res.second; jump = jump || res.third; unmount = unmount || res.fourth
            }
        } catch (_: Throwable) {
            // boolean directional accessors
            var flags = 0
            try { if (w.javaClass.getMethod("isLeft").invoke(w) as Boolean) flags = flags or 0x01 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isRight").invoke(w) as Boolean) flags = flags or 0x02 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isForward").invoke(w) as Boolean) flags = flags or 0x04 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isBackward").invoke(w) as Boolean) flags = flags or 0x08 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isJump").invoke(w) as Boolean) flags = flags or 0x10 } catch (_: Throwable) {}
            try { if (w.javaClass.getMethod("isUnmount").invoke(w) as Boolean) flags = flags or 0x20 } catch (_: Throwable) {}
            if (flags != 0) {
                val res = deriveFromFlags(sideways, forward, flags)
                sideways = res.first; forward = res.second; jump = jump || res.third; unmount = unmount || res.fourth
            }
        }
        return Input(forward, sideways, jump, unmount)
    }

    // Map flags to axes (fallback when floats are ~0). Bits chosen to match typical wrappers:
    // 0x01: left, 0x02: right, 0x04: forward (W), 0x08: backward (S), 0x10: jump, 0x20: unmount
    private fun deriveFromFlags(sidewaysIn: Float, forwardIn: Float, flags: Int): Quad<Float, Float, Boolean, Boolean> {
        var sideways = sidewaysIn
        var forward = forwardIn
        if (abs(sideways) < 1e-4 && abs(forward) < 1e-4) {
            if ((flags and 0x01) != 0) sideways -= 1f
            if ((flags and 0x02) != 0) sideways += 1f
            if ((flags and 0x04) != 0) forward += 1f
            if ((flags and 0x08) != 0) forward -= 1f
        }
        val jump = (flags and 0x10) != 0
        val unmount = (flags and 0x20) != 0
        return Quad(sideways, forward, jump, unmount)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun handleMenuInput(forward: Float, sideways: Float, jump: Boolean, unmount: Boolean) {
        if (debugInput && (jump || unmount || kotlin.math.abs(forward) > 0.001f || kotlin.math.abs(sideways) > 0.001f)) {
            player.sendActionBar(Component.text("WASD f=%.2f s=%.2f j=%s u=%s".format(forward, sideways, jump, unmount)))
        }
        val now = System.currentTimeMillis()

        // Confirm with Jump or Shift
        if (jump || unmount) {
            Bukkit.getScheduler().runTask(pluginRef, Runnable { confirmAndClose() })
            return
        }

        if (usableOptions.size <= 1) return
        if (now < nextInputAtMs) return

        var acted = false
        // Prefer forward/back first (W/S), then sideways (A/D)
        if (kotlin.math.abs(forward) > steerDeadzone) {
            if (forward > 0f) {      // W → Up
                moveSelection(-1); acted = true
            } else if (forward < 0f) { // S → Down
                moveSelection(+1); acted = true
            }
        } else if (kotlin.math.abs(sideways) > steerDeadzone) {
            if (sideways < 0f) {    // A → Up
                moveSelection(-1); acted = true
            } else if (sideways > 0f) { // D → Down
                moveSelection(+1); acted = true
            }
        }

        if (acted) {
            nextInputAtMs = now + inputCooldownMs
            Bukkit.getScheduler().runTask(pluginRef, Runnable { displayMessage(playTime) })
        }
    }

    private fun confirmAndClose() {
        animationComplete = true
        state = MessengerState.FINISHED
        stopMountControl()
    }

    // --------------------- Safety nets / fallback movement lock ---------------------
    @EventHandler
    private fun onPlayerJump(e: PlayerJumpEvent) {
        if (e.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val s = stand
        if (s == null || e.player.vehicle != s) return
        e.isCancelled = true
        confirmAndClose()
    }

    @EventHandler
    private fun onSneak(e: PlayerToggleSneakEvent) {
        if (e.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val s = stand
        if (s == null || e.player.vehicle != s) return
        if (!e.isSneaking) return
        e.isCancelled = true
        confirmAndClose()
    }

    @EventHandler
    private fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val s = stand
        // If for some reason we aren't mounted (plugin conflict), hard-lock position but keep look
        if (s == null || event.player.vehicle != s) {
            val from = event.from
            val to = event.to ?: return
            if (!event.hasChangedPosition()) return
            event.to = from.clone().apply {
                yaw = to.yaw
                pitch = to.pitch
            }
        }
    }

    override fun dispose() {
        super.dispose()
        confirmationKeyHandler?.dispose()
        confirmationKeyHandler = null
        stopMountControl()
    }

    // Resolve a usable JavaPlugin instance when this class isn't loaded by a plugin classloader.
    private fun resolveAnyJavaPlugin(): JavaPlugin {
        val pm = Bukkit.getPluginManager()

        // Prefer Typewriter if present (this messenger is typically invoked from it)
        pm.getPlugin("Typewriter")?.let { if (it is JavaPlugin && it.isEnabled) return it }

        // Fallback: any enabled JavaPlugin
        val firstEnabled = pm.plugins.firstOrNull { it is JavaPlugin && it.isEnabled } as? JavaPlugin
        if (firstEnabled != null) return firstEnabled

        // Last resort: throw
        throw IllegalStateException("No enabled JavaPlugin found for scheduling tasks.")
    }
}
