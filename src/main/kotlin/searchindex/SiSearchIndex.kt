package com.tellusr.searchindex

import com.tellusr.searchindex.exception.TellusRIndexException
import com.tellusr.searchindex.util.getAutoNamedLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tellusr.searchindex.exception.messageAndCrumb
import com.tellusr.searchindex.exception.stackTraceString
import com.tellusr.searchindex.query.StandardQueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.BytesRef
import java.nio.file.Path
import java.nio.file.Files
import java.util.*
import kotlin.math.max
import kotlin.math.min


/**
 * The SearchIndex class represents a lucene index that enables fast
 * and powerful search operations. It allows adding, updating, removing, and searching
 * of elements efficiently.
 *
 * Creating a new SearchIndex usually involves implementing a wrapping interface containing
 * Fields enum (SiField), Schema object (SiSchema), Record class (SiRecord) and SearchIndex class.
 * The idiomatic structure for this is to have all these components as inner declarations of
 * the wrapping interface.
 *
 * Example:
 * ```kotlin
 * interface DataStore {
 *     enum class Fields : SiField {
 *         Id, Name;
 *         override fun primary(): String = name
 *         override fun fieldType(): Set<SiFieldType> = when (this) {
 *             Id -> setOf(SiFieldType.UniqueId)
 *             else -> setOf(SiFieldType.Auto)
 *         }
 *     }
 *
 *     object Schema : SiSchema("store", Fields.entries) {
 *         override fun fromDoc(doc: Document): SiRecord = Record(doc)
 *         override fun fromJson(jsonObject: JsonObject): Record =
 *             json.decodeFromJsonElement<Record>(jsonObject)
 *     }
 *
 *     class Record(var id: String, var name: String) : SiRecord {
 *         constructor(doc: Document) : this(
 *             id = doc.get(Fields.Id.primary()),
 *             name = doc.get(Fields.Name.primary())
 *         )
 *         override fun schema(): SiSchema = Schema
 *         override fun getValue(key: SiField): Any? = when (key as? Fields) {
 *             Fields.Id -> id
 *             Fields.Name -> name
 *             null -> null
 *         }
 *     }
 *
 *     class SearchIndex(name: String) : SiSearchIndex<Record>(Schema, name)
 * }
 * ```
 *
 * The class is designed to be thread-safe, guaranteeing consistent state
 * even when accessed concurrently.
 */
