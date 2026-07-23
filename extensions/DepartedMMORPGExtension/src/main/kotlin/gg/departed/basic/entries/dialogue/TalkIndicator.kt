package gg.departed.basic.entries.dialogue

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3f
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MaterialProperties
import com.typewritermc.core.extension.annotations.MaterialProperty
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.entries.get
import com.typewritermc.engine.paper.entry.entity.toProperty
import com.typewritermc.engine.paper.extensions.packetevents.metas
import com.typewritermc.engine.paper.utils.move
import com.typewritermc.engine.paper.utils.toPacketLocation
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.meta.EntityMeta
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class TalkIndicatorSettings(
    @Help("Whether to show a packet-only item display above the player during this dialogue.")
    @Default("true")
    val enabled: Boolean = true,
    @MaterialProperties(MaterialProperty.ITEM)
    @Help("The item material shown above the player.")
    @Default("\"PAPER\"")
    val material: Var<Material> = ConstVar(Material.PAPER),
    @Help("Whether to apply legacy custom model data to the displayed item.")
    @Default("true")
    val useCustomModelData: Boolean = true,
    @Help("The legacy custom model data value applied to the displayed item.")
    @Default("4410019")
    val customModelData: Var<Int> = ConstVar(4410019),
    @Help("How many blocks above the player's feet the item display should float.")
    @Default("2.6")
    val heightOffset: Var<Double> = ConstVar(2.6),
    @Help("The visual scale of the item display.")
    @Default("0.55")
    val scale: Var<Double> = ConstVar(0.55),
    @Help("Who receives the packet-only item display.")
    @Default("\"NEARBY_PLAYERS\"")
    val viewers: TalkIndicatorViewers = TalkIndicatorViewers.NEARBY_PLAYERS,
    @Help("Maximum distance for nearby viewers.")
    @Default("48.0")
    val viewDistance: Var<Double> = ConstVar(48.0),
    @Help("How Minecraft should render the displayed item.")
    @Default("\"FIXED\"")
    val displayType: TalkIndicatorDisplayType = TalkIndicatorDisplayType.FIXED,
)

enum class TalkIndicatorViewers {
    TALKING_PLAYER,
    NEARBY_PLAYERS,
    ALL_PLAYERS,
}

enum class TalkIndicatorDisplayType(private val type: ItemDisplayMeta.DisplayType) {
    NONE(ItemDisplayMeta.DisplayType.NONE),
    THIRD_PERSON_LEFT_HAND(ItemDisplayMeta.DisplayType.THIRD_PERSON_LEFT_HAND),
    THIRD_PERSON_RIGHT_HAND(ItemDisplayMeta.DisplayType.THIRD_PERSON_RIGHT_HAND),
    FIRST_PERSON_LEFT_HAND(ItemDisplayMeta.DisplayType.FIRST_PERSON_LEFT_HAND),
    FIRST_PERSON_RIGHT_HAND(ItemDisplayMeta.DisplayType.FIRST_PERSON_RIGHT_HAND),
    HEAD(ItemDisplayMeta.DisplayType.HEAD),
    GUI(ItemDisplayMeta.DisplayType.GUI),
    GROUND(ItemDisplayMeta.DisplayType.GROUND),
    FIXED(ItemDisplayMeta.DisplayType.FIXED);

    fun toEntityLibType(): ItemDisplayMeta.DisplayType = type
}

class TalkIndicator(
    private val player: Player,
    private val context: InteractionContext,
    private val settings: TalkIndicatorSettings,
) {
    private val entities = mutableMapOf<UUID, WrapperEntity>()
    private var itemStack: ItemStack? = null
    private var indicatorScale = 0.55

    fun init() {
        if (!settings.enabled) return

        itemStack = settings.buildItem(player, context)
        indicatorScale = settings.scale.get(player, context).coerceAtLeast(0.01)
        tick()
    }

    fun tick() {
        if (!settings.enabled || !player.isOnline) {
            dispose()
            return
        }

        val viewers = viewers().associateBy { it.uniqueId }
        entities.keys
            .filter { it !in viewers.keys }
            .forEach(::removeViewer)

        val location = player.location.clone().add(0.0, settings.heightOffset.get(player, context), 0.0)
        viewers.values.forEach { viewer ->
            val entity = entities.getOrPut(viewer.uniqueId) {
                createEntity().also {
                    it.spawn(location.toPacketLocation())
                    it.addViewer(viewer.uniqueId)
                }
            }
            entity.move(location.toProperty())
        }
    }

    fun dispose() {
        entities.keys.toList().forEach(::removeViewer)
    }

    private fun viewers(): List<Player> {
        return when (settings.viewers) {
            TalkIndicatorViewers.TALKING_PLAYER -> listOf(player)
            TalkIndicatorViewers.ALL_PLAYERS -> Bukkit.getOnlinePlayers().filter { it.world == player.world }
            TalkIndicatorViewers.NEARBY_PLAYERS -> {
                val maxDistanceSquared = settings.viewDistance.get(player, context).let { it * it }
                Bukkit.getOnlinePlayers().filter {
                    it.world == player.world && it.location.distanceSquared(player.location) <= maxDistanceSquared
                }
            }
        }
    }

    private fun createEntity(): WrapperEntity {
        val uuid = EntityLib.getPlatform().entityUuidProvider.provide(EntityTypes.ITEM_DISPLAY)
        val entityId = EntityLib.getPlatform().entityIdProvider.provide(uuid, EntityTypes.ITEM_DISPLAY)
        val metaData = EntityMeta.createMeta(entityId, EntityTypes.ITEM_DISPLAY)
        val entity = WrapperEntity(entityId, uuid, EntityTypes.ITEM_DISPLAY, metaData)

        entity.entityMeta.setNotifyAboutChanges(false)
        entity.metas {
            meta<ItemDisplayMeta> {
                item = SpigotConversionUtil.fromBukkitItemStack(itemStack ?: settings.buildItem(player, context))
                displayType = settings.displayType.toEntityLibType()
            }
        }
        entity.metas {
            meta<AbstractDisplayMeta> {
                billboardConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
                scale = Vector3f(indicatorScale.toFloat(), indicatorScale.toFloat(), indicatorScale.toFloat())
            }
        }
        entity.entityMeta.setNotifyAboutChanges(true)
        return entity
    }

    private fun removeViewer(viewerId: UUID) {
        entities.remove(viewerId)?.let { entity ->
            entity.despawn()
            entity.remove()
        }
    }
}

private fun TalkIndicatorSettings.buildItem(player: Player, context: InteractionContext): ItemStack {
    val item = ItemStack(material.get(player, context))
    if (useCustomModelData) {
        item.editMeta { meta ->
            meta.setCustomModelData(customModelData.get(player, context))
        }
    }
    return item
}
