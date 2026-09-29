package gg.departed.objectives

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.item.CustomItem
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.item.SerializedItem
import com.typewritermc.engine.paper.utils.item.components.ItemAmountComponent
import com.typewritermc.engine.paper.utils.server
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

private val fallbackObjective by snippet("departed.objectives.fallback", "Explore Departed.")
private val noObjective by snippet("departed.objectives.none", "None")
private const val OBJECTIVE_SCOREBOARD_LINE_WIDTH = 30
private const val OBJECTIVE_SCOREBOARD_MAX_LINES = 4

@Singleton
class DepartedObjectiveManager : Initializable {
    // In-memory only: progress is intentionally not persisted to disk.
    private val progress = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ObjectiveProgress>>()

    override suspend fun initialize() {
        server.onlinePlayers.forEach(::refreshCollectObjectives)
    }

    override suspend fun shutdown() {
        progress.clear()
    }

    fun progress(player: Player, objective: DepartedObjectiveEntry): Int {
        return progress[player.uniqueId]?.get(objective.id)?.progress ?: 0
    }

    fun isComplete(player: Player, objective: DepartedObjectiveEntry): Boolean {
        val state = progress[player.uniqueId]?.get(objective.id) ?: return false
        return state.completed || state.progress >= objective.targetAmount(player)
    }

    /**
     * Objectives only accrue progress while the player has the owning quest active.
     * Objectives without a [DepartedObjectiveEntry.questId] are not tied to a quest and always track.
     *
     * "Active" means the quest itself is WIP/TURN_IN, not that it is the quest the scoreboard is
     * currently pointed at. Gating on the tracked quest froze every other accepted quest's progress,
     * so those quests never reached TURN_IN and their hand-in dialogue option never unlocked.
     */
    fun isTracking(player: Player, objective: DepartedObjectiveEntry): Boolean {
        if (objective.questId.isBlank()) return true
        return DepartedRpgBridge.isQuestActive(player, objective.questId)
    }

    fun increment(
        player: Player,
        objective: DepartedObjectiveEntry,
        amount: Int = 1,
        context: InteractionContext? = player.contextOrEmpty(),
    ) {
        val current = progress(player, objective)
        setProgress(player, objective, current + amount, context)
    }

    fun setProgress(
        player: Player,
        objective: DepartedObjectiveEntry,
        value: Int,
        context: InteractionContext? = player.contextOrEmpty(),
    ) {
        if (!isTracking(player, objective)) return
        val target = objective.targetAmount(player, context)
        val playerProgress = progress.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val state = playerProgress.computeIfAbsent(objective.id) { ObjectiveProgress() }
        val clampedValue = min(max(0, value), target)
        syncQuestVariable(player, objective, clampedValue)
        if (state.completed) return

        state.progress = clampedValue
        if (state.progress >= target) {
            complete(player, objective, state, context)
        }
    }

    /**
     * Marks an objective complete unconditionally — no quest-active gate, no completion triggers,
     * no [DepartedObjectiveEntry.completeDepartedQuestWhenDone]. For skip paths that waive content
     * the player bypassed (e.g. the tutorial ship's kills): the scoreboard moves on to the next
     * objective, while quest completion stays with whatever normally grants it. Safe to call
     * regardless of quest status or timing, and idempotent.
     */
    fun waive(player: Player, objective: DepartedObjectiveEntry) {
        val playerProgress = progress.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val state = playerProgress.computeIfAbsent(objective.id) { ObjectiveProgress() }
        if (state.completed) return
        state.progress = objective.targetAmount(player)
        state.completed = true
    }

    fun objectiveText(player: Player): String {
        val currentQuest = DepartedRpgBridge.currentQuestId(player)
        if (currentQuest.isBlank()) {
            return ObjectiveLang.tr(player, noObjective).parsePlaceholders(player)
        }

        val objective = Query.find<DepartedObjectiveEntry>()
            .filter { it.questId.equals(currentQuest, ignoreCase = true) }
            .filterNot { isComplete(player, it) }
            .sortedWith(compareBy<DepartedObjectiveEntry> { it.order }.thenBy { it.id })
            .firstOrNull()

        if (objective != null) {
            return objective.display(player)
        }

        val departedGuidance = ObjectiveLang.tr(player, DepartedRpgBridge.questGuidance(player, currentQuest))
        if (departedGuidance.isNotBlank()) {
            return departedGuidance
        }
        return ObjectiveLang.tr(player, fallbackObjective).parsePlaceholders(player)
    }

