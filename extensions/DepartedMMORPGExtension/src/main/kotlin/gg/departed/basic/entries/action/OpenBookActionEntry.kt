package gg.departed.basic.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.asMini
import kotlinx.coroutines.Dispatchers
import net.kyori.adventure.inventory.Book

@Entry("open_book", "Open a read-only book for the player", Colors.RED, "mdi:book-open-page-variant")
/**
 * Opens a written-book screen for the player without giving them an item (Paper `Player.openBook`).
 * Read-only lore such as the King's castle library: nothing lands in the inventory, nothing can be copied.
 *
 * Each page is MiniMessage (placeholders allowed). A page holds roughly 14 lines of ~19 characters.
 */
class OpenBookActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Colored
    @Placeholder
    val title: String = "",
    @Placeholder
    val author: String = "",
    @Colored
    @Placeholder
    @MultiLine
    @Help("One entry per page, MiniMessage.")
    val pages: List<String> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        if (pages.isEmpty()) return
        val book = Book.book(
            title.parsePlaceholders(player).asMini(),
            author.parsePlaceholders(player).asMini(),
            pages.map { it.parsePlaceholders(player).asMini() },
        )
        Dispatchers.Sync.launch { player.openBook(book) }
    }
}
