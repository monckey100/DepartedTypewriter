package gg.departed.basic.entries.dialogue.hud

import com.departed.models.api.DepartedModelsAPI
import com.departed.models.entity.Skins
import com.departed.models.model.CompiledModel
import com.departed.models.render.ModelInstance
import com.typewritermc.engine.paper.plugin
import dev.departed.departednpc.DepartedNpcPlugin
import dev.departed.departednpc.model.NpcType
import dev.departed.departednpc.model.SkinSource
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A viewer-only clone of a model NPC's model, projected into the dialogue HUD by the pack's shader, without changing
 * the NPC's shared model. Drawn by DepartedModels: this class links against its API, so it is only created while
 * that plugin is enabled.
 *
 * The clone is a model with no entity under it, shown to its viewer only, standing at their eyes. Only its display
 * transforms are encoded for the shader; its culling origin stays beside the viewer.
 *
 * Framing: once a tick the cubes of the model's [DialogueModelGeometry] are put through the posed bones and the
 * shader's view, so the window is aimed at what the model looks like right now (see [DialogueModelFraming]) and not
 * at its hit box ([reframe]). The model's own size does the zoom. Where the engine works out each display's
 * transform, the framed point is moved to the window's centre and the shader's marker is added ([place]).
 *
 * Speech: the head and everything hanging from it nods about the head's pivot with the skin portraits' timing.
 */
internal class DialogueModelPortrait(private val player: Player) {
    /** What [place] needs, swapped whole: it runs where the engine poses the model. */
    private class Placing(val centre: Vector3f?, val neck: Vector3f?, val revealed: Boolean)

    @Volatile
    private var talking = false
    @Volatile
    private var disposed = false
    @Volatile
    private var placing = Placing(null, null, false)
    private var copy: ModelInstance? = null
    private var updateTask: BukkitTask? = null
    private var worldId: UUID? = null
    private var yaw = 0f
    private var enlarge = 1f
    private var corners: Array<FloatArray?> = emptyArray()
    private var inHead = BooleanArray(0)
    private var headTop = -1
    private var track: DialoguePortraitTrack? = null
    private var tuning = DialoguePortraitTuning()
    private var frame: PortraitFrame? = null
    /** False until the bones have been posed at the framed size: nothing is drawn unframed. */
    private var revealed = false