@Suppress("UNCHECKED_CAST")
open class SiSearchIndex<TT : SiRecord>(
    final override val schema: SiSchema,
    val name: String,
    val createDirectory: (searchIndex: SiSearchIndex<*>) -> Directory = { si ->
        FSDirectory.open(si.schema.path(si.name))
    }
) : SiSearchInterface<TT> {
    // Needs to be created first
    private val logger = getAutoNamedLogger()

    // Use this to check if cached data is still valid
    private var writeCounter: Int = 0
    fun writeCount(): Int = writeCounter


    /**
     * Returns a qualified name for the search index by combining the schema group and index name.
     * This is used to ensure uniqueness when registering indexes and to provide a fully qualified
     * identifier for the index in the form "group.name".
     *
     * @return A string containing the fully qualified index name in the format "schema.group.name"
     */
    fun qualifiedName(): String = "${schema.group}.$name"

    companion object {
        private val existingIndexNames: MutableSet<String> = mutableSetOf()
    }

    init {
        val fullName = qualifiedName()
        if (existingIndexNames.contains(fullName)) {
            throw TellusRIndexException.SearchIndexError(
                "Initialized the same index again: $fullName ${this::class.simpleName}"
            )
        }
        logger.trace("Initialized index: {}", fullName)
        existingIndexNames.add(fullName)

        initIndex()
    }


    fun close() {
        searchManager.close()
        existingIndexNames.remove(qualifiedName())
    }

    private var searchManager: SearcherManager = createSearchManager()

    private val writeMutex = Mutex()

    private fun initIndex() {
        val oldPath: Path = this.schema.oldPath(this.name)
        val newPath: Path = this.schema.path(this.name)

        if (Files.exists(oldPath)) {
            if (!Files.exists(newPath)) {
                logger.warn("Moving index from old to new path: {} -> {}", oldPath, newPath)
                Files.createDirectories(newPath.parent)
                Files.move(oldPath, newPath)
            }
        }


        val directory: Directory = createDirectory(this)
        val analyzer: Analyzer = schema.analyser()
        val config = IndexWriterConfig(analyzer).apply {
            this.openMode = OpenMode.CREATE_OR_APPEND
        }

        IndexWriter(directory, config).use {
            it.commit()
            it.close()
        }
    }


    private fun createSearchManager(): SearcherManager {
        logger.trace("Initializing: {}", schema.path(name))

        val sf = object : SearcherFactory() {
            override fun newSearcher(reader: IndexReader, previousReader: IndexReader?): IndexSearcher {
                val searcher = IndexSearcher(reader)
                searcher.similarity = BM25Similarity()
                return searcher
            }
        }

        val directory: Directory = MMapDirectory(schema.path(name))
        return SearcherManager(directory, sf)
    }


    private fun refreshSearchManager() {
        logger.trace("Refreshing scene manager")
        searchManager.maybeRefreshBlocking()
    }

    private suspend fun <TT> searcher(task: (indexSearcher: IndexSearcher) -> TT): TT {
        val result = withContext<TT>(Dispatchers.IO) {
            val sm = searchManager
            val reader = try {
                sm.acquire()
            } catch (ex: NullPointerException) {
                logger.trace("{}", ex.stackTraceString)
                initIndex()
                sm.acquire()
            }
            try {
                task(reader)
            } finally {
                sm.release(reader)
            }

        }
        return result
    }


    private suspend fun writer(
        openMode: OpenMode = OpenMode.CREATE_OR_APPEND,
        task: (indexWriter: IndexWriter) -> Unit
    ) = writeMutex.withLock {
        val directory: Directory = FSDirectory.open(schema.path(name))
        val analyzer: Analyzer = schema.analyser()

        val config = IndexWriterConfig(analyzer).apply {
            this.openMode = openMode
            this.ramBufferSizeMB = 16.0
            this.maxBufferedDocs = 5000
            this.similarity = similarity
        }

        withContext(Dispatchers.IO) {
            val writer = IndexWriter(directory, config)
            try {
                task(writer)
                writer.commit()
                refreshSearchManager()
            } finally {
                writer.close()
                ++writeCounter
            }
        }
    }


    /**
     * Retrieves all documents in the search index.
     * It uses a `MatchAllDocsQuery` to fetch all documents and returns them as a list.
     *
     * @return A list of all documents in the index.
     */
    override suspend fun all(start: Int, rows: Int): SiHits<TT> = search(MatchAllDocsQuery(), start, rows)


    /**
     * The `size` property returns the total number of documents in the search index.
     *
     * It uses the lucene count method on a `MatchAllDocsQuery` to count all documents present
     * within the index.
     *
     * @return The total count of documents in the index.
     */
    override suspend fun size(): Int = searcher {
        searcher ->
        searcher.count(MatchAllDocsQuery())
    }


    /**
     * Checks if the search index is empty.
     * It determines this by comparing the size of the index to zero.
     *
     * @return True if the index contains no documents, false otherwise.
     */
    suspend fun isEmpty(): Boolean = (size() == 0)


    /**
     * Retrieves a single document from the search index by its ID.
     *
     * @param q The ID of the document to retrieve.
     * @return The document with the specified ID, or null if no such document exists.
     */
    override suspend fun byId(q: String): TT? =
        search(schema.idQuery(q), 0, 1, null).docs.firstOrNull()


    /**
     * Executes the search based on the provided query, maximum number of rows,
     * and an optional sorting parameter. It performs a search using the provided query
     * and returns the results as SiHits.
     *
     * @param query The query to execute against the index.
     * @param rows The maximum number of rows to return in the search results.
     * @param sort (Optional) The sorting criteria for the results.
     * @return A SiHits instance containing the search results.
     */
    override suspend fun search(query: Query, start: Int, rows: Int, sort: Sort?): SiHits<TT> {
        val rows = start + rows
        logger.trace("Searching {}: {}", schema.path(name), query)
        return searcher<SiHits<TT>> { searcher ->
            val docs = sort?.let { sort ->
                searcher.search(query, rows, sort)
            } ?: searcher.search(query, rows)
            val res = docs.scoreDocs.map {
                val doc = searcher.indexReader.storedFields().document(it.doc)
                schema.fromDoc(doc) as TT
            }

            if (start < res.size) {
                val takeCount = min(rows, res.size - start)
                SiHits(docs.totalHits, res.drop(start).take(takeCount))
            } else {
                SiHits(docs.totalHits, emptyList())

            }
        }
    }


    /**
     * Executes a search using a string query against the specified default field.
     * The query string is parsed into a Lucene Query using StandardQueryBuilder,
     * and then executed against the index.
     *
     * @param query The search query string to execute
     * @param defaultField The field to search against if no field is specified in the query string.
     *                    Defaults to the schema's default search field.
     * @return A SiHits object containing the search results and total hit count
     */
    suspend fun search(query: String, defaultField: SiField = schema.defaultSearchField): SiHits<TT> =
        StandardQueryBuilder(schema, defaultField, query).build().let {
            search(it, start = 0, rows = SiSchema.ALL)
        }


    /**
     * The `clear` method deletes all documents from the search index.
     * This operation will remove all indexed documents and keep the index structure intact.
     * It uses IndexWriter().deleteAll() to clear the documents.
     */
    override suspend fun clear() {
        writer(OpenMode.CREATE_OR_APPEND) { indexWriter ->
            indexWriter.deleteAll()
        }
    }


    /**
     * Creates a new index and inserts the documents into this index.
     * If the index exists, it overwritten.
     *
     * @param docs The documents to add to the search index.
     */
    suspend fun create(docs: Iterable<Document>) {
        writer(OpenMode.CREATE) { indexWriter ->
            docs.forEach { doc ->
                doc.get(schema.idField.primary()).let { it ->
                    if (it == null) {
                        val id = UUID.randomUUID().toString()
                        doc.add(StringField(schema.idField.primary(), id, Field.Store.YES))
                        doc.add(SortedDocValuesField(schema.idField.primary(), BytesRef()))
                    }
                }
                indexWriter.addDocument(doc)
            }
        }
    }


    /**
     * Creates a new index and inserts the documents into this index.
     * If the index exists, it is overwritten.
     *
     * @param docs The documents to add to the search index.
     */
    suspend fun create(records: List<SiRecord>) {
        create(records.map { it.toLuceneDoc() })
    }


    /**
     * The `update` function updates the index with the provided records.
     * Documents that do not exist will be added.
     *
     * @param records The list of records to update in the index.
     */
    override suspend fun update(records: List<SiRecord>) {
        update(records.map {
            it.toLuceneDoc()
        })
    }


    /**
     * Updates a record in the index by its ID using a provided transformation function.
     * This method first retrieves the record by its ID, applies the transformation function,
     * and then updates the index with the modified record.
     *
     * Example:
     * ```kotlin
     * // Update the category of a record
     * searchIndex.update("doc123") { record ->
     *     record.category = "new-category"
     * }
     *
     * // Update multiple fields
     * searchIndex.update("doc456") { record ->
     *     record.instruction = "New instruction"
     *     record.language = "en"
     *     record.vectorized = calculateNewVector(record)
     * }
     * ```
     *
     * @param id The unique identifier of the record to update
     * @param task A suspending lambda function that takes the existing record and returns
     *            the modified record. The function is only called if the record exists.
     */
    suspend fun update(id: String, task: suspend (record: TT) -> Unit) {
        byId(id)?.let {
            task(it)
            update(it)
        }
    }


    /**
     * The `update` function updates the index with the provided documents.
     * Documents that do not exist will be added.
     *
     * @param docs The iterable collection of documents to update in the index.
     */
    private fun ensureDocumentId(doc: Document) {
        doc.get(schema.idField.primary()).let { id ->
            if (id == null) {
                val createdId = UUID.randomUUID().toString()
                doc.add(StringField(schema.idField.primary(), createdId, Field.Store.YES))
                doc.add(SortedDocValuesField(schema.idField.primary(), BytesRef()))
            }
        }
    }


    /**
     * Updates multiple Lucene Document objects in the search index in a single transaction.
     * This method works directly with low-level Lucene Document objects, not SiRecord instances.
     * For each document:
     * 1. If the document has an ID, the existing document with that ID is deleted
     * 2. If the document has no ID, a new UUID is generated and assigned
     * 3. The document is then added to the index
     *
     * The entire operation is atomic and thread-safe. If any document update fails,
     * the entire transaction is rolled back and no changes are made to the index.
     *
     * @param docs An Iterable of Lucene Document objects to update in the index
     * @throws TellusRIndexException If there is an error during the update process
     */
    suspend fun update(docs: Iterable<Document>) {
        writer(OpenMode.CREATE_OR_APPEND) { indexWriter ->
            docs.forEach { doc ->
                try {
                    doc.get(schema.idField.primary()).let { id ->
                        if (id != null) {
                            indexWriter.deleteDocuments(
                                Term(schema.idField.primary(), id)
                            )
                            logger.trace("Deleting id: $id")
                        }
                        logger.trace("Updating id: $id")
                    }
                    ensureDocumentId(doc)
                    indexWriter.addDocument(doc)
                } catch (ex: Throwable) {
                    logger.error(ex.messageAndCrumb)
                    throw ex
                }
            }
        }
    }


    /**
     * The `remove` function allows for the removal of documents from the index based on a list of
     * IDs.
     *
     * This operation ensures the documents identified by the specified IDs are removed
     * from the search index.
     *
     * @param idList The list of IDs for the documents to be removed from the search index.
     */
    override suspend fun remove(idList: List<String>) {
        writer(OpenMode.CREATE_OR_APPEND) { indexWriter ->
            idList.forEach {
                indexWriter.deleteDocuments(schema.idQuery(it))
            }
        }
    }


    suspend fun validateWithException() {
        schema.constraints.forEach { it.validateWithThrow(this) }
    }


    suspend fun mayInsert(record: TT): Boolean {
        val failed = schema.constraints.filter { !it.validate(this) }
        logger.warn("The ${failed.joinToString { it.name }} constraint(s) failed for insert in ${schema.path(name)} for record ${record.toLogString()}")
        return failed.isNotEmpty()
    }


    suspend fun dump(maxLength: Int = 80): String =
        all().docs.joinToString("\n") {
            val line = it.toLogString()
            if (line.length > maxLength) {
                line.substring(maxLength)
            } else {
                line
            }
        }
}


