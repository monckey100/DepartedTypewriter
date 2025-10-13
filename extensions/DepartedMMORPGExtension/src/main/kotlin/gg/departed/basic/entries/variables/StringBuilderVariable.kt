package gg.departed.basic.entries.variables

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.exceptions.ContextDataNotFoundException
import com.typewritermc.core.extension.annotations.*
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders

@Entry(
    "departed_string_builder_variable",
    "A way to build strings from other variables",
    Colors.GREEN,
    "material-symbols:text-compare-rounded"
)
@GenericConstraint(String::class)
@VariableData(StringBuilderVariableData::class)
private class StringBuilderVariable(
    override val id: String = "",
    override val name: String = "",
    private val parts: List<StringPart> = emptyList(),
) : VariableEntry {
    override fun <T : Any> get(context: VarContext<T>): T {
        val data = context.getData<StringBuilderVariableData>()
            ?: throw ContextDataNotFoundException(context.klass, context.data, id)

        val template = data.text
        val allParts = data.parts + parts

        val player = context.player
        val ic = context.interactionContext

        // Build lines with special tokens handled
        val builtLines = template
            .lines()
            .flatMap { line ->
                var current = line
                var skipLine = false

                for (part in allParts) {
                    val token = "<${part.key}>"
                    if (!current.contains(token)) continue

                    val raw = part.value.get(player, ic)

                    // If this used var wants to skip, drop the whole line
                    if (raw.contains("<skip>")) {
                        skipLine = true
                        break
                    }

                    // Support explicit newlines in variable values
                    val withBreaks = raw.replace("<nl>", "\n")
                    current = current.replace(token, withBreaks)
                }

                if (skipLine) {
                    emptyList()
                } else {
                    // If replacements inserted newlines, split into multiple lines
                    current.split('\n')
                }
            }
            .toMutableList()

        // Remove leading blank lines (so if the first placeholder renders empty,
        // the next content starts at the top with no extra blank line)
        while (builtLines.isNotEmpty() && builtLines.first().isBlank()) {
            builtLines.removeAt(0)
        }

        val result = builtLines.joinToString("\n").parsePlaceholders(player)
        return context.cast(result)
    }

}

private data class StringBuilderVariableData(
    val parts: List<StringPart> = emptyList(),
    @Placeholder
    @MultiLine
    @Help("Use <key> to insert the part")
    val text: String = "",
)

private data class StringPart(
    @SnakeCase
    val key: String = "",
    val value: Var<String> = ConstVar(""),
)
