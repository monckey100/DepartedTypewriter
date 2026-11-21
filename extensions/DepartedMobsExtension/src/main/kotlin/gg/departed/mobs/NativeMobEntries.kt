package gg.departed.mobs

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.InnerMax
import com.typewritermc.core.extension.annotations.Max
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.Regex
import com.typewritermc.core.extension.annotations.Segments
import com.typewritermc.core.extension.annotations.WithRotation
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.CinematicEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.Segment
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.temporal.SimpleCinematicAction
import com.typewritermc.engine.paper.entry.triggerAllFor
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.logger
import com.departed.models.api.DepartedModelsAPI
import com.departed.models.render.ModelInstance
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.toBukkitLocation
import dev.departed.itemcore.api.event.MobDefeatedEvent
import io.nv.bukkit.departedmobs.DepartedMobs
import io.nv.bukkit.departedmobs.runtime.paper.NativeMobs
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Entry ids that have already logged their warning, so each logs once however often it runs. */
private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

/**
 * Logs one warning for an entry type the native mob runtime has no equivalent for. Later calls with the same
 * [entryId] stay silent.
 */
private fun unsupported(entryId: String, why: String) {
    if (warned.add(entryId)) {
        logger.warning("Entry '$entryId' ran, but $why: nothing was done. The MythicMobs runtime is retired.")
    }
}

/** The native mob runtime, or null while DepartedMobs is not enabled. */
private fun natives(): NativeMobs? = DepartedMobs.get()?.nativeMobs()

/** The first account that hurt the mob, last blow first, that is online now. */
private fun MobDefeatedEvent.killer(): Player? = credited().firstNotNullOfOrNull { Bukkit.getPlayer(it) }

@Entry("on_mythic_mob_die", "When a player kill a MythicMobs mob.", Colors.YELLOW, "fa6-solid:skull")
/**
 * The `Mob Death Event` event is triggered when a player kills a mob.
 *
 * It is driven by the native mob-defeated event and triggers for the first online player who hurt the mob,
 * the one who dealt the last blow first.
 *
 * ## How could this be used?
 *
 * After killing a final boss, a dialogue or cinematic section can start. The player could also get a special reward the first time they kill a specific mob.
 */
class MythicMobDeathEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only trigger when a specific mob dies.")
    @Regex
    val mobName: String = "",
    @Help("Only trigger when the mob's level is at least this (0 = any level). The MythicMobs level is the Departed mob level.")
    val minLevel: Int = 0,
) : EventEntry

@EntryListener(MythicMobDeathEventEntry::class)
fun onMobDeath(event: MobDefeatedEvent, query: Query<MythicMobDeathEventEntry>) {
    val player = event.killer() ?: return
    val level = event.level()
    query.findWhere {
        (it.minLevel <= 0 || level >= it.minLevel) &&
            it.mobName.toRegex(RegexOption.IGNORE_CASE).matches(event.type())
    }.triggerAllFor(player, context())
}

@Entry("mythicmobs_interact_event", "MythicMob Interact Event", Colors.YELLOW, "fa6-solid:dragon")
/**
 * The `MythicMob Interact Event` was fired when a player interacted with a MythicMob. The native mob runtime has
 * no interaction event, so the entry still loads but never fires.
 */
class MythicMobInteractEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The specific MythicMob type to listen for")
    @Regex
    val mobName: String = "",
) : EventEntry

@Entry("mythicmobs_kill_player_event", "MythicMob Kill Player Event", Colors.YELLOW, "fa6-solid:skull")
/**
 * The `MythicMob Kill Player Event` was triggered when a MythicMob killed a player. The native mob runtime does
 * not report which mob killed a player, so the entry still loads but never fires.
 */
class MythicMobKillPlayerEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The type of the MythicMob that killed the player")
    @Placeholder
    @Regex
    val mythicMobType: Optional<String> = Optional.empty(),
) : EventEntry

