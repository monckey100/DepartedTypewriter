package gg.departed.basic.entries.zone

import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.World
import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned, block-aligned cube described by two opposite corners.
 *
 * Corners are resolved (and stored) at the block level so membership tests and iteration are cheap
 * and deterministic. All positions produced are block-aligned (integer coordinates, no rotation).
 */
data class BlockCuboid(
    val world: World,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
) {
    companion object {
        fun of(corner1: Position, corner2: Position): BlockCuboid = BlockCuboid(
            corner1.world,
            min(corner1.blockX, corner2.blockX), min(corner1.blockY, corner2.blockY), min(corner1.blockZ, corner2.blockZ),
            max(corner1.blockX, corner2.blockX), max(corner1.blockY, corner2.blockY), max(corner1.blockZ, corner2.blockZ),
        )
    }

    fun contains(position: Position): Boolean {
        if (position.world != world) return false
        return position.blockX in minX..maxX &&
                position.blockY in minY..maxY &&
                position.blockZ in minZ..maxZ
    }

    /** Every block-aligned position inside the cube. */
    fun blockPositions(): List<Position> {
        val positions = ArrayList<Position>((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1))
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            positions += Position(world, x.toDouble(), y.toDouble(), z.toDouble())
        }
        return positions
    }
}
