package com.typewritermc.verification

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.processors.fullName

private val regex = Regex("^[a-z0-9_]+$")

class TagsValidator : SymbolProcessor {
    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val entries = resolver.getSymbolsWithAnnotation(Tags::class.qualifiedName!!)
        val invalidEntries = entries
            .map { it to it.getAnnotationsByType(Tags::class).first() }
            .flatMap { (entry, annotation) ->
                annotation.tags.filter { !it.matches(regex) }.map { tag ->
                    if (entry !is KSClassDeclaration) return@map tag
                    "${entry.fullName}: $tag"
                }
            }
            .toList()

        if (invalidEntries.isEmpty()) return emptyList()
        throw InvalidTagsException(invalidEntries)
    }
}

class InvalidTagsException(tags: List<String>) : Exception(
    """
    |Tags can only contain lowercase letters, numbers and underscores.
    |The following tags are invalid:
    | - ${tags.joinToString("\n - ")}
    |
""".trimMargin()
)

class TagsValidatorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TagsValidator()
    }
}