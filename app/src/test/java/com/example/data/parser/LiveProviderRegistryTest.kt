package com.example.data.parser

import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProviderRegistryTest {
    private class RecordingProvider(
        override val sourceCode: String,
    ) : LiveProvider {
        var authorChecks: Int = 0
            private set

        override suspend fun genres(): List<GenreDto> = emptyList()

        override suspend fun catalog(page: Int): List<LiveCatalogItemDto> = emptyList()

        override suspend fun search(
            query: String,
            page: Int,
            limit: Int,
        ): List<LiveCatalogItemDto> = emptyList()

        override suspend fun book(bookId: String): BookDetailDto =
            error("Not needed by registry tests")

        override fun canBrowseAuthor(authorId: String): Boolean {
            authorChecks += 1
            return true
        }
    }

    @Test
    fun entityRoutingSelectsOnlyProviderEncodedInReference() {
        val audiopolka = RecordingProvider(AUDIOPOLKA_SOURCE)
        val uknig = RecordingProvider(UKNIG_SOURCE)
        val registry = LiveProviderRegistry(listOf(audiopolka, uknig))

        val selected = registry.providerForEntityRef("uknig:author:42")

        assertSame(uknig, selected)
        assertTrue(selected!!.canBrowseAuthor("uknig:author:42"))
        assertEquals(0, audiopolka.authorChecks)
        assertEquals(1, uknig.authorChecks)
    }

    @Test
    fun invalidEntityReferenceDoesNotSelectAnyProvider() {
        val provider = RecordingProvider(UKNIG_SOURCE)
        val registry = LiveProviderRegistry(listOf(provider))

        assertNull(registry.providerForEntityRef("uknig:author:../42"))
        assertEquals(0, provider.authorChecks)
    }

    @Test
    fun bookRoutingUsesBookSourcePrefix() {
        val audiopolka = RecordingProvider(AUDIOPOLKA_SOURCE)
        val rutracker = RecordingProvider(RUTRACKER_SOURCE)
        val registry = LiveProviderRegistry(listOf(audiopolka, rutracker))

        assertSame(rutracker, registry.providerForBook("rutracker:123456"))
        assertSame(audiopolka, registry.providerForBook("audiopolka:42"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateSourceCodesAreRejected() {
        LiveProviderRegistry(
            listOf(
                RecordingProvider(AUDIOPOLKA_SOURCE),
                RecordingProvider(AUDIOPOLKA_SOURCE),
            )
        )
    }
}
