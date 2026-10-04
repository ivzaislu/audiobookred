package com.example.data.repository

import java.util.ArrayDeque
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CatalogPhysicalBatch<T>(
    val items: List<T>,
    val terminal: Boolean = false,
)

internal data class BufferedCatalogPage<T>(
    val items: List<T>,
    val total: Int,
)

/**
 * Adapts provider-sized physical pages to caller-sized logical pages without
 * dropping the surplus items from a provider response.
 */
internal class CatalogPageBuffer<T> {
    private val mutex = Mutex()
    private val items = mutableListOf<T>()
    private var nextPhysicalPage = 1
    private var exhausted = false

    suspend fun page(
        page: Int,
        limit: Int,
        loadPhysicalPage: suspend (Int) -> CatalogPhysicalBatch<T>,
    ): BufferedCatalogPage<T> = mutex.withLock {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val offset = (safePage.toLong() - 1L) * safeLimit.toLong()
        val targetExclusive = offset + safeLimit.toLong()
        if (offset > Int.MAX_VALUE.toLong() || targetExclusive > Int.MAX_VALUE.toLong()) {
            return@withLock BufferedCatalogPage(emptyList(), if (exhausted) items.size else 0)
        }

        while (items.size < targetExclusive.toInt() && !exhausted) {
            val physicalPage = nextPhysicalPage
            val batch = loadPhysicalPage(physicalPage)
            if (batch.items.isEmpty()) {
                if (batch.terminal) exhausted = true
                // A non-terminal empty batch represents a temporary provider
                // failure. Do not spin or advance the cursor; a later retry may
                // load this same physical page successfully.
                break
            }

            items += batch.items
            if (batch.terminal || physicalPage == Int.MAX_VALUE) {
                exhausted = true
            } else {
                nextPhysicalPage = physicalPage + 1
            }
        }

        val logicalItems = if (offset >= items.size.toLong()) {
            emptyList()
        } else {
            items.drop(offset.toInt()).take(safeLimit)
        }
        BufferedCatalogPage(
            items = logicalItems,
            total = if (exhausted) items.size else 0,
        )
    }
}

/**
 * Stateful aggregate pager with one physical cursor per provider.
 *
 * Provider failures must not advance another provider's cursor or create a hole
 * in an already-returned logical page. Logical pages are therefore sealed once
 * returned. A provider that failed while a page was being assembled keeps its
 * physical cursor and is retried while a later logical page is assembled.
 */
internal class AggregateCatalogPageBuffer<K : Any, T : Any>(
    providers: List<K>,
    private val pageSize: Int,
) {
    private data class ProviderState<T : Any>(
        var nextPhysicalPage: Int = 1,
        var exhausted: Boolean = false,
        val pending: ArrayDeque<T> = ArrayDeque(),
    )

    private data class ProviderAttempt<K : Any, T : Any>(
        val provider: K,
        val batch: CatalogPhysicalBatch<T>? = null,
        val error: Exception? = null,
    )

    private val mutex = Mutex()
    private val providerOrder = providers.distinct()
    private val states = LinkedHashMap<K, ProviderState<T>>().apply {
        providerOrder.forEach { provider -> put(provider, ProviderState()) }
    }
    private val logicalPages = mutableListOf<List<T>>()
    private var nextDrainProviderIndex = 0
    private var totalItems = 0
    private var knownTotal: Int? = if (providerOrder.isEmpty()) 0 else null

    init {
        require(pageSize > 0) { "Aggregate catalog page size must be positive" }
    }

    suspend fun page(
        page: Int,
        loadPhysicalPage: suspend (K, Int) -> CatalogPhysicalBatch<T>,
    ): BufferedCatalogPage<T> = mutex.withLock {
        val safePage = page.coerceAtLeast(1)
        while (logicalPages.size < safePage && knownTotal == null) {
            val generated = generateLogicalPage(loadPhysicalPage)
            if (generated == null) {
                knownTotal = totalItems
                break
            }
            logicalPages += generated
            totalItems = (totalItems.toLong() + generated.size.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (allProvidersExhausted()) knownTotal = totalItems
        }

        BufferedCatalogPage(
            items = logicalPages.getOrNull(safePage - 1).orEmpty(),
            total = knownTotal ?: 0,
        )
    }

    private suspend fun generateLogicalPage(
        loadPhysicalPage: suspend (K, Int) -> CatalogPhysicalBatch<T>,
    ): List<T>? {
        if (allProvidersExhausted()) return null

        val output = ArrayList<T>(pageSize)
        val failedThisPage = mutableSetOf<K>()
        var lastFailure: Exception? = null

        while (output.size < pageSize) {
            val candidates = providerOrder.filter { provider ->
                val state = states.getValue(provider)
                !state.exhausted && state.pending.isEmpty() && provider !in failedThisPage
            }

            if (candidates.isNotEmpty()) {
                val attempts = coroutineScope {
                    candidates.map { provider ->
                        async {
                            val state = states.getValue(provider)
                            try {
                                ProviderAttempt(
                                    provider = provider,
                                    batch = loadPhysicalPage(provider, state.nextPhysicalPage),
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                ProviderAttempt<K, T>(provider = provider, error = error)
                            }
                        }
                    }.awaitAll()
                }

                attempts.forEach { attempt ->
                    val state = states.getValue(attempt.provider)
                    val batch = attempt.batch
                    if (batch == null) {
                        failedThisPage += attempt.provider
                        lastFailure = attempt.error ?: lastFailure
                        return@forEach
                    }

                    if (batch.items.isEmpty()) {
                        if (batch.terminal) {
                            state.exhausted = true
                        } else {
                            // Retry the same physical page when a later logical page
                            // is assembled. Retrying repeatedly in one call would spin
                            // against a temporarily broken provider.
                            failedThisPage += attempt.provider
                        }
                        return@forEach
                    }

                    state.pending.addAll(batch.items)
                    if (batch.terminal || state.nextPhysicalPage == Int.MAX_VALUE) {
                        state.exhausted = true
                    } else {
                        state.nextPhysicalPage += 1
                    }
                }
            }

            drainPending(output)
            if (output.size >= pageSize || allProvidersExhausted()) break

            val canAttemptMore = providerOrder.any { provider ->
                val state = states.getValue(provider)
                !state.exhausted && state.pending.isEmpty() && provider !in failedThisPage
            }
            val hasPending = states.values.any { it.pending.isNotEmpty() }
            if (!canAttemptMore && !hasPending) break
        }

        if (output.isNotEmpty()) return output
        if (allProvidersExhausted()) return null

        // Returning an empty non-terminal page makes Paging treat the feed as
        // finished. Surface a real load error instead so the same logical page can
        // be retried without advancing any provider cursor.
        throw lastFailure ?: IllegalStateException("Источники каталога временно недоступны")
    }

    private fun drainPending(output: MutableList<T>) {
        if (providerOrder.isEmpty()) return
        var emptyChecks = 0
        while (output.size < pageSize && emptyChecks < providerOrder.size) {
            val provider = providerOrder[nextDrainProviderIndex]
            nextDrainProviderIndex = (nextDrainProviderIndex + 1) % providerOrder.size
            val item = states.getValue(provider).pending.pollFirst()
            if (item == null) {
                emptyChecks += 1
            } else {
                output += item
                emptyChecks = 0
            }
        }
    }

    private fun allProvidersExhausted(): Boolean =
        states.values.all { state -> state.exhausted && state.pending.isEmpty() }
}