@Entry("spawn_mythicmobs_mob", "Spawn a mob from MythicMobs", Colors.ORANGE, "fa6-solid:dragon")
/**
 * The `Spawn Mob Action` spawns a native mob of the named type at a place, at the given level (rounded down, at
 * least 1). A native mob has no entity to hide, so `onlyVisibleForPlayer` cannot be honoured: the mob is drawn to
 * everyone and the entry logs one warning when that option is set. An unknown mob name spawns nothing.
 */
class SpawnMobActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val mobName: Var<String> = ConstVar(""),
    private val level: Var<Double> = ConstVar(1.0),
    private val onlyVisibleForPlayer: Boolean = false,
    @WithRotation
    private var spawnLocation: Var<Position> = ConstVar(Position.ORIGIN),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val natives = natives() ?: return
        val typeId = mobName.get(player, context).parsePlaceholders(player)
        if (typeId !in natives.types()) return
        if (onlyVisibleForPlayer) {
            unsupported("spawn_mythicmobs_mob", "native mobs cannot be shown to one player, so this one is shown to all")
        }
        val place = spawnLocation.get(player, context).toBukkitLocation()
        val mobLevel = level.get(player, context).toInt().coerceAtLeast(1)
        Dispatchers.Sync.launch { natives.spawn(typeId, mobLevel, place) }
    }
}

@Entry("despawn_mythicmobs_mob", "Despawn a mob from MythicMobs", Colors.ORANGE, "fluent:crown-subtract-24-filled")
/**
 * The `Despawn Mob Action` removes every living native mob of the named type, in every world.
 */
class DespawnMobActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val mobName: Var<String> = ConstVar(""),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val natives = natives() ?: return
        val typeId = mobName.get(player, context).parsePlaceholders(player)
        Dispatchers.Sync.launch {
            for (world in Bukkit.getWorlds()) {
                val mobs = natives.world(world)
                mobs.mobs().filter { it.type().id() == typeId }.forEach { mobs.remove(it, "typewriter") }
            }
        }
    }
}

@Entry("execute_mythicmob_skill", "Executes a MythicMobs skill", Colors.RED, "fa6-solid:bolt-lightning")
/**
 * The `Execute Skill Action` cast a MythicMobs skill at the player. Native mobs have no skill scripts, so the
 * entry logs one warning and does nothing.
 */
class ExecuteSkillActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    val skillName: Var<String> = ConstVar(""),
) : ActionEntry {
    override fun ActionTrigger.execute() = unsupported("execute_mythicmob_skill", "native mobs have no skill scripts")
}

@Entry(
    "mythic_mob_count_fact",
    "Count the number of active Mythic Mobs of the specified type",
    Colors.PURPLE,
    "mingcute:counter-fill"
)
/**
 * A fact that is how many living native mobs of one type there are, counted over every world. Reads 0 for an
 * unknown type.
 */
class MobCountFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    val mobName: String = "",
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val natives = natives() ?: return FactData(0)
        val count = Bukkit.getWorlds().sumOf { world ->
            natives.world(world).mobs().count { it.type().id() == mobName }
        }
        return FactData(count)
    }
}

@Entry("mythicmob_cinematic", "Spawn a MythicMob during a cinematic", Colors.PURPLE, "fa6-solid:dragon")
/**
 * The `Spawn MythicMob Cinematic` cinematic entry shows a mob's model during a cinematic, to the viewer only.
 *
 * The actor is the model of the named native mob type, drawn at the type's model scale (the old actor's scale),
 * standing still at the segment's place with its `idle` animation when it has one; no living mob is made, so it neither
 * fights nor thinks. A type whose brain plays its attacks in order (an actor such as q69_famine_actor) plays the
 * animations of that list one after the other, each for its attack's length, from the segment's first frame: the old
 * actor's state timeline. It is taken away when the segment ends or the cinematic stops. An unknown type, or a type whose
 * model the engine lacks, shows nothing and logs one warning.
 */
class MythicMobCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    @Segments(Colors.PURPLE, "fa6-solid:dragon")
    val segments: List<MythicMobSegment> = emptyList(),
) : CinematicEntry {
    override fun create(player: Player): CinematicAction = MobCinematicAction(player, this)
}

