package com.typewritermc.basic.itemcore

import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.typewritermc.engine.paper.interaction.InterceptionBundle

/**
 * The world-interaction half of the engine's `keepFakeInventory()`: a player in a cinematic cannot use items,
 * hit entities or dig. Used instead of `keepFakeInventory()` while a core mask hides the inventory, so Typewriter
 * never rewrites inventory packets itself (DESIGN R15).
 */
fun InterceptionBundle.blockWorldInteraction() {
    !PacketType.Play.Client.USE_ITEM
    !PacketType.Play.Client.INTERACT_ENTITY
    !PacketType.Play.Client.PLAYER_DIGGING
}
