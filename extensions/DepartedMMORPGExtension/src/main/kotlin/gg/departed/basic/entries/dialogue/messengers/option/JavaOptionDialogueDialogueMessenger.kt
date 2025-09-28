package gg.departed.basic.entries.dialogue.messengers.option

import gg.departed.basic.entries.dialogue.Option
import gg.departed.basic.entries.dialogue.OptionContextKeys
import gg.departed.basic.entries.dialogue.OptionDialogueEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.around
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.dialogue.*
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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
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

    // --- Server-side mount + PacketEvents input ---
    private var mount: ArmorStand? = null
    private var packetListener: PacketListenerAbstract? = null
    private val hasPacketEvents by lazy {
        Bukkit.getPluginManager().getPlugin("packetevents") != null ||
                Bukkit.getPluginManager().getPlugin("PacketEvents") != null
    }

    private val steerDeadzone = 0.35f //0.35f
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

        // Start mount-based control
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

    // --------------------- Scheduler helper ---------------------
    private fun pluginHost(): JavaPlugin {
        (Bukkit.getPluginManager().getPlugin("PacketEvents") as? JavaPlugin)?.let { return it }
        (Bukkit.getPluginManager().getPlugin("packetevents") as? JavaPlugin)?.let { return it }
        Bukkit.getPluginManager().plugins.firstOrNull { it is JavaPlugin }?.let { return it as JavaPlugin }
        throw IllegalStateException("No JavaPlugin found to schedule tasks")
    }
    private fun runSync(task: () -> Unit) {
        Bukkit.getScheduler().runTask(pluginHost(), Runnable { task() })
    }

    // --------------------- Mount control ---------------------
    private fun startMountControl() {
        runSync {
            // Spawn invisible marker ArmorStand and mount the player (server-side)
            if (mount?.isValid == true) {
                if (player.vehicle != mount) mount!!.addPassenger(player)
            } else {
                val baseLoc: Location = player.location.clone()
                baseLoc.add(0.0, 0.380, 0.0) // 0.375
                val asStand = player.world.spawnEntity(baseLoc, EntityType.ARMOR_STAND) as ArmorStand
                asStand.isSilent = true
                asStand.isInvisible = true
                asStand.isInvulnerable = true
                asStand.setGravity(false)
                asStand.isMarker = true
                asStand.isCollidable = false
                asStand.customName = null
                asStand.setBasePlate(false)
                asStand.setArms(false)
                asStand.setSmall(true)
                asStand.addPassenger(player)
                mount = asStand
            }

            if (hasPacketEvents) registerPacketEventsListener() else {
                player.sendMessage(Component.text("WASD menu requires PacketEvents; Jump/Shift will still confirm."))
            }
        }
    }

    private fun stopMountControl() {
        unregisterPacketEventsListener()
        runSync {
            // dismount & remove anchor
            mount?.let { m ->
                try {
                    if (player.vehicle == m) player.leaveVehicle()
                } catch (_: Throwable) {}
                try {
                    if (!m.isDead) m.remove()
                } catch (_: Throwable) {}
            }
            mount = null
        }
    }

    private fun registerPacketEventsListener() {
        if (packetListener != null) return

        packetListener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                val type = event.packetType
                if (type != PacketType.Play.Client.STEER_VEHICLE &&
                    type != PacketType.Play.Client.PLAYER_INPUT) return

                val user = event.user ?: return
                if (user.uuid != player.uniqueId) return
                if (state != MessengerState.RUNNING) return

                val m = mount ?: return
                if (!m.isValid || player.vehicle != m) return

                // Extract inputs depending on the packet actually received
                var forward = 0.0f
                var sideways = 0.0f
                var confirm = false

                when (type) {
                    PacketType.Play.Client.STEER_VEHICLE -> {
                        val w = WrapperPlayClientSteerVehicle(event)
                        sideways = w.sideways           // A (-) … D (+)
                        forward = w.forward             // S (-) … W (+)
                        confirm = w.isJump || w.isUnmount   // SPACE or SHIFT
                    }
                    PacketType.Play.Client.PLAYER_INPUT -> {
                        val w = WrapperPlayClientPlayerInput(event)
                        if (w.isLeft) sideways = -1.0f
                        else if (w.isRight) sideways = 1.0f
                        else sideways = 0f
                        if (w.isForward) forward = 1.0f
                        else if (w.isBackward) forward = -1.0f
                        else forward = 0f
                        confirm = w.isJump || w.isShift // SPACE or SHIFT
                    }
                    else -> return
                }

                val now = System.currentTimeMillis()
                event.isCancelled = true // always cancel the packet
                // Confirm → close & clean up (cancel only the current packet)
                if (confirm) {
                    runSync { confirmAndClose() }
                    return
                }

                // WASD navigation (dominant axis), debounce repeats
                if (now >= nextInputAtMs && usableOptions.size > 1) {
                    var acted = false
                    if (kotlin.math.abs(forward) >= kotlin.math.abs(sideways)) {
                        if (forward > steerDeadzone) {      // W → Up
                            moveSelection(-1); acted = true
                        } else if (forward < -steerDeadzone) { // S → Down
                            moveSelection(+1); acted = true
                        }
                    } else {
                        if (sideways < -steerDeadzone) {    // A → Up
                            moveSelection(-1); acted = true
                        } else if (sideways > steerDeadzone) { // D → Down
                            moveSelection(+1); acted = true
                        }
                    }
                    if (acted) {
                        nextInputAtMs = now + inputCooldownMs
                        runSync { displayMessage(playTime) }
                    }
                }
            }

        }

        PacketEvents.getAPI().eventManager.registerListener(packetListener!!)
    }

    private fun unregisterPacketEventsListener() {
        packetListener?.let {
            try {
                PacketEvents.getAPI().eventManager.unregisterListener(it)
            } catch (_: Throwable) {}
        }
        packetListener = null
    }

    private fun confirmAndClose() {
        completeOrFinish()
        stopMountControl()
    }

    // --------------------- Safety nets / fallback movement lock ---------------------
    @EventHandler
    private fun onPlayerJump(e: PlayerJumpEvent) {
        if (e.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val m = mount ?: return
        if (player.vehicle != m) return
        // Use Jump as confirm; prevent actual motion
        e.isCancelled = true
        confirmAndClose()
    }

    @EventHandler
    private fun onSneak(e: PlayerToggleSneakEvent) {
        if (e.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val m = mount ?: return
        if (player.vehicle != m) return
        if (!e.isSneaking) return
        e.isCancelled = true
        confirmAndClose()
    }

    @EventHandler
    private fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (state != MessengerState.RUNNING) return
        val m = mount
        // If for some reason we aren't mounted (plugin conflict), hard-lock position but keep look
        if (m == null || event.player.vehicle != m) {
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
}