data class MythicMobSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    @Placeholder
    val mobName: Var<String> = ConstVar(""),
    @WithRotation
    val location: Var<Position> = ConstVar(Position.ORIGIN),
) : Segment

class MobCinematicAction(
    private val player: Player,
    entry: MythicMobCinematicEntry,
) : SimpleCinematicAction<MythicMobSegment>() {
    override val segments: List<MythicMobSegment> = entry.segments

    private val entryId = entry.id
    private var model: ModelInstance? = null
    /** The frames from the segment's start at which the actor's next animations begin, with their names. */
    private var timeline: List<Pair<Int, String>> = emptyList()
    private var played = 0

    override suspend fun startSegment(segment: MythicMobSegment) {
        super.startSegment(segment)

        Dispatchers.Sync.switchContext {
            val natives = natives() ?: return@switchContext
            val typeId = segment.mobName.get(player).parsePlaceholders(player)
            val type = natives.types()[typeId]
            if (type == null || !DepartedModelsAPI.hasModel(type.model())) {
                unsupported(entryId, "there is no native mob '$typeId' with a model")
                return@switchContext
            }
            val place = segment.location.get(player).toBukkitLocation()
            val shown = DepartedModelsAPI.spawn(type.model(), place, place.yaw) ?: return@switchContext
            shown.restrictTo(setOf(player.uniqueId))
            if (type.modelScale() != 1.0) shown.setSize(type.modelScale().toFloat())
            if (shown.model.animations.containsKey("idle")) shown.setBase("idle", 0f)
            val attacks = type.brain().attacks()
            val steps = mutableListOf<Pair<Int, String>>()
            if (!attacks.weighted()) {
                var at = 0
                for (choice in attacks.list()) {
                    val ability = type.abilities()[choice.ability()] ?: continue
                    steps.add(at to ability.animation())
                    at += ability.length()
                }
            }
            timeline = steps
            played = 0
            model = shown
            playDue(0)
        }
    }

    override suspend fun tickSegment(segment: MythicMobSegment, frame: Int) {
        super.tickSegment(segment, frame)
        if (played >= timeline.size) return
        Dispatchers.Sync.switchContext { playDue(frame - segment.startFrame) }
    }

    /** Plays every animation of the actor's timeline whose frame has come and that has not played yet. */
    private fun playDue(sinceStart: Int) {
        val shown = model ?: return
        while (played < timeline.size && timeline[played].first <= sinceStart) {
            val name = timeline[played].second
            if (shown.model.animations.containsKey(name)) shown.controller.play(name, 0.1f, 0.1f, 1f)
            played++
        }
    }

    override suspend fun stopSegment(segment: MythicMobSegment) {
        super.stopSegment(segment)
        timeline = emptyList()

        val shown = model ?: return
        model = null
        Dispatchers.Sync.switchContext { DepartedModelsAPI.remove(shown) }
    }
}

@Entry("mythicskill_cinematic", "Trigger a MythicSkill during a cinematic", Colors.PURPLE, "fa6-solid:bolt-lightning")
/**
 * The `Mythic Skill Cinematic` cinematic entry triggered a MythicMobs skill during a cinematic. Native mobs have
 * no skill scripts, so the cinematic plays without the effect and logs one warning.
 */
class MythicSkillCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    @Segments(Colors.PURPLE, "fa6-solid:bolt-lightning")
    @InnerMax(Max(1))
    val segments: List<MythicSkillSegment> = emptyList(),
) : CinematicEntry {
    override fun create(player: Player): CinematicAction = SkillCinematicAction(this)
}

data class MythicSkillSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    val skillName: Var<String> = ConstVar(""),
) : Segment

class SkillCinematicAction(entry: MythicSkillCinematicEntry) : SimpleCinematicAction<MythicSkillSegment>() {
    override val segments: List<MythicSkillSegment> = entry.segments

    override suspend fun startSegment(segment: MythicSkillSegment) {
        super.startSegment(segment)
        unsupported("mythicskill_cinematic", "native mobs have no skill scripts")
    }
}
