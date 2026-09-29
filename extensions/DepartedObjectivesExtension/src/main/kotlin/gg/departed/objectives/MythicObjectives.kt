package gg.departed.objectives

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Regex
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.server
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent
import net.playavalon.mythicdungeons.api.events.dungeon.DungeonStartEvent
import net.playavalon.mythicdungeons.api.events.dungeon.PlayerFinishDungeonEvent
import net.playavalon.mythicdungeons.api.parents.dungeons.AbstractDungeon
import org.bukkit.entity.Player
import org.koin.java.KoinJavaComponent.get

@Entry("kill_mythicmob_objective", "Kill MythicMobs mobs", Colors.YELLOW, "fa6-solid:skull")
class KillMythicMobObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Kill {remaining} more monsters."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("MythicMob internal name. Regex is supported. Leave empty to match any MythicMob.")
    @Regex
    val mobName: String = "",
    @Help(
        "Require a player to land the killing blow (the default — use for normal quests). " +
            "Turn OFF for dungeon/area fights where NPCs or clones may finish mobs: then every " +
            "questing player in the mob's world is credited for the kill."
    )
    @Default("true")
    val requirePlayerKiller: Boolean = true,
) : DepartedObjectiveEntry

@EntryListener(KillMythicMobObjectiveEntry::class)
fun onKillMythicMobObjective(event: MythicMobDeathEvent, query: Query<KillMythicMobObjectiveEntry>) {
    val matching = query.findWhere { matchesPattern(it.mobName, event.mobType.internalName) }.toList()
    if (matching.isEmpty()) return

    val manager = objectives()
    val directKiller = event.killer as? Player
    // increment -> setProgress filters to players actually tracking the objective's quest.
    matching.forEach { objective ->
        if (objective.requirePlayerKiller) {
            // Normal quests: only the player who landed the kill counts.
            directKiller?.let { manager.increment(it, objective) }
        } else {
            // Dungeon/area fights: credit every questing player in the mob's world,
            // so mobs finished off by NPCs/clones still count.
            val world = event.entity?.world ?: return@forEach
            server.onlinePlayers.filter { it.world == world }.forEach { manager.increment(it, objective) }
        }
    }
}

@Entry("start_mythicdungeon_objective", "Start a MythicDungeons dungeon", Colors.BLUE, "mdi:dungeon")
class StartMythicDungeonObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Start the target dungeon."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("Dungeon world name, display name, or folder name. Regex is supported. Leave empty for any dungeon.")
    @Regex
    val dungeonName: String = "",
) : DepartedObjectiveEntry

@EntryListener(StartMythicDungeonObjectiveEntry::class)
fun onStartMythicDungeonObjective(event: DungeonStartEvent, query: Query<StartMythicDungeonObjectiveEntry>) {
    query.findWhere { event.dungeon.matchesDungeon(it.dungeonName) }.forEach { objective ->
        event.players.forEach { player -> objectives().increment(player, objective) }
    }
}

@Entry("clear_mythicdungeon_objective", "Clear a MythicDungeons dungeon", Colors.BLUE, "mdi:flag-checkered")
class ClearMythicDungeonObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Clear the target dungeon."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("Dungeon world name, display name, or folder name. Regex is supported. Leave empty for any dungeon.")
    @Regex
    val dungeonName: String = "",
) : DepartedObjectiveEntry

@EntryListener(ClearMythicDungeonObjectiveEntry::class)
fun onClearMythicDungeonObjective(event: PlayerFinishDungeonEvent, query: Query<ClearMythicDungeonObjectiveEntry>) {
    val player = event.player ?: return
    query.findWhere { event.dungeon.matchesDungeon(it.dungeonName) }
        .forEach { objectives().increment(player, it) }
}

private fun AbstractDungeon?.matchesDungeon(pattern: String): Boolean {
    if (this == null) return pattern.isBlank()
    if (pattern.isBlank()) return true
    return matchesPattern(pattern, worldName)
            || matchesPattern(pattern, displayName)
            || matchesPattern(pattern, folder?.name.orEmpty())
}

private fun matchesPattern(pattern: String, value: String?): Boolean {
    val trimmed = pattern.trim()
    val actual = value.orEmpty()
    if (trimmed.isBlank()) return true
    return runCatching { Regex(trimmed, RegexOption.IGNORE_CASE).matches(actual) }
        .getOrDefault(actual.equals(trimmed, ignoreCase = true))
}

private fun objectives(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
