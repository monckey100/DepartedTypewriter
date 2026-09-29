package gg.departed.objectives

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.monckey100.departedrpg.quests.QuestState
import com.monckey100.departedrpg.quests.QuestStatuses
import com.typewritermc.engine.paper.utils.server
import org.bukkit.entity.Player

object DepartedRpgBridge {
    private val api: DepartedRpgAPI?
        get() = server.servicesManager.load(DepartedRpgAPI::class.java)

    fun currentQuestId(player: Player): String = api?.getCurrentQuestId(player)?.orElse("") ?: ""

    /**
     * True when the player has this quest accepted and unfinished (WIP or TURN_IN).
     *
     * Deliberately independent of [currentQuestId]: a player may carry several quests at once and
     * every one of them has to keep accruing objective progress, not just the tracked one.
     */
    fun isQuestActive(player: Player, questId: String): Boolean {
        if (questId.isBlank()) return false
        val status = api?.getQuest(player, questId)?.orElse(null)?.status() ?: return false
        return QuestStatuses.isActive(status)
    }

    fun objectiveGuidance(player: Player): String = api?.getObjectiveGuidance(player).orEmpty()

    fun questGuidance(player: Player, questId: String): String = api?.getQuestGuidance(player, questId).orEmpty()

    fun questState(player: Player, questId: String): QuestState {
        if (questId.isBlank()) return QuestState.LOCKED
        return api?.getQuestState(player, questId) ?: QuestState.LOCKED
    }

    fun completeQuest(player: Player, questId: String) {
        if (questId.isBlank()) return
        api?.completeQuest(player, questId)
    }

    // ---- Quest paths ----
    // The branch(es) a player took through a multi-path quest, stored on the quest row itself
    // (MySQL quests.path) so WIP/Completed branching no longer depends on Typewriter facts.

    fun questPath(player: Player, questId: String): List<Int> {
        if (questId.isBlank()) return emptyList()
        return api?.getQuestPath(player, questId) ?: emptyList()
    }

    fun hasQuestPath(player: Player, questId: String, path: Int): Boolean {
        if (questId.isBlank()) return false
        return api?.hasQuestPath(player, questId, path) ?: false
    }

    fun addQuestPath(player: Player, questId: String, path: Int) {
        if (questId.isBlank()) return
        api?.addQuestPath(player, questId, path)
    }

    fun clearQuestPath(player: Player, questId: String) {
        if (questId.isBlank()) return
        api?.clearQuestPath(player, questId)
    }

    /** Cached path csv split to ints — safe for per-tick criteria, no DB round trip. */
    fun cachedQuestPathValues(player: Player, questId: String): List<Int> {
        if (questId.isBlank()) return emptyList()
        val csv = api?.getQuestPathCachedCsv(player, questId) ?: return emptyList()
        if (csv.isBlank()) return emptyList()
        return csv.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    /** Range-slot write (one value per 100-wide slice); cache is primed before this returns. */
    fun setQuestPathRangeValue(player: Player, questId: String, offset: Int, span: Int, value: Int) {
        if (questId.isBlank()) return
        api?.setQuestPathRangeValue(player, questId, offset, span, value)
    }

    fun cachedQuestDataValue(player: Player, questId: String, key: String): String {
        if (questId.isBlank() || key.isBlank()) return ""
        return api?.getQuestDataCachedValue(player, questId, key).orEmpty()
    }

    // ---- Quest extra data ----
    // Persistent free-form key/value data on the quest row (kill counts, items collected, a name
    // the player chose). Wiped on a fresh run, kept on completion.

    fun questDataValue(player: Player, questId: String, key: String): String {
        if (questId.isBlank() || key.isBlank()) return ""
        return api?.getQuestDataValue(player, questId, key).orEmpty()
    }

    fun setQuestDataValue(player: Player, questId: String, key: String, value: String?) {
        if (questId.isBlank() || key.isBlank()) return
        api?.setQuestDataValue(player, questId, key, value)
    }

    fun clearQuestData(player: Player, questId: String) {
        if (questId.isBlank()) return
        api?.clearQuestData(player, questId)
    }
}
