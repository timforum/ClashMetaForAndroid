package com.github.kr328.clash.service.util

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * Carries the contents of one preference file in two directions.
 *
 * A backup writes them out as Android's own shared_prefs xml, so the zip
 * stays readable by anything that understands that format, and a restore reads
 * them back before handing them to the live [SharedPreferences] of whichever
 * process owns them.
 *
 * The second direction is a [Bundle]: preferences are per-process, and the one
 * file that matters (`service`) is written by the `:background` process while
 * the screen that edits it runs in the main process. Only the owning process
 * can replace its in-memory copy, so a restore has to travel over IPC instead
 * of just overwriting the file and hoping every reader notices.
 */
object PreferencesCodec {
    /** Renders [values] as a `shared_prefs` XML document. */
    fun encode(values: Map<String, Any?>): ByteArray {
        val writer = StringWriter()
        val serializer = Xml.newSerializer()

        serializer.setOutput(writer)
        serializer.startDocument(StandardCharsets.UTF_8.name(), true)
        serializer.startTag(null, "map")

        for ((key, value) in values) {
            when (value) {
                null -> Unit
                is String -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value)
                    serializer.endTag(null, "string")
                }
                is Boolean -> scalar(serializer, "boolean", key, value.toString())
                is Int -> scalar(serializer, "int", key, value.toString())
                is Long -> scalar(serializer, "long", key, value.toString())
                is Float -> scalar(serializer, "float", key, value.toString())
                is Set<*> -> {
                    serializer.startTag(null, "set")
                    serializer.attribute(null, "name", key)

                    for (item in value) {
                        if (item == null) continue

                        serializer.startTag(null, "string")
                        serializer.text(item.toString())
                        serializer.endTag(null, "string")
                    }

                    serializer.endTag(null, "set")
                }
                else -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value.toString())
                    serializer.endTag(null, "string")
                }
            }
        }

        serializer.endTag(null, "map")
        serializer.endDocument()

        return writer.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Reads a `shared_prefs` document back. Returns null rather than a partial
     * map when the document is not understood: a restore must never apply half
     * of a preference file.
     */
    fun decode(bytes: ByteArray): Map<String, Any?>? {
        return try {
            val values = LinkedHashMap<String, Any?>()
            val parser = Xml.newPullParser()

            parser.setInput(ByteArrayInputStream(bytes), StandardCharsets.UTF_8.name())

            var setKey: String? = null
            var setValues: MutableSet<String>? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "set" -> {
                        setKey = parser.getAttributeValue(null, "name")
                        setValues = linkedSetOf()
                    }

                    parser.eventType == XmlPullParser.START_TAG && parser.name == "string" -> {
                        // The name has to be read before nextText() moves on.
                        val key = parser.getAttributeValue(null, "name")
                        val text = parser.nextText()
                        val set = setValues

                        if (set != null) {
                            set.add(text)
                        } else if (key != null) {
                            values[key] = text
                        }
                    }

                    parser.eventType == XmlPullParser.START_TAG &&
                        parser.name in SCALAR_TAGS -> {
                        val key = parser.getAttributeValue(null, "name")
                        val raw = parser.getAttributeValue(null, "value")

                        if (key != null && raw != null) {
                            parseScalar(parser.name, raw)?.let { values[key] = it }
                        }
                    }

                    parser.eventType == XmlPullParser.END_TAG && parser.name == "set" -> {
                        setKey?.let { values[it] = setValues.orEmpty() }
                        setKey = null
                        setValues = null
                    }
                }

                parser.next()
            }

            values
        } catch (e: Exception) {
            null
        }
    }

    private val SCALAR_TAGS = setOf("boolean", "int", "long", "float")

    private fun parseScalar(tag: String, raw: String): Any? = when (tag) {
        "boolean" -> raw.toBoolean()
        "int" -> raw.toIntOrNull()
        "long" -> raw.toLongOrNull()
        "float" -> raw.toFloatOrNull()
        else -> null
    }

    private fun scalar(serializer: XmlSerializer, tag: String, key: String, value: String) {
        serializer.startTag(null, tag)
        serializer.attribute(null, "name", key)
        serializer.attribute(null, "value", value)
        serializer.endTag(null, tag)
    }

    /** Sends [values] over IPC. Only the types SharedPreferences can hold are carried. */
    fun encodeBundle(values: Map<String, Any?>): Bundle {
        val bundle = Bundle(values.size)

        for ((key, value) in values) {
            when (value) {
                null -> Unit
                is String -> bundle.putString(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Float -> bundle.putFloat(key, value)
                is Set<*> -> bundle.putStringArrayList(
                    key,
                    ArrayList(value.filterIsInstance<String>()),
                )
                else -> bundle.putString(key, value.toString())
            }
        }

        return bundle
    }

    /** Reads back what [encodeBundle] produced. */
    @Suppress("DEPRECATION")
    fun decodeBundle(bundle: Bundle): Map<String, Any?> {
        val values = LinkedHashMap<String, Any?>(bundle.size())

        for (key in bundle.keySet()) {
            when (val value = bundle.get(key)) {
                null -> Unit
                is String -> values[key] = value
                is Boolean -> values[key] = value
                is Int -> values[key] = value
                is Long -> values[key] = value
                is Float -> values[key] = value
                is ArrayList<*> -> values[key] = value.filterIsInstance<String>().toSet()
                else -> values[key] = value.toString()
            }
        }

        return values
    }

    /**
     * Replaces every key of [target] with [values] and commits before
     * returning.
     *
     * Committing rather than applying is deliberate: a restore has just put
     * new bytes on disk, and an `apply()` that had not run yet would write the
     * previous contents straight back over them.
     */
    fun write(target: SharedPreferences, values: Map<String, Any?>) {
        val editor = target.edit().clear()

        for ((key, value) in values) {
            when (value) {
                null -> Unit
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> editor.putString(key, value.toString())
            }
        }

        editor.commit()
    }
}
