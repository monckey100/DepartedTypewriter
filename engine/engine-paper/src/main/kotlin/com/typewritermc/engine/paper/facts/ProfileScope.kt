package com.typewritermc.engine.paper.facts

import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Resolves the *character* scope for facts that don't declare an explicit group.
 *
 * DepartedProfiles gives every character slot its own profile UUID (slot 1's
 * profile UUID equals the account UUID, so existing data keeps working). By
 * scoping un-grouped facts to the active profile UUID, quest progress is tracked
 * per character instead of per account.
 *
 * Facts that should be shared across all of an account's characters opt out by
 * declaring an explicit account-wide group (e.g. the `player_group` entry, which
 * keys on the account UUID) — those quests are the "account quests".
 *
 * Resolution goes through the `%departedprofiles_uuid%` placeholder so the engine
 * stays decoupled from DepartedProfiles. If the placeholder (or DepartedProfiles)
 * is unavailable, this falls back to the account UUID, preserving legacy behaviour.
 */
object ProfileScope {
    private const val PROFILE_UUID_PLACEHOLDER = "%departedprofiles_uuid%"

    /** The group a player's un-grouped facts belong to: their active character. */
    fun groupId(player: Player): GroupId = GroupId(resolveProfileUuid(player))

    /**
     * Reverse of [groupId]: find the online player whose active character matches
     * [groupId]. Falls back to treating the id as a raw account UUID (slot 1 and
     * the legacy no-profile case).
     */
    fun playerFor(groupId: GroupId): Player? {
        server.onlinePlayers.firstOrNull { resolveProfileUuid(it) == groupId.id }?.let { return it }
        return runCatching { UUID.fromString(groupId.id) }.getOrNull()?.let { server.getPlayer(it) }
    }

    private fun resolveProfileUuid(player: Player): String {
        val resolved = runCatching { PROFILE_UUID_PLACEHOLDER.parsePlaceholders(player).trim() }.getOrNull()
        if (resolved.isNullOrEmpty() || resolved == PROFILE_UUID_PLACEHOLDER) {
            return player.uniqueId.toString()
        }
        return resolved
    }
}
