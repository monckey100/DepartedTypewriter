package gg.departed.objectives

import com.monckey100.departedrpg.quests.QuestState
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import dev.departed.departednpc.api.DepartedNpcApi
import dev.departed.departednpc.api.DepartedNpcProvider
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.koin.java.KoinJavaComponent.get
import java.util.UUID

data class DepartedQuestStateRequirement(
    @Help("DepartedRPG quest id to check.")
    val questId: String = "",
    @Help("Quest states that satisfy this requirement.")
    @Default("""["COMPLETE"]""")
    val states: List<QuestState> = listOf(QuestState.COMPLETE),
    @Help("Invert the requirement.")
    @Default("false")
    val inverted: Boolean = false,
)

@Entry("departednpc_quest_visibility", "Show or hide a DepartedNPC by Departed quest state", Colors.YELLOW, "fa6-solid:eye")
class DepartedNpcQuestVisibilityEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("DepartedNPC id.")
    val npcIdentifier: String = "",
    @Help("DepartedRPG quest id that controls this NPC.")
    val questId: String = "",
    @Help("Quest states that make this NPC visible.")
    @Default("""["READY","REPEATABLE","CURRENT"]""")
    val visibleStates: List<QuestState> = listOf(QuestState.READY, QuestState.REPEATABLE, QuestState.CURRENT),
    @Help("Extra quest-state checks for sequential questlines.")
    val requirements: List<DepartedQuestStateRequirement> = emptyList(),
) : StaticEntry {
    /** Null while the player's quest snapshot is still loading. */
    fun isVisibleFor(player: Player): Boolean? {
        if (questId.isBlank()) return false
        val states = visibleStates.ifEmpty { listOf(QuestState.READY, QuestState.REPEATABLE, QuestState.CURRENT) }
        val state = DepartedRpgBridge.questStateCached(player, questId) ?: return null
        if (state !in states) return false
        for (requirement in requirements) {
            if (!(requirement.matchesRequirement(player) ?: return null)) return false
        }
        return true
    }
}

private fun DepartedQuestStateRequirement.matchesRequirement(player: Player): Boolean? {
    if (questId.isBlank()) return !inverted
    val state = DepartedRpgBridge.questStateCached(player, questId) ?: return null
    val matches = state in states.ifEmpty { listOf(QuestState.COMPLETE) }
    return if (inverted) !matches else matches
}

/**
 * Drives DepartedNPC quest markers and quest-state visibility natively: instead of spawning its own
 * item displays, it tells DepartedNPC which per-player marker ("?"/"!") to show and reveals/hides NPCs
 * via the transient visibility API. Polling handles proximity re-spawns and changing quest state.
 */
@Singleton
class DepartedNpcQuestRuntime : Initializable {
    private var task: BukkitTask? = null
    private val managedVisibility = HashSet<String>()

    /** Per-player NPCs whose visibility we have decided from a loaded quest snapshot. */
    private val decidedVisibility = HashMap<UUID, MutableSet<String>>()

    /** Per-player npc -> marker token we currently have set, so we only re-send on change. */
    private val shown = HashMap<UUID, MutableMap<String, String>>()

    private var ticks = 0L

    private fun objectiveManager(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)

    override suspend fun initialize() {
        task = server.scheduler.runTaskTimer(plugin, Runnable { tick() }, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS)
    }