    fun objectiveShortText(player: Player): String {
        return objectiveLines(player, maxLines = 1).firstOrNull().orEmpty()
    }

    fun objectiveLine(player: Player, line: Int?): String {
        if (line == null || line < 1) return ""
        return objectiveLines(player).getOrNull(line - 1).orEmpty()
    }

    fun objectiveLineCount(player: Player): Int {
        return objectiveLines(player).size
    }

    fun refreshCollectObjectives(player: Player) {
        Query.find<CollectItemObjectiveEntry>().forEach { objective ->
            val count = objective.countCollectItems(player)
            if (objective.progressVariable.isNotBlank() || count > progress(player, objective)) {
                setProgress(player, objective, count)
            }
        }
    }

    private fun syncQuestVariable(
        player: Player,
        objective: DepartedObjectiveEntry,
        value: Int,
    ) {
        val variable = (objective as? QuestVariableObjectiveEntry)?.progressVariable?.trim().orEmpty()
        if (variable.isBlank() || objective.questId.isBlank()) return
        server.dispatchCommand(
            server.consoleSender,
            "departedrpg:questvar set ${player.name} ${objective.questId} $variable $value",
        )
    }

    private fun complete(
        player: Player,
        objective: DepartedObjectiveEntry,
        state: ObjectiveProgress,
        interactionContext: InteractionContext?,
    ) {
        state.completed = true
        state.progress = objective.targetAmount(player, interactionContext)
        objective.eventTriggers.triggerFor(player, interactionContext ?: context())

        if (!objective.completeDepartedQuestWhenDone || objective.questId.isBlank()) {
            return
        }
        if (allObjectivesComplete(player, objective.questId)) {
            DepartedRpgBridge.completeQuest(player, objective.questId)
        }
    }

    private fun allObjectivesComplete(player: Player, questId: String): Boolean {
        val objectives = Query.find<DepartedObjectiveEntry>()
            .filter { it.questId.equals(questId, ignoreCase = true) }
            .toList()
        return objectives.isNotEmpty() && objectives.all { isComplete(player, it) }
    }

    private fun DepartedObjectiveEntry.display(player: Player): String {
        val context = player.contextOrEmpty()
        val progress = progress(player, this)
        val amount = targetAmount(player, context)
        return ObjectiveLang.tr(player, instruction.get(player, context))
            .replace("{progress}", progress.toString())
            .replace("{amount}", amount.toString())
            .replace("{remaining}", max(0, amount - progress).toString())
            .replace("{quest}", questId)
            .replace("{quest_id}", questId)
            .parsePlaceholders(player)
    }

    private fun objectiveLines(
        player: Player,
        maxLines: Int = OBJECTIVE_SCOREBOARD_MAX_LINES,
    ): List<String> {
        return objectiveText(player).wrapObjectiveText(OBJECTIVE_SCOREBOARD_LINE_WIDTH, maxLines)
    }
}

data class ObjectiveProgress(
    var progress: Int = 0,
    var completed: Boolean = false,
)

fun Player.contextOrEmpty(): InteractionContext = runCatching {
    interactionContext
}.getOrNull() ?: context()

fun countInventory(player: Player, item: Item): Int {
    return player.inventory.contents.filterNotNull().filter { item.isSameAs(player, it) }.sumOf { it.amount }
}

fun removeItems(player: Player, item: Item, amount: Int): Int {
    if (amount <= 0) return 0
    return when (item) {
        is SerializedItem -> {
            val itemStack = item.build(player).clone().apply { this.amount = amount }
            val remaining = player.inventory.removeItemAnySlot(itemStack).values.sumOf { it.amount }
            amount - remaining
        }

        is CustomItem -> removeCustomItems(player, item, amount)
    }
}

private fun removeCustomItems(player: Player, item: CustomItem, amount: Int): Int {
    val requested = item.components<ItemAmountComponent>().sumOf { it.amount.get(player) }.takeIf { it > 0 } ?: amount
    var amountLeft = min(amount, requested)
    val totalToRemove = amountLeft
    val content = player.inventory.contents

    for (slot in content.indices) {
        val slotItem = content[slot] ?: continue
        if (!item.isSameAs(player, slotItem)) continue

        val slotAmount = slotItem.amount
        if (slotAmount <= amountLeft) {
            player.inventory.clear(slot)
            amountLeft -= slotAmount
        } else {
            slotItem.amount = slotAmount - amountLeft
            player.inventory.setItem(slot, slotItem)
            amountLeft = 0
        }

        if (amountLeft == 0) break
    }
    return totalToRemove - amountLeft
}

