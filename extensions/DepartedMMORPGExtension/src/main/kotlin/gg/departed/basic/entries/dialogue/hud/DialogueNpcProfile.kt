package gg.departed.basic.entries.dialogue.hud

import com.destroystokyo.paper.profile.ProfileProperty
import com.google.gson.JsonParser
import dev.departed.departednpc.DepartedNpcPlugin
import dev.departed.departednpc.model.NpcType
import dev.departed.departednpc.model.SkinSource
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal data class DialogueNpcProfile(val item: ItemStack, val slim: Boolean) {
    companion object {
        fun resolve(player: Player, npcId: String): DialogueNpcProfile? {
            val npcPlugin = Bukkit.getPluginManager().getPlugin("DepartedNPC") as? DepartedNpcPlugin ?: return null
            val definition = npcPlugin.npcManager().get(npcId)?.def() ?: return null
            if (definition.type() != NpcType.PLAYER) return null

            val skin = definition.skin()
            val profile = if (skin.source() == SkinSource.MIRROR) {
                player.playerProfile.clone()
            } else {
                val texture = skin.texture()?.takeIf(String::isNotBlank) ?: return null
                Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.toByteArray(StandardCharsets.UTF_8)), "DialogueNPC")
                    .apply { setProperty(ProfileProperty("textures", texture, skin.signature())) }
            }
            val texture = profile.properties.firstOrNull { it.name == "textures" }?.value ?: return null
            val item = ItemStack(Material.PLAYER_HEAD)
            item.editMeta(SkullMeta::class.java) { it.playerProfile = profile }
            return DialogueNpcProfile(item, isSlim(texture))
        }

        private fun isSlim(texture: String): Boolean = runCatching {
            val json = String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8)
            JsonParser().parse(json).asJsonObject
                .getAsJsonObject("textures")?.getAsJsonObject("SKIN")
                ?.getAsJsonObject("metadata")?.get("model")?.asString == "slim"
        }.getOrDefault(false)
    }
}
