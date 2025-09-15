package com.tellusr.searchindex.util

import kotlinx.serialization.json.*
import com.tellusr.searchindex.SiField
import java.time.Instant

object ConvertUtil {
    private fun some(maxLength: Int?): Int =
        maxLength?.let { it / 20 } ?: Int.MAX_VALUE

    fun toJsonValue(value: Any?, shortenTo: Int? = null): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value.shortenTo(shortenTo))
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is FloatArray -> JsonArray(value.take(some(shortenTo)).map { JsonPrimitive(it) })
            is Enum<*> -> JsonPrimitive(value.name)
            is StringExporter -> JsonPrimitive(value.stringExport())
            is Collection<*> -> JsonArray(value.take(some(shortenTo)).map { toJsonValue(it, shortenTo) })
            is Map<*, *> -> value.entries.associate { (key, value) ->
                when (key) {
                    is String -> key
                    is Enum<*> -> key.name
                    is SiField -> key.primary()
                    else -> key.toString()
                } to toJsonValue(value, shortenTo)
            }.let { JsonObject(it) }

            is Instant -> JsonPrimitive(value.toString())
            is JsonPrimitive -> if (value.isString && shortenTo != null) {
                JsonPrimitive(value.content.shortenTo(shortenTo))
            } else {
                value
            }

            is JsonElement -> shortenTo?.let {
                value
            } ?: JsonPrimitive(jsonFormatter.encodeToString(value).shortenTo(shortenTo))

            is JsonExporter ->
                value.jsonExport().let {
                    if(shortenTo != null) {
                        JsonPrimitive(jsonFormatter.encodeToString(it).shortenTo(shortenTo))
                    }
                    else {
                        it
                    }
                }


            else -> TODO("Conversion not supported: ${value::class.simpleName} - ${value.toString()}")
        }
    }


    fun toAny(e: JsonElement): Any? =
        when (e) {
            is JsonNull -> null

            is JsonPrimitive -> {
                if (e.isString) {
                    e.contentOrNull
                } else {
                    e.booleanOrNull ?: e.intOrNull ?: e.doubleOrNull
                }
            }

            is JsonArray -> {
                e.map { toAny(it) }.toList()
            }

            is JsonObject -> {
                e.map { it.key to toAny(it.value) }.toMap()
            }
        }


    val jsonFormatter = Json {
        prettyPrint = true
    }
}