private data class ObjectiveChunk(
    val raw: String,
    val visibleLength: Int,
    val lineBreak: Boolean = false,
)

private fun String.wrapObjectiveText(width: Int, maxLines: Int): List<String> {
    val wrapped = mutableListOf<String>()
    val current = StringBuilder()
    var currentVisibleLength = 0
    var activePrefix = ""
    val lineWidth = max(1, width)

    fun flushLine() {
        val line = current.toString().trim()
        if (line.isNotEmpty() && currentVisibleLength > 0) wrapped += line
        activePrefix = line.activeObjectiveFormatPrefix()
        current.clear()
        if (activePrefix.isNotEmpty()) current.append(activePrefix)
        currentVisibleLength = 0
    }

    fun appendChunk(chunk: ObjectiveChunk) {
        if (chunk.visibleLength == 0) {
            current.append(chunk.raw)
            return
        }

        if (chunk.visibleLength > lineWidth) {
            if (currentVisibleLength > 0) flushLine()
            chunk.raw.splitObjectiveWord(lineWidth).forEach { part ->
                if (currentVisibleLength + part.visibleLength > lineWidth) flushLine()
                current.append(part.raw)
                currentVisibleLength += part.visibleLength
                if (currentVisibleLength >= lineWidth) flushLine()
            }
            return
        }

        val separatorLength = if (currentVisibleLength > 0) 1 else 0
        if (currentVisibleLength + separatorLength + chunk.visibleLength > lineWidth) {
            flushLine()
        }

        if (currentVisibleLength > 0) {
            current.append(' ')
            currentVisibleLength++
        }
        current.append(chunk.raw)
        currentVisibleLength += chunk.visibleLength
    }

    objectiveChunks().forEach { chunk ->
        if (chunk.lineBreak) {
            flushLine()
        } else {
            appendChunk(chunk)
        }
    }
    flushLine()

    if (wrapped.size <= maxLines) return wrapped
    return wrapped.take(maxLines).toMutableList().also { lines ->
        lines[maxLines - 1] = lines[maxLines - 1].ellipsizeObjectiveLine(lineWidth)
    }
}

private fun String.objectiveChunks(): List<ObjectiveChunk> {
    val chunks = mutableListOf<ObjectiveChunk>()
    val raw = StringBuilder()
    var visibleLength = 0
    var index = 0

    fun flushChunk() {
        if (raw.isEmpty()) return
        chunks += ObjectiveChunk(raw.toString(), visibleLength)
        raw.clear()
        visibleLength = 0
    }

    while (index < length) {
        when {
            this[index] == '\r' -> index++
            this[index] == '\n' -> {
                flushChunk()
                chunks += ObjectiveChunk("", 0, lineBreak = true)
                index++
            }

            this[index].isWhitespace() -> {
                flushChunk()
                index++
            }

            legacyFormatAt(index) != null -> {
                val legacy = legacyFormatAt(index) ?: ""
                raw.append(legacy)
                index += legacy.length
            }

            miniMessageTagEnd(index) >= 0 -> {
                val end = miniMessageTagEnd(index)
                raw.append(substring(index, end + 1))
                index = end + 1
            }

            else -> {
                raw.append(this[index])
                visibleLength++
                index++
            }
        }
    }
    flushChunk()
    return chunks
}

private fun String.splitObjectiveWord(width: Int): List<ObjectiveChunk> {
    val chunks = mutableListOf<ObjectiveChunk>()
    val raw = StringBuilder()
    var visibleLength = 0
    var index = 0

    fun flushChunk() {
        if (raw.isEmpty()) return
        chunks += ObjectiveChunk(raw.toString(), visibleLength)
        raw.clear()
        visibleLength = 0
    }

    while (index < length) {
        when {
            legacyFormatAt(index) != null -> {
                val legacy = legacyFormatAt(index) ?: ""
                raw.append(legacy)
                index += legacy.length
            }

            miniMessageTagEnd(index) >= 0 -> {
                val end = miniMessageTagEnd(index)
                raw.append(substring(index, end + 1))
                index = end + 1
            }

            else -> {
                if (visibleLength >= width) flushChunk()
                raw.append(this[index])
                visibleLength++
                index++
            }
        }
    }
    flushChunk()
    return chunks
}

