package com.example.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDtoJsonCompatibilityTest {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun oldBookCardJsonIgnoresRemovedCanonicalFields() {
        val json = """
            {
              "id": "book-1",
              "title": "Book",
              "rating": "4.8",
              "canonical_work_id": "work-42",
              "canonical_title": "Canonical title",
              "canonical_series": [
                {
                  "id": "series-1",
                  "name": "Legacy canonical series",
                  "provider": "fantlab"
                }
              ],
              "series": [
                {
                  "id": "series-current",
                  "name": "Current series",
                  "is_primary": true
                }
              ]
            }
        """.trimIndent()

        val value = moshi.adapter(BookCardDto::class.java).fromJson(json)

        assertNotNull(value)
        assertEquals("book-1", value!!.id)
        assertEquals("Canonical title", value.canonicalTitle)
        assertEquals("series-current", value.series.single().id)
    }

    @Test
    fun oldBookDetailJsonIgnoresRemovedCanonicalWorkAndSeries() {
        val json = """
            {
              "id": "book-2",
              "title": "Book detail",
              "rating": "4.9",
              "canonical_work": {
                "id": "work-77",
                "provider": "fantlab",
                "external_id": "77",
                "title": "Legacy work"
              },
              "canonical_series": [
                {
                  "id": "series-legacy",
                  "name": "Legacy canonical series"
                }
              ],
              "series": [
                {
                  "id": "series-current",
                  "name": "Current series"
                }
              ],
              "chapters": [
                {
                  "id": "chapter-1",
                  "position": 0,
                  "title": "Chapter",
                  "duration_seconds": 10,
                  "stream_url": "https://cdn.example.test/1.mp3"
                }
              ]
            }
        """.trimIndent()

        val value = moshi.adapter(BookDetailDto::class.java).fromJson(json)

        assertNotNull(value)
        assertEquals("book-2", value!!.id)
        assertEquals("series-current", value.series.single().id)
        assertEquals("chapter-1", value.chapters.single().id)
    }

    @Test
    fun oldBookDetailJsonIgnoresRemovedSourceVariantMetadata() {
        val json = """
            {
              "id": "book-source-variant",
              "title": "Book",
              "selected_source": "rutracker",
              "selected_book_source_id": "live:rutracker:42",
              "source_variants": [
                {
                  "book_source_id": "live:rutracker:42",
                  "source_code": "rutracker",
                  "source_name": "RuTracker",
                  "external_id": "42",
                  "external_url": "https://rutracker.example/topic/42",
                  "chapters_count": 12,
                  "duration_seconds": 3600,
                  "series_name": "Current source series",
                  "series_position": 7,
                  "magnet_uri": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                }
              ]
            }
        """.trimIndent()

        val value = moshi.adapter(BookDetailDto::class.java).fromJson(json)

        assertNotNull(value)
        val variant = value!!.sourceVariants.single()
        assertEquals("live:rutracker:42", variant.bookSourceId)
        assertEquals("rutracker", variant.sourceCode)
        assertEquals("RuTracker", variant.sourceName)
        assertEquals("Current source series", variant.seriesName)
        assertTrue(variant.magnetUri.startsWith("magnet:?xt=urn:btih:"))
    }

    @Test
    fun oldSeriesJsonIgnoresRemovedHierarchyAndWorkType() {
        val json = """
            {
              "id": "source:test:series",
              "name": "Series",
              "provider": "test",
              "parents": [
                {
                  "id": "parent",
                  "name": "Parent"
                }
              ],
              "children": [
                {
                  "id": "child",
                  "name": "Child"
                }
              ],
              "entries": [
                {
                  "external_work_id": "book-3",
                  "title": "Entry",
                  "published_year": 2020,
                  "work_type": "novel",
                  "available": true
                }
              ]
            }
        """.trimIndent()

        val value = moshi.adapter(SeriesDetailDto::class.java).fromJson(json)

        assertNotNull(value)
        assertEquals("source:test:series", value!!.id)
        assertEquals(1, value.entries.size)
        assertEquals(2020, value.entries.single().publishedYear)
        assertTrue(value.entries.single().available)
    }
}
