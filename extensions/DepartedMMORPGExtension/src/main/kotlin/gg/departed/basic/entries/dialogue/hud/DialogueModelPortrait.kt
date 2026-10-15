package gg.departed.basic.entries.dialogue.hud

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import com.ticxo.modelengine.api.ModelEngineAPI
import com.ticxo.modelengine.api.animation.BlueprintAnimation
import com.ticxo.modelengine.api.entity.Dummy
import com.ticxo.modelengine.api.model.ActiveModel
import com.ticxo.modelengine.api.model.ModeledEntity
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes
import com.ticxo.modelengine.api.model.bone.ModelBone
import com.typewritermc.engine.paper.plugin
import dev.departed.departednpc.DepartedNpcPlugin
import dev.departed.departednpc.model.NpcType
import dev.departed.departednpc.model.SkinSource
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Owns an independent, viewer-only ModelEngine clone without changing the NPC's shared model. */
internal class DialogueModelPortrait(private val player: Player) {
    @Volatile
    private var talking = false
    @Volatile
    private var disposed = false
    private var dummy: Dummy<Any>? = null
    private var modeled: ModeledEntity? = null
    private var updateTask: BukkitTask? = null
    private var worldId: UUID? = null
    private var elapsedTicks = 0L
    private var yaw = 0f

    /** Resolve the existing blueprint and create a private preview. Call on the Bukkit main thread. */
    fun show(npcId: String?, facingDegrees: Double = DialogueFacing.RIGHT): Boolean {
        check(Bukkit.isPrimaryThread()) { "Dialogue model portraits must be shown on the Bukkit main thread" }
        if (disposed || !player.isOnline || npcId.isNullOrBlank()) return false
        if (!Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return false
        val npcPlugin = Bukkit.getPluginManager().getPlugin("DepartedNPC") as? DepartedNpcPlugin ?: return false
        val definition = npcPlugin.npcManager().get(npcId)?.def() ?: return false
        if (definition.type() != NpcType.MODEL_ENGINE) return false
        val modelId = definition.modelId()?.takeIf(String::isNotBlank) ?: return false
        val blueprint = ModelEngineAPI.getBlueprint(modelId) ?: return false
        val hitbox = blueprint.mainHitbox
        // The shader views the model turned SHADER_YAW; turning the dummy by -(facing + SHADER_YAW) nets out to facing.
        val facing = Math.toRadians(facingDegrees)
        yaw = Math.toDegrees(-facing - SHADER_YAW).toFloat()
        val projectedWidth = abs(cos(facing)) * hitbox.width + abs(sin(facing)) *
            (abs(cos(0.10)) * hitbox.depth + abs(sin(0.10)) * hitbox.height)
        val projectedHeight = abs(cos(0.10)) * hitbox.height + abs(sin(0.10)) * hitbox.depth
        val extent = maxOf(projectedWidth, projectedHeight, hitbox.depth, 0.25)
        val scale = 1.8 / extent
        val centerY = (hitbox.height * scale * 0.5).toFloat()

        DialoguePortraitLifecycle.register(this, player.uniqueId, ::dispose)
        owners.put(player.uniqueId, this)?.takeIf { it !== this }?.dispose()
        removeModel()
        worldId = player.world.uid
        return runCatching {
            val base = Dummy<Any>().apply {
                setDetectingPlayers(false)
                setForcedAlive(true)
                setVisible(false)
                syncLocation(anchor())
                data.setBackCull(false)
                data.setVerticalCull(false)
                data.setBlockedCull(false)
                setForceViewing(player, true)
            }
            dummy = base
            val clone = ModelEngineAPI.createModeledEntity(base)
            modeled = clone
            clone.setSaved(false)
            clone.setBaseEntityVisible(false)
            val model = ModelEngineAPI.createActiveModel(
                blueprint,
                { active -> DialogueModelRenderer(active, centerY) },
                { active -> DialoguePortraitAnimationHandler(active) },
            )
            model.setScale(scale)
            model.setCanHurt(false)
            model.setHitboxVisible(false)
            model.setShadowVisible(false)
            model.setRenderFire(false)
            clone.addModel(model, false)
            check(clone.getModel(blueprint.name).orElse(null) === model) { "Model preview attachment was cancelled" }
            definition.skin()?.let { skin ->
                applyPlayerLimbTexture(model, skin.source(), skin.texture(), skin.signature())
            }
            val animation = definition.modelAnimation()?.takeIf(String::isNotBlank)
            if (animation != null) {
                model.animationHandler.playAnimation(animation, 0.0, 0.0, 1.0, true)?.apply {
                    forceLoopMode = BlueprintAnimation.LoopMode.getOrNull(definition.modelAnimationLoop())
                        ?: BlueprintAnimation.LoopMode.HOLD
                    forceOverride = BlueprintAnimation.OverrideMode.OVERRIDE
                }
            }
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

    private fun update() {
        if (disposed || !player.isOnline || player.world.uid != worldId || owners[player.uniqueId] !== this ||
            !Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
            dispose()
            return
        }
        val base = dummy ?: return
        base.syncLocation(anchor())
        base.setXHeadRot(if (talking) (sin(elapsedTicks++ * 0.32) * 3.0).toFloat() else 0f)
    }

    private fun anchor(): Location = player.eyeLocation.clone().also {
        it.yaw = yaw
        it.pitch = 0f
    }

    private fun removeModel() {
        updateTask?.cancel()
        updateTask = null
        val ownedModel = modeled
        val ownedDummy = dummy
        modeled = null
        dummy = null
        if (ownedModel != null) {
            runCatching { ownedModel.destroy() }
            if (ownedDummy != null) runCatching { ModelEngineAPI.removeModeledEntity(ownedDummy.uuid) }
        }
        ownedDummy?.setRemoved(true)
    }

    private fun closeResources() {
        removeModel()
        DialoguePortraitLifecycle.unregister(this)
    }

    private fun applyPlayerLimbTexture(model: ActiveModel, source: SkinSource, texture: String?, signature: String?) {
        val profile: PlayerProfile = if (source == SkinSource.MIRROR) player.playerProfile.clone()
        else {
            if (texture.isNullOrBlank()) return
            Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.toByteArray(StandardCharsets.UTF_8)), "DialogueModel")
                .apply { setProperty(ProfileProperty("textures", texture, signature)) }
        }
        fun apply(bone: ModelBone) {
            bone.getBoneBehavior(BoneBehaviorTypes.PLAYER_LIMB).ifPresent { it.setTexture(profile) }
            bone.children.values.forEach(::apply)
        }
        model.bones.values.forEach(::apply)
    }

    private companion object {
        /** Fixed yaw in departed_dialogue_model_vsh.glsl's projection. */
        const val SHADER_YAW = -0.23
        val owners = ConcurrentHashMap<UUID, DialogueModelPortrait>()
    }
}