    override suspend fun shutdown() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val api = DepartedNpcProvider.get() ?: return
        updateMarkers(api)
        updateVisibility(api)
    }

    /**
     * Marks only the NPC of each quest's *next incomplete* objective, so the "?" walks the player
     * along the questline instead of lighting up every NPC in it at once. Quests handled here must set
     * `objective-markers: true` in quests.yml, which stops DepartedRPG's QuestMarkerService from also
     * pinning a marker on the quest giver for the whole quest.
     */
    private fun updateMarkers(api: DepartedNpcApi) {
        val byQuest = Query.find<DepartedNpcObjectiveEntry>()
            .filter { it.marker.enabled && it.npcIdentifier.isNotBlank() && it.questId.isNotBlank() }
            .toList()
            .groupBy { it.questId.trim().lowercase() }

        if (byQuest.isEmpty() && shown.isEmpty()) return

        // Same hazard as DepartedRPG's QuestMarkerService: we only send on change, but DepartedNPC drops
        // its per-viewer marker state when NPCs are rebuilt (/npc reload). Periodically forget what we
        // think is shown and re-assert, so a wipe self-heals instead of persisting until relog.
        if (++ticks % REASSERT_EVERY_POLLS == 0L) {
            shown.clear()
        }

        val manager = objectiveManager()

        for (player in server.onlinePlayers) {
            // Quest snapshot still loading (join / character switch): leave this player's markers as
            // they are and try again next poll rather than guess.
            val states = HashMap<String, QuestState>()
            var loading = false
            for ((key, group) in byQuest) {
                val state = DepartedRpgBridge.questStateCached(player, group.first().questId)
                if (state == null) {
                    loading = true
                    break
                }
                states[key] = state
            }
            if (loading) continue

            val want = HashMap<String, String>()

            byQuest.forEach { (key, group) ->
                val ordered = group.sortedWith(compareBy<DepartedNpcObjectiveEntry> { it.order }.thenBy { it.id })
                val target: DepartedNpcObjectiveEntry?
                val token: String

                when (states.getValue(key)) {
                    QuestState.CURRENT -> {
                        target = ordered.firstOrNull { !manager.isComplete(player, it) }
                        token = target?.let { "PAPER:${it.marker.effectiveCurrentModelData()}" }.orEmpty()
                    }
                    in READY_STATES -> {
                        // Not started yet: point at the first step so the player knows where to begin.
                        target = ordered.firstOrNull()
                        token = target?.let { "PAPER:${it.marker.readyCustomModelData}" }.orEmpty()
                    }
                    else -> return@forEach
                }

                if (target != null && token.isNotEmpty()) {
                    want[target.npcIdentifier.trim().lowercase()] = token
                }
            }

            val current = shown.getOrPut(player.uniqueId) { HashMap() }

            want.forEach { (npcId, token) ->
                if (current[npcId] != token) api.setMarker(player, npcId, token)
            }
            current.keys.filterNot { want.containsKey(it) }.forEach { npcId ->
                api.setMarker(player, npcId, null)
            }

            current.clear()
            current.putAll(want)
        }

        // Drop tracking for players who logged off.
        shown.keys.retainAll(server.onlinePlayers.map { it.uniqueId }.toSet())
    }

    private fun updateVisibility(api: DepartedNpcApi) {
        val entries = Query.find<DepartedNpcQuestVisibilityEntry>()
            .filter { it.npcIdentifier.isNotBlank() }
            .toList()
        val byNpc = entries.groupBy { it.npcIdentifier.trim().lowercase() }

        val stale = managedVisibility - byNpc.keys
        stale.forEach { npcId ->
            server.onlinePlayers.forEach { api.setDynamicVisibility(it, npcId, null) }
        }
        managedVisibility.removeAll(stale)

        byNpc.forEach { (npcId, group) ->
            managedVisibility.add(npcId)
            server.onlinePlayers.forEach { player ->
                val decided = decidedVisibility.getOrPut(player.uniqueId) { HashSet() }
                val results = group.map { it.isVisibleFor(player) }
                if (results.any { it == null }) {
                    // Snapshot still loading. Keep an NPC we already decided as it is (the refetch is a
                    // few ms); one never decided stays hidden so a retired scene NPC can't flash in.
                    if (npcId !in decided) api.setDynamicVisibility(player, npcId, false)
                    return@forEach
                }
                decided.add(npcId)
                api.setDynamicVisibility(player, npcId, results.any { it == true })
            }
        }

        // Drop tracking for players who logged off, and for NPCs no longer managed.
        decidedVisibility.keys.retainAll(server.onlinePlayers.map { it.uniqueId }.toSet())
        decidedVisibility.values.forEach { it.retainAll(managedVisibility) }
    }

    private companion object {
        const val POLL_INTERVAL_TICKS = 10L

        /** Re-assert every marker this often (in polls; 20 polls x 10 ticks = 10s), so losses recover. */
        const val REASSERT_EVERY_POLLS = 20L
        val READY_STATES = setOf(QuestState.READY, QuestState.REPEATABLE)
    }
}
