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
import dev.departed.itemcore.api.event.MobDefeatedEvent
import dev.departed.dungeons.api.DungeonCompletedEvent
import dev.departed.dungeons.api.DungeonPlaces
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerChangedWorldEvent
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
fun onKillMythicMobObjective(event: MobDefeatedEvent, query: Query<KillMythicMobObjectiveEntry>) {
    val matching = query.findWhere { matchesPattern(it.mobName, event.type()) }.toList()
    if (matching.isEmpty()) return

    val manager = objectives()
    val directKiller = event.credited().firstNotNullOfOrNull { Bukkit.getPlayer(it) }
    // increment -> setProgress filters to players actually tracking the objective's quest.
    matching.forEach { objective ->
        if (objective.requirePlayerKiller) {
            // Normal quests: only the player who landed the kill counts.
            directKiller?.let { manager.increment(it, objective) }
        } else {
            // Dungeon/area fights: credit every questing player in the mob's world, so mobs finished
            // off by NPCs/clones still count. Every run of a dungeon map stands on the same blocks of a
            // host world: there, only the players of the mob's own run (the event names it) count.
            val world = event.at().world ?: return@forEach
            val run = event.run()
            server.onlinePlayers.filter {
                it.world == world && (run == null || places()?.runOf(it)?.orElse(null)?.id() == run)
            }.forEach { manager.increment(it, objective) }
        }
    }
}

// The entry ids keep their old names so saved pages stay valid; the dungeons are DepartedDungeons' now.
@Entry("start_mythicdungeon_objective", "Enter a dungeon", Colors.BLUE, "mdi:dungeon")
class StartMythicDungeonObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Start the target dungeon."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("Dungeon id (the DepartedDungeons definition name). Regex is supported. Leave empty for any dungeon.")
    @Regex
    val dungeonName: String = "",
) : DepartedObjectiveEntry

/** A player arrives in a run: every run plays in a copy inside a host world, so the arrival is a world change. */
@EntryListener(StartMythicDungeonObjectiveEntry::class)
fun onStartMythicDungeonObjective(event: PlayerChangedWorldEvent, query: Query<StartMythicDungeonObjectiveEntry>) {
    val run = places()?.runOf(event.player)?.orElse(null) ?: return
    query.findWhere { matchesPattern(it.dungeonName, run.dungeonId()) }
        .forEach { objectives().increment(event.player, it) }
}

@Entry("clear_mythicdungeon_objective", "Clear a dungeon", Colors.BLUE, "mdi:flag-checkered")
class ClearMythicDungeonObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Clear the target dungeon."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("Dungeon id (the DepartedDungeons definition name). Regex is supported. Leave empty for any dungeon.")
    @Regex
    val dungeonName: String = "",
) : DepartedObjectiveEntry

/** A run is completed: every player it credited (DungeonCompletedEvent) who is online counts. */
@EntryListener(ClearMythicDungeonObjectiveEntry::class)
fun onClearMythicDungeonObjective(event: DungeonCompletedEvent, query: Query<ClearMythicDungeonObjectiveEntry>) {
    val completion = event.completion
    val matching = query.findWhere { matchesPattern(it.dungeonName, completion.run().dungeonId()) }.toList()
    if (matching.isEmpty()) return
    completion.characters().keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
        matching.forEach { objectives().increment(player, it) }
    }
}

private fun places(): DungeonPlaces? = Bukkit.getServicesManager().load(DungeonPlaces::class.java)

private fun matchesPattern(pattern: String, value: String?): Boolean {
    val trimmed = pattern.trim()
    val actual = value.orEmpty()
    if (trimmed.isBlank()) return true
    return runCatching { Regex(trimmed, RegexOption.IGNORE_CASE).matches(actual) }
        .getOrDefault(actual.equals(trimmed, ignoreCase = true))
}

private fun objectives(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