private fun String.ellipsizeObjectiveLine(width: Int): String {
    if (visibleObjectiveLength() <= width) return this
    val suffix = "..."
    val visibleLimit = max(0, width - suffix.length)
    val raw = StringBuilder()
    var visibleLength = 0
    var index = 0

    while (index < length && visibleLength < visibleLimit) {
        when {
            legacyFormatAt(index) != null -> {
                val legacy = legacyFormatAt(index) ?: ""
                raw.append(legacy)
                index += legacy.length
            }

            miniMessageTagEnd(index) >= 0 -> {
                val end = miniMessageTagEnd(index)
                raw.append(substring(index, end + 1))
                index = end + 1
            }

            else -> {
                raw.append(this[index])
                visibleLength++
                index++
            }
        }
    }
    return raw.toString().trim() + suffix
}

private fun String.visibleObjectiveLength(): Int {
    var visibleLength = 0
    var index = 0

    while (index < length) {
        when {
            legacyFormatAt(index) != null -> index += legacyFormatAt(index)?.length ?: 1
            miniMessageTagEnd(index) >= 0 -> index = miniMessageTagEnd(index) + 1
            else -> {
                visibleLength++
                index++
            }
        }
    }
    return visibleLength
}

private fun String.activeObjectiveFormatPrefix(): String {
    var legacyColor = ""
    val legacyFormats = linkedMapOf<Char, String>()
    val miniTags = mutableListOf<String>()
    var index = 0

    while (index < length) {
        val legacy = legacyFormatAt(index)
        if (legacy != null) {
            val code = legacy.last().lowercaseChar()
            when {
                code == 'r' -> {
                    legacyColor = ""
                    legacyFormats.clear()
                    miniTags.clear()
                }

                code == 'x' || code in "0123456789abcdef" -> {
                    legacyColor = legacy
                    legacyFormats.clear()
                }

                code in "klmno" -> legacyFormats[code] = legacy
            }
            index += legacy.length
            continue
        }

        val tagEnd = miniMessageTagEnd(index)
        if (tagEnd >= 0) {
            val rawTag = substring(index, tagEnd + 1)
            val tag = rawTag.substring(1, rawTag.length - 1)
            when {
                tag.equals("reset", ignoreCase = true) -> {
                    legacyColor = ""
                    legacyFormats.clear()
                    miniTags.clear()
                }

                tag.startsWith("/") -> {
                    val closing = tag.substring(1).substringBefore(":").lowercase()
                    val removeIndex = miniTags.indexOfLast {
                        it.substring(1, it.length - 1).substringBefore(":").lowercase() == closing
                    }
                    if (removeIndex >= 0) miniTags.removeAt(removeIndex)
                }

                else -> miniTags += rawTag
            }
            index = tagEnd + 1
            continue
        }

        index++
    }

    return buildString {
        append(legacyColor)
        legacyFormats.values.forEach(::append)
        miniTags.forEach(::append)
    }
}

private fun String.isLegacyFormatAt(index: Int): Boolean = legacyFormatAt(index) != null

private fun String.legacyFormatAt(index: Int): String? {
    if (index + 1 >= length) return null
    val marker = this[index]
    if (marker != '\u00A7' && marker != '&') return null
    val code = this[index + 1].lowercaseChar()
    if (code == 'x' && index + 13 < length) {
        val isHexSequence = (index + 2..index + 12 step 2).all { this[it] == marker } &&
            (index + 3..index + 13 step 2).all { this[it].lowercaseChar() in "0123456789abcdef" }
        if (isHexSequence) return substring(index, index + 14)
    }
    if (code in "0123456789abcdefklmnorx") return substring(index, index + 2)
    return null
}

private fun String.miniMessageTagEnd(index: Int): Int {
    if (index >= length || this[index] != '<') return -1
    val end = indexOf('>', startIndex = index + 1)
    if (end < 0 || end - index > 64) return -1
    val tag = substring(index + 1, end)
    if (tag.isBlank()) return -1
    if (tag.any { it.isWhitespace() || it == '<' || it == '>' }) return -1
    return end
}
