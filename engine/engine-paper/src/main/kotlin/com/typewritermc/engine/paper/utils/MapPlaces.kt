package com.typewritermc.engine.paper.utils

import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.World
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Where the coordinates of a map stand for one player.
 *
 * A plugin that plays a map in copies placed elsewhere (a dungeon run) registers one with the Bukkit
 * ServicesManager. Positions a page writes as "in the player's world" (the player world position variable, a
 * recorded entity cinematic) are coordinates of the map the player is on: through this they land in the player's
 * copy, and a page keeps the map's own coordinates. Called from any thread.
 */
interface MapPlaces {
    /** The place of a map coordinate for [player], or null when the player plays no copied map. */
    fun place(player: Player, map: Location): Location?
}

/** [position] (a coordinate of the map the player is on) as the place it stands for this player. */
fun Player.mapPlace(position: Position): Position {
    val places = Bukkit.getServicesManager().load(MapPlaces::class.java) ?: return position
    val map = Location(null, position.x, position.y, position.z, position.yaw, position.pitch)
    val placed = places.place(this, map) ?: return position
    return Position(World(placed.world.uid.toString()), placed.x, placed.y, placed.z, placed.yaw, placed.pitch)
}
