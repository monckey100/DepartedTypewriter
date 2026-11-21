package gg.departed.dungeons

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.temporal.CinematicPlayback
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.MapPlaces
import dev.departed.dungeons.api.DungeonPlaces
import dev.departed.dungeons.api.DungeonPosition
import dev.departed.dungeons.api.DungeonPresentation
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * Links Typewriter to DepartedDungeons: map coordinates of a page land in the player's run copy, and a run's scene
 * step plays an ordinary cinematic page.
 */
@Singleton
class DungeonBridge : Initializable {
    private val places = RunPlaces()
    private val scenes = RunScenes()

    override suspend fun initialize() {
        Bukkit.getServicesManager().register(MapPlaces::class.java, places, plugin, ServicePriority.Normal)
        Bukkit.getServicesManager().register(DungeonPresentation::class.java, scenes, plugin, ServicePriority.Normal)
    }

    override suspend fun shutdown() {
        Bukkit.getServicesManager().unregister(MapPlaces::class.java, places)
        Bukkit.getServicesManager().unregister(DungeonPresentation::class.java, scenes)
    }
}

/** Every map coordinate goes through the dungeon plugin's one transform. */
private class RunPlaces : MapPlaces {
    override fun place(player: Player, map: Location): Location? {
        val dungeons = Bukkit.getServicesManager().load(DungeonPlaces::class.java) ?: return null
        val pose = DungeonPosition.Pose(map.x, map.y, map.z, map.yaw, map.pitch)
        return dungeons.placeFor(player, pose).orElse(null)
    }
}

/** A scene is the cinematic page of that id, played for the participant like any page. */
private class RunScenes : DungeonPresentation {
    private data class Playing(val player: UUID, val page: String)

    private val playing = ConcurrentHashMap<UUID, Playing>()

    override fun ready(): Boolean = CinematicPlayback.ready()

    override fun hasScene(sceneId: String): Boolean = CinematicPlayback.isCinematic(sceneId)

    override fun play(request: DungeonPresentation.Request): CompletionStage<DungeonPresentation.Outcome> {
        val execution = request.context().executionId()
        val page = request.context().sceneId()
        playing[execution] = Playing(request.player().uniqueId, page)
        return CinematicPlayback.play(request.player(), page)
            .thenApply { completed ->
                if (completed) DungeonPresentation.Outcome.COMPLETED else DungeonPresentation.Outcome.CANCELLED
            }
            .whenComplete { _, _ -> playing.remove(execution) }
    }

    override fun cancel(executionId: UUID) {
        val scene = playing.remove(executionId) ?: return
        val player = Bukkit.getPlayer(scene.player) ?: return
        CinematicPlayback.stop(player, scene.page)
    }
}
