package com.tellusr.searchindex.tool

import com.tellusr.searchindex.SiRecord
import com.tellusr.searchindex.SiSearchIndex
import com.tellusr.searchindex.util.getAutoNamedLogger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tellusr.searchindex.exception.messageAndCrumb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.*

/**
 * SiDelayedUpdate class represents a utility for managing delayed updates.
 * It is useful for converting frequent single updates into periodic batch
 * updates. The update will happen either when the number of entries reaches
 * a certain number (100), or no updates has been made for a certain amount of time
 * (2 seconds).
 *
 * @property delayMillis The delay in milliseconds before an update is performed.
 */
class SiDelayedUpdate<TT : SiRecord>(
    val searchIndex: SiSearchIndex<TT>
) {
    private var updateQueue: LinkedList<TT> = LinkedList()
    private var lastUpdateQueueInsert: Instant = Instant.now()


    private val updateMutex = Mutex()
    private val queueMutex = Mutex()

    private suspend fun delayedUpdate() {
        // Start a delayed update if none is already running
        if (updateMutex.tryLock()) {
            coroutineScope {
                launch(Job()) {
                    try {
                        var isMore = true
                        while (isMore) {
                            isMore = false

                            // Do not insert until there has been no inserts for two seconds, unless
                            // there is a reasonable number of entries to store
                            while (lastUpdateQueueInsert.plusSeconds(1).isAfter(Instant.now())
                                && updateQueue.size < 5000
                            ) {
                                delay(250)
                            }

                            val oldQueue = updateQueue
                            queueMutex.withLock {
                                updateQueue = LinkedList()
                            }
                            logger.info("Delayed update of ${oldQueue.size} records started")

                            // Write updates to the index
                            oldQueue.chunked(25000).forEach {
                                // Chunk to avoid OOM
                                searchIndex.update(it)
                                logger.trace("Updated chunk of ${it.size} records")
                            }
                            logger.info("Delayed update of ${oldQueue.size} records finished")

                            queueMutex.withLock {
                                isMore = updateQueue.isNotEmpty()
                            }
                        }
                    } finally {
                        //
                        logger.info("Delayed update finished all")
                        updateMutex.unlock()
                    }
                }
            }
        }
    }

    suspend fun update(record: TT) {
        try {
            logger.info("Queuing record for delayed storage")
            lastUpdateQueueInsert = Instant.now()
            queueMutex.withLock {
                updateQueue.add(record)
            }
            delayedUpdate()
        } catch (e: IllegalArgumentException) {
            logger.error(e.messageAndCrumb)

            // Reformat entries
            logger.info("Reformatting index")
            searchIndex.create(searchIndex.all(Int.MAX_VALUE).docs)
            searchIndex.update(record)
        }
    }

    suspend fun update(records: List<TT>) {
        try {
            logger.info("Queuing ${records.size} records for delayed storage")
            lastUpdateQueueInsert = Instant.now()
            queueMutex.withLock {
                updateQueue.addAll(records)
            }
            delayedUpdate()
        } catch (e: IllegalArgumentException) {
            logger.error(e.messageAndCrumb)

            // Reformat entries
            logger.info("Reformatting index")
            searchIndex.create(searchIndex.all(Int.MAX_VALUE).docs)
            searchIndex.update(records)
        }
    }

    companion object {
        private val logger = getAutoNamedLogger()
    }
}

