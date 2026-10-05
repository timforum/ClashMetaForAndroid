package com.github.kr328.clash.service.probtest

import android.content.Context
import java.io.IOException

/**
 * The screening template as it sits in the APK: the .ini naming the groups and
 * rule sources, plus the rule files those sources resolve to.
 *
 * The rule files are keyed by file name, which is the last path element of the
 * URL a ruleset line names. That is what the core looks them up by, so the
 * template can carry full URLs while the bundle only carries file names.
 */
internal data class TemplateBundle(
    val ini: String,
    val ruleFiles: Map<String, String>,
)

/**
 * Reads the screening template out of the bundled assets.
 *
 * The template decides which groups the nodes that pass screening fill and
 * where each rule set sends its traffic, so a round that cannot read it has
 * nothing to publish that matches what was designed. It fails here, before any
 * candidate is downloaded, rather than falling back to the fixed layout the
 * reader was never promised.
 */
internal object TemplateAssets {
    /**
     * The directory the template is bundled in. It is a normal asset
     * directory, so a rule file added there is picked up by the next build
     * without any other change.
     */
    private const val DIRECTORY = "template"

    /**
     * Reads the template and every rule file next to it.
     *
     * Exactly one .ini is expected: more than one would leave the round
     * guessing which routing policy to publish, and none would leave it with
     * no policy at all.
     */
    @Throws(IOException::class, IllegalStateException::class)
    fun read(context: Context): TemplateBundle {
        val assets = context.assets

        val names = try {
            assets.list(DIRECTORY)
        } catch (e: IOException) {
            throw IOException("cannot list the bundled template at $DIRECTORY", e)
        } ?: throw IllegalStateException("no template bundled at $DIRECTORY")

        val templates = names.filter { it.endsWith(".ini", ignoreCase = true) }
        val template = when (templates.size) {
            0 -> throw IllegalStateException("no .ini bundled at $DIRECTORY")
            1 -> templates[0]
            else -> throw IllegalStateException(
                "several templates bundled at $DIRECTORY: ${templates.sorted().joinToString(", ")}",
            )
        }

        val ini = assets.open("$DIRECTORY/$template").use {
            it.readBytes().toString(Charsets.UTF_8)
        }

        // Every other file in the directory is a possible rule source. Which
        // of them the template actually names is decided in the core, so
        // nothing is dropped here for looking unused.
        val ruleFiles = mutableMapOf<String, String>()
        for (name in names) {
            if (name == template) {
                continue
            }
            ruleFiles[name] = assets.open("$DIRECTORY/$name").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }

        if (ruleFiles.isEmpty()) {
            throw IllegalStateException("no rule file bundled at $DIRECTORY")
        }

        return TemplateBundle(ini, ruleFiles)
    }
}
