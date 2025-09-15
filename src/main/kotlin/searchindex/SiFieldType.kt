package com.tellusr.searchindex

import com.tellusr.searchindex.util.JsonExporter
import com.tellusr.searchindex.util.StringExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexableField
import org.apache.lucene.util.BytesRef
import java.security.Key
import java.time.Instant


/**
 * SiFieldType is an enumeration that defines various types of fields
 * in a SiField structure, determining their input and indexing behavior.
 *
 * - `Auto`: Automatically determines the field type based on the value data type.
 * - `UniqueId`: Represents a unique identifier field.
 * - `Keyword`: Stores a single keyword for exact match searches.
 * - `ForeignKey`: Represents a link to another entity.
 * - `SortKey`: Use the value of this field as sort key. Should be combined with another field type.
 * - `Text`: Stores full-text content for searching.
 * - `MetaTags`: For fields that should not be stored but which may be populated programatically.
 */
enum class SiFieldType {
    Auto,
    UniqueId,
    Keyword,
    ForeignKey,
    SortKey,
    Text,
    DefaultSearch,
    Vector,
    MetaTags;


    /**
     * Maps a field type and its value to an appropriate list of Lucene IndexableField
     * instances.
     *
     * @param key The name of the field.
     * @param value The value associated with the key, or null if no value is provided.
     * @return A list of Lucene IndexableField instances appropriate for the SiFieldType.
     */
    fun toLuceneFields(key: String, value: Any?): List<IndexableField> =
        when (this) {
            UniqueId  -> value?.let {
                listOf(
                    StringField(key, value.toString(), Field.Store.YES),
                    SortedDocValuesField(key, BytesRef(value.toString()))
                )
            } ?: listOf()

            Keyword, ForeignKey -> when (value) {
                is Collection<*> -> {
                    (value as? List<*>)?.mapNotNull {
                        it.toString()
                    }?.map {
                        listOf(
                            StringField(key, it, Field.Store.YES),
                        )
                    }?.flatten() ?: listOf()

                }

                else -> value?.let {
                    listOf(
                        StringField(key, value.toString(), Field.Store.YES),
                        SortedDocValuesField(key, BytesRef(value.toString()))
                    )
                } ?: listOf()
            }

            Auto, Text, Vector, DefaultSearch -> Companion.toLuceneFields(key, value)

            MetaTags -> listOf()

            SortKey -> listOf(
                SortedDocValuesField(SiRecord.sortField.primary(), BytesRef(value.toString().lowercase()))
            )
        }


    companion object {
        /**
         * Utility method for translation kotlin data types to Lucene IndexableField instances.
         * The Auto field type relies on this conversion.
         *
         * @param key The name of the field.
         * @param value The value associated with the key, which can be null.
         * @return A list of Lucene IndexableField instances suitable for the given SiFieldType.
         */
        fun toLuceneFields(key: String, value: Any?): List<IndexableField> =
            when (value) {
                null -> listOf()
                is String -> listOf(TextField(key, value.toString(), Field.Store.YES))
                is Int -> listOf(IntField(key, value, Field.Store.YES))
                is Long -> listOf(LongField(key, value, Field.Store.YES))
                is Float -> listOf(FloatField(key, value, Field.Store.YES))
                is Double -> listOf(DoubleField(key, value, Field.Store.YES))
                is Boolean -> listOf(StringField(key, value.toString(), Field.Store.YES))
                is Instant -> listOf(StringField(key, value.toString(), Field.Store.YES))
                is Enum<*> -> listOf(StringField(key, value.name, Field.Store.YES))
                is StringExporter -> listOf(StringField(key, value.stringExport(), Field.Store.YES))
                is JsonElement -> listOf(TextField(key, jsonEncoder.encodeToString(value), Field.Store.YES))
                is JsonExporter -> value.jsonExport().let {
                    listOf(TextField(key, jsonEncoder.encodeToString(it), Field.Store.YES))
                }

                is Collection<*> -> value.flatMap { toLuceneFields(key, it) }.toList()
                is FloatArray -> {
                    listOf(
                        KnnFloatVectorField(key, value),
                        StringField(key, SiRecord.toVectorString(value), Field.Store.YES)
                    )
                }

                else -> TODO("Unsupported type: ${value::class.simpleName}")
            }


        val jsonEncoder = Json {
            prettyPrint = true
        }
    }
}
