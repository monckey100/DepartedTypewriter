package gg.departed.basic.entries.dialogue.hud

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a blueprint's cubes are, read from its .bbmodel: ModelEngine keeps bone pivots but not the geometry, and
 * its hitbox says nothing about where the head is. [corners] are x, y, z triples around each group's own pivot in
 * ModelEngine bone space (blocks, Blockbench x and z negated), so a live bone matrix places them in the pose the
 * model is actually in. Groups are matched to bones by the uuid ModelEngine copies onto its blueprint bones.
 */
internal class DialogueModelGeometry(
    val corners: Map<UUID, FloatArray>,
    /** The groups that make up the head (the shallowest group named like one, and everything under it). */
    val head: Set<UUID>,
    /** Changes whenever the file does, so framing measured on an older version is not reused. */
    val stamp: Long,
) {
    companion object {
        private val HEAD_NAME = Regex("(^|_)head\\d*($|_)")
        private const val PIXEL = 1f / 16f

        private class Cached(val file: File, val modified: Long, val length: Long, val geometry: DialogueModelGeometry?)

        private val cache = ConcurrentHashMap<String, Cached>()
        @Volatile
        private var files: Map<String, File> = emptyMap()
        @Volatile
        private var indexedAt = 0L

        /** Geometry of the blueprint ModelEngine calls [blueprintName] (its file name), or null if unreadable. */
        fun of(blueprintName: String): DialogueModelGeometry? {
            val name = blueprintName.lowercase(Locale.ENGLISH)
            val file = cache[name]?.file?.takeIf(File::isFile) ?: locate(name) ?: return null
            val modified = file.lastModified()
            val length = file.length()
            cache[name]?.takeIf { it.file == file && it.modified == modified && it.length == length }
                ?.let { return it.geometry }
            val geometry = runCatching {
                file.bufferedReader().use { parse(Gson().fromJson(it, JsonObject::class.java), modified xor (length shl 20)) }
            }.getOrElse { error ->
                plugin.logger.warning("Could not read ${file.name} for dialogue portrait framing: ${error.message}")
                null
            }
            cache[name] = Cached(file, modified, length, geometry)
            return geometry
        }

        private fun locate(name: String): File? {
            files[name]?.takeIf(File::isFile)?.let { return it }
            // A miss may be a model added since the last walk; look again, but not on every line of a dialogue.
            val now = System.currentTimeMillis()
            if (now - indexedAt < 10_000L) return null
            indexedAt = now
            val folder = Bukkit.getPluginManager().getPlugin("ModelEngine")?.dataFolder?.resolve("blueprints")
                ?: return null
            // ModelEngine names a blueprint after its file, whatever folder it sits in; the first one sorted wins.
            files = folder.walkTopDown().filter { it.isFile && it.extension.equals("bbmodel", ignoreCase = true) }
                .sortedBy { it.path }.groupBy { it.nameWithoutExtension.lowercase(Locale.ENGLISH) }
                .mapValues { it.value.first() }
            return files[name]
        }

        /** Both outliner layouts: groups nested in the outliner (Blockbench 4) or listed beside it (5). */
        fun parse(root: JsonObject, stamp: Long = 0L): DialogueModelGeometry {
            val elements = HashMap<String, JsonObject>()
            root.array("elements").forEach { element ->
                val cube = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                cube.string("uuid")?.let { elements[it] = cube }
            }
            val listed = HashMap<String, JsonObject>()
            root.array("groups").forEach { element ->
                val group = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                group.string("uuid")?.let { listed[it] = group }
            }

            val corners = HashMap<UUID, FloatArray>()
            val parents = HashMap<UUID, UUID?>()
            val heads = ArrayList<Pair<Int, UUID>>()

            fun walk(node: JsonObject, parent: UUID?, depth: Int) {
                val id = node.string("uuid") ?: return
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
                val group = listed[id] ?: node
                val origin = group.vector("origin")
                parents[uuid] = parent
                if (HEAD_NAME.containsMatchIn(group.string("name").orEmpty().lowercase(Locale.ENGLISH))) {
                    heads += depth to uuid
                }
                val points = ArrayList<Float>()
                node.array("children").forEach { child ->
                    if (child.isJsonObject) walk(child.asJsonObject, uuid, depth + 1)
                    else if (child.isJsonPrimitive) elements[child.asString]?.let { cube(it, origin, points) }
                }
                if (points.isNotEmpty()) corners[uuid] = points.toFloatArray()
            }
            root.array("outliner").forEach { if (it.isJsonObject) walk(it.asJsonObject, null, 0) }

            fun under(group: UUID, top: UUID): Boolean {
                var at: UUID? = group
                while (at != null) {
                    if (at == top) return true
                    at = parents[at]
                }
                return false
            }
            // sortedBy is stable: among equally deep candidates the one listed first in the outliner wins.
            val head = heads.sortedBy { it.first }.asSequence()
                .map { (_, top) -> corners.keys.filterTo(HashSet()) { under(it, top) } }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            return DialogueModelGeometry(corners, head, stamp)
        }

        /** The eight corners of a cube, turned about its own pivot, relative to its group's [origin]. */
        private fun cube(cube: JsonObject, origin: DoubleArray, into: MutableList<Float>) {
            if ((cube.string("type") ?: "cube") != "cube" || !cube.has("from") || !cube.has("to")) return
            val inflate = cube.get("inflate")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
            val from = cube.vector("from")
            val to = cube.vector("to")
            val pivot = cube.vector("origin")
            val turn = cube.vector("rotation").map(Math::toRadians)
            val (sx, cx) = sin(turn[0]) to cos(turn[0])
            val (sy, cy) = sin(turn[1]) to cos(turn[1])
            val (sz, cz) = sin(turn[2]) to cos(turn[2])
            for (corner in 0 until 8) {
                var x = (if (corner and 1 == 0) from[0] - inflate else to[0] + inflate) - pivot[0]
                var y = (if (corner and 2 == 0) from[1] - inflate else to[1] + inflate) - pivot[1]
                var z = (if (corner and 4 == 0) from[2] - inflate else to[2] + inflate) - pivot[2]
                // Blockbench turns in ZYX order: about x first, then y, then z.
                var t = y * cx - z * sx; z = y * sx + z * cx; y = t
                t = x * cy + z * sy; z = -x * sy + z * cy; x = t
                t = x * cz - y * sz; y = x * sz + y * cz; x = t
                into += (-(x + pivot[0] - origin[0])).toFloat() * PIXEL
                into += (y + pivot[1] - origin[1]).toFloat() * PIXEL
                into += (-(z + pivot[2] - origin[2])).toFloat() * PIXEL
            }
        }

        private fun JsonObject.array(key: String): JsonArray =
            get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.vector(key: String): DoubleArray {
            val values = array(key)
            return DoubleArray(3) { index ->
                values.takeIf { it.size() > index }?.get(index)?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
            }
        }
    }
}