    /** Resolve the NPC's model and create a private preview. Call on the Bukkit main thread. */
    fun show(npcId: String?, facingDegrees: Double = DialogueFacing.RIGHT): Boolean {
        check(Bukkit.isPrimaryThread()) { "Dialogue model portraits must be shown on the Bukkit main thread" }
        if (disposed || !player.isOnline || npcId.isNullOrBlank()) return false
        if (!Bukkit.getPluginManager().isPluginEnabled("DepartedModels")) return false
        val npcPlugin = Bukkit.getPluginManager().getPlugin("DepartedNPC") as? DepartedNpcPlugin ?: return false
        val definition = npcPlugin.npcManager().get(npcId)?.def() ?: return false
        if (definition.type() != NpcType.MODEL_ENGINE) return false
        val compiled = definition.modelId()?.takeIf(String::isNotBlank)?.let(DepartedModelsAPI::model) ?: return false
        // The shader views the model turned VIEW_YAW; turning the clone by -(facing + VIEW_YAW) nets out to facing.
        val facing = Math.toRadians(facingDegrees)
        yaw = Math.toDegrees(-facing - VIEW_YAW).toFloat()
        tuning = DialoguePortraitTuning.resolve(npcId)
        val animation = definition.modelAnimation()?.takeIf(String::isNotBlank)
        val loop = runCatching { CompiledModel.Loop.valueOf(definition.modelAnimationLoop().trim().uppercase(Locale.ROOT)) }
            .getOrDefault(CompiledModel.Loop.HOLD)
        val geometry = DialogueModelGeometry.of(compiled.id)
        index(compiled, geometry)
        // Without an animation of its own the preview idles, which loops.
        val tracked = DialoguePortraitTrack.of(
            compiled.id, geometry?.stamp ?: 0L, animation,
            animation == null || loop == CompiledModel.Loop.LOOP, facingDegrees,
        )
        track = tracked
        // Framing an earlier line already measured: pose at its scale from the first tick, so it shows at once.
        val scale = tracked.sample?.let { tuning.fit(DialogueModelFraming.frame(it, tuning.focus)) } ?: 1f
        enlarge = if (compiled.bones.any { it.limb != 0 }) 1f else SCALE

        DialoguePortraitLifecycle.register(this, player.uniqueId, ::dispose)
        owners.put(player.uniqueId, this)?.takeIf { it !== this }?.dispose()
        removeModel()
        worldId = player.world.uid
        return runCatching {
            val at = anchor()
            val made = DepartedModelsAPI.spawn(compiled.id, at, yaw) { model ->
                model.permit(player, true) // this viewer only
                model.setSize(scale)
                model.setBrightness(-1, 15, 15)
                model.setInstant(true)
                model.setMuted(true)
                model.setShadow(false)
                definition.skin()?.let { skin ->
                    val textures = if (skin.source() == SkinSource.MIRROR) Skins.of(player) else skin.texture()
                    if (!textures.isNullOrBlank()) model.setSkin(textures)
                }
                // An animation that holds or loops is the whole pose; otherwise the preview idles under it.
                if (animation != null) model.controller.play(animation, 0f, 0f, 1f, 0, true, loop)
                if ((animation == null || loop == CompiledModel.Loop.ONCE) && "idle" in compiled.animations) {
                    model.setBase("idle", 0f)
                }
                model.setDisplayTransform { bone, transform -> place(model, bone, transform) }
            } ?: error("DepartedModels has no model '${compiled.id}'")
            copy = made
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update() }, 1L, 1L)
            true
        }.getOrElse { error ->
            plugin.logger.warning("Could not create dialogue model preview for '$npcId': ${error.message}")
            owners.remove(player.uniqueId, this)
            DialoguePortraitLifecycle.unregister(this)
            removeModel()
            false
        }
    }

    fun tick(talking: Boolean) {
        this.talking = talking
    }

    fun dispose() {
        disposed = true
        owners.remove(player.uniqueId, this)
        if (Bukkit.isPrimaryThread()) closeResources()
        else if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable { closeResources() })
    }

    /** The cubes of each bone and which bones make up the head, by the engine's bone order (matched by name). */
    private fun index(compiled: CompiledModel, geometry: DialogueModelGeometry?) {
        val bones = compiled.bones
        corners = arrayOfNulls(bones.size)
        inHead = BooleanArray(bones.size)
        headTop = -1
        if (geometry == null) return
        val byName = HashMap<String, UUID>()
        geometry.names.forEach { (id, name) -> byName.putIfAbsent(name.lowercase(Locale.ENGLISH), id) }
        for (bone in bones) {
            val id = byName[bone.name.lowercase(Locale.ENGLISH)] ?: continue
            corners[bone.index] = geometry.corners[id]
            inHead[bone.index] = id in geometry.head
            if (id == geometry.headTop) headTop = bone.index
        }
        // The nod turns everything hanging from the head, cubes or not.
        if (headTop >= 0) for (bone in bones) {
            var at = bone.index
            while (at >= 0 && at != headTop) at = bones[at].parent
            if (at == headTop) inHead[bone.index] = true
        }
    }

    private fun update() {
        val model = copy
        if (disposed || !player.isOnline || player.world.uid != worldId || owners[player.uniqueId] !== this ||
            model == null || model.isRemoved || !Bukkit.getPluginManager().isPluginEnabled("DepartedModels")) {
            dispose()
            return
        }
        val at = anchor()
        model.moveTo(at.world, at.x, at.y, at.z)
        reframe(model)
    }

    /** Measures the model as it is posed now, aims the window at it and hands [place] what it needs. */
    private fun reframe(model: ModelInstance) {
        val posedAt = model.size()
        var neck: Vector3f? = null
        val turn = Math.toRadians(-model.yaw().toDouble()).toFloat()
        val view = Matrix4f().rotateY(VIEW_YAW).rotateX(VIEW_PITCH).rotateY(turn)
        val body = Extent()
        val head = Extent()
        val point = Vector3f()
        // Everything is measured at size 1, so that the size the window asks for does not feed back into the measure.
        if (posedAt > 1e-6f) model.pose { bone, x, y, z, rotation, sx, sy, sz ->
            if (bone == headTop) neck = Vector3f(x, y, z).div(posedAt).rotateY(turn)
            val cube = corners[bone] ?: return@pose
            if (!model.isBoneVisible(model.model.bones[bone].drawnBy)) return@pose
            for (at in cube.indices step 3) {
                rotation.transform(point.set(cube[at] * sx, cube[at + 1] * sy, cube[at + 2] * sz))
                view.transformPosition(point.add(x / posedAt, y / posedAt, z / posedAt))
                body.add(point)
                if (inHead[bone]) head.add(point)
            }
        }
        if (body.empty) {
            // Nothing readable (no .bbmodel, or every cube hidden): frame the model's hit box instead.
            val compiled = model.model
            val half = compiled.hitboxWidth / 2f
            val height = if (compiled.hitboxHeight > 0f) compiled.hitboxHeight else compiled.height
            for (corner in 0 until 8) {
                view.transformPosition(
                    if (corner and 1 == 0) -half else half,
                    if (corner and 2 == 0) 0f else height,
                    if (corner and 4 == 0) -half else half,
                    point,
                )
                body.add(point)
            }
        }
        val bounds = body.bounds()?.takeIf(PortraitBounds::finite)
        if (bounds != null) {
            val measured = PortraitSample(bounds, head.bounds()?.takeIf(PortraitBounds::finite))
            val target = DialogueModelFraming.frame(track?.add(measured) ?: measured, tuning.focus)
            val next = frame?.ease(target) ?: target
            frame = next
            val fit = tuning.fit(next)
            if (abs(posedAt - fit) <= fit * 0.01f) revealed = true
            // Takes effect when the bones are posed next; an eased zoom trails by that one tick.
            if (abs(posedAt - fit) > fit * 1e-5f) model.setSize(fit)
        }
        val framed = frame
        val centre = framed?.let {
            // Window offsets are in box sizes; one box is BOX_BLOCKS scaled blocks, so unscaled it is that / fit.
            // Back out of the view (the inverse of Ry * Rx) into the space the display translations are in.
            val box = DialogueModelFraming.BOX_BLOCKS / tuning.fit(it)
            Vector3f(it.x - tuning.offsetX.toFloat() * box, it.y - tuning.offsetY.toFloat() * box, it.z)
                .rotateY(-VIEW_YAW).rotateX(-VIEW_PITCH)
        }
        placing = Placing(centre, neck, revealed)
        model.markDirty()
    }

    /**
     * One display's transform (translation 0-2 in blocks from the clone's feet with its yaw in, rotation 3-6, scale
     * 7-9), where the engine poses the model: the head nods about its pivot, the framed point moves to the window's
     * centre, the model is enlarged for the shader to shrink again, and the shader's marker goes on y.
     */
    private fun place(model: ModelInstance, bone: Int, t: FloatArray) {
        val now = placing
        val size = model.size()
        val neck = now.neck
        if (neck != null && bone >= 0 && bone < inHead.size && inHead[bone]) {
            val turn = Math.toRadians(-model.yaw().toDouble()).toFloat()
            val axis = Vector3f(1f, 0f, 0f).rotateY(turn)
            val nod = Quaternionf().rotationAxis(nodAngle(), axis.x, axis.y, axis.z)
            val at = Vector3f(t[0] - neck.x * size, t[1] - neck.y * size, t[2] - neck.z * size).rotate(nod)
            t[0] = at.x + neck.x * size
            t[1] = at.y + neck.y * size
            t[2] = at.z + neck.z * size
            val turned = nod.mul(Quaternionf(t[3], t[4], t[5], t[6]), Quaternionf())
            t[3] = turned.x; t[4] = turned.y; t[5] = turned.z; t[6] = turned.w
        }
        val centre = now.centre
        val marker = if (enlarge == 1f) MARKER else SCALED_MARKER
        t[0] = (t[0] - (centre?.x ?: 0f) * size) * enlarge
        t[1] = (t[1] - (centre?.y ?: 0f) * size) * enlarge + marker
        t[2] = (t[2] - (centre?.z ?: 0f) * size) * enlarge
        // Nothing is drawn unframed.
        val grow = if (now.revealed) enlarge else 0f
        t[7] *= grow; t[8] *= grow; t[9] *= grow
    }

    /** The skin portraits' head motion: a slow sway, and three quick nods per sway while speaking. */
    private fun nodAngle(): Float {
        val phase = (System.nanoTime() / 1.0e8 % 16.0 * Math.PI / 8.0).toFloat()
        return if (talking) sin(phase * 3f) * 0.065f else sin(phase) * 0.012f
    }

    private fun anchor(): Location = player.eyeLocation.clone().also {
        it.yaw = yaw
        it.pitch = 0f
    }

    private fun removeModel() {
        updateTask?.cancel()
        updateTask = null
        copy?.let { runCatching { DepartedModelsAPI.remove(it) } }
        copy = null
    }

    private fun closeResources() {
        removeModel()
        DialoguePortraitLifecycle.unregister(this)
    }

    private class Extent {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY
        val empty get() = minX > maxX

        fun add(point: Vector3f) {
            minX = min(minX, point.x); minY = min(minY, point.y); minZ = min(minZ, point.z)
            maxX = max(maxX, point.x); maxY = max(maxY, point.y); maxZ = max(maxZ, point.z)
        }

        fun bounds(): PortraitBounds? = if (empty) null else PortraitBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * The shader's side of the portrait: these constants must match departed_dialogue_model_vsh.glsl.
     * Models without player limbs are enlarged [SCALE] times under [SCALED_MARKER]: the shader divides the
     * enlargement back out, which shrinks the third-person camera's offset from the eye anchor to nothing.
     * Player-limb models keep real scale under [MARKER] because their part index rides on 1024-block Y steps.
     */
    private companion object {
        const val MARKER = -32768f
        const val SCALED_MARKER = -2097152f
        const val SCALE = 1024f
        /** The fixed turn in departed_dialogue_model_vsh.glsl's projection: Ry(VIEW_YAW) * Rx(VIEW_PITCH). */
        const val VIEW_YAW = -0.23f
        const val VIEW_PITCH = 0.10f

        val owners = ConcurrentHashMap<UUID, DialogueModelPortrait>()
    }
}
