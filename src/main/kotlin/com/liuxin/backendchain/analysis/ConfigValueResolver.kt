package com.liuxin.backendchain.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

internal object ConfigValueResolver {
    private val placeholder = Regex("""\$\{([^}:]+)(?::([^}]*))?}""")
    private val cache = ConcurrentHashMap<String, CachedConfigs>()

    fun resolvePlaceholders(project: Project, value: String): String {
        if (!value.contains("\${")) return value
        val configs = cachedConfigs(project)
        if (configs.isEmpty()) return value
        return resolve(value, configs, emptySet())
    }

    private fun resolve(value: String, configs: Map<String, String>, visiting: Set<String>): String =
        placeholder.replace(value) { match ->
            val key = match.groupValues[1].trim()
            val fallback = match.groupValues.getOrNull(2).orEmpty()
            if (!visiting.addable(key)) {
                match.value
            } else {
                val configured = configs[key]
                when {
                    configured != null -> resolve(configured, configs, visiting + key)
                    fallback.isNotEmpty() -> fallback
                    else -> match.value
                }
            }
        }

    private fun Set<String>.addable(key: String): Boolean = key !in this

    private fun cachedConfigs(project: Project): Map<String, String> {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val key = project.locationHash
        val cached = cache[key]
        if (cached != null && cached.modificationCount == stamp) return cached.values
        val values = loadConfigs(project)
        cache[key] = CachedConfigs(stamp, values)
        return values
    }

    private fun loadConfigs(project: Project): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val scope = GlobalSearchScope.projectScope(project)
        listOf("properties", "yml", "yaml").forEach { ext ->
            FilenameIndex.getAllFilesByExt(project, ext, scope)
                .filter { file -> ProjectFileIndex.getInstance(project).isInContent(file) }
                .sortedBy { it.path }
                .forEach { file ->
                    val text = try {
                        VfsUtilCore.loadText(file)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (_: Exception) {
                        return@forEach
                    }
                    when (ext) {
                        "properties" -> result.putAll(parseProperties(text))
                        else -> result.putAll(parseYaml(text))
                    }
                }
        }
        return result
    }

    private fun parseProperties(text: String): Map<String, String> {
        val properties = Properties()
        properties.load(text.reader())
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    private fun parseYaml(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val stack = mutableListOf<Pair<Int, String>>()
        text.lineSequence().forEach { rawLine ->
            val withoutComment = rawLine.substringBefore(" #")
            if (withoutComment.isBlank() || withoutComment.trimStart().startsWith("#")) return@forEach
            val indent = withoutComment.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return@forEach
            val line = withoutComment.trim()
            val split = line.indexOf(':')
            if (split <= 0) return@forEach
            val key = line.substring(0, split).trim().takeIf { it.isNotBlank() } ?: return@forEach
            val rawValue = line.substring(split + 1).trim()
            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeAt(stack.lastIndex)
            }
            val fullKey = (stack.map { it.second } + key).joinToString(".")
            if (rawValue.isBlank()) {
                stack += indent to key
            } else {
                result[fullKey] = unquote(rawValue)
            }
        }
        return result
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("\"").removeSurrounding("'")

    private data class CachedConfigs(val modificationCount: Long, val values: Map<String, String>)
}
