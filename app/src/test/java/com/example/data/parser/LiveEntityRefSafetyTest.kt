package com.example.data.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEntityRefSafetyTest {
    @Test
    fun normalProviderEntityRefsRemainAllowed() {
        assertTrue(isSafeLiveEntityRef("audiopolka:author:11"))
        assertTrue(isSafeLiveEntityRef("uknig:reader:123"))
        assertTrue(isSafeLiveEntityRef("knigavuhe:genre:triller"))
        assertTrue(isSafeLiveEntityRef("bazaknig:genre:true-crime"))
        assertTrue(isSafeLiveEntityRef("audioboo:author:%D0%90%D0%B2%D1%82%D0%BE%D1%80"))
    }

    @Test
    fun routeControlCharactersAreRejectedBeforeProviderUrlConstruction() {
        listOf(
            "knigavuhe:author:../admin",
            "knigavuhe:author:ivan/petrov",
            "uknig:genre:crime?sort=new",
            "audioboo:narrator:name#fragment",
            "knigavuhe:reader:name\\other",
            "bazaknig:genre:name:other",
            "knigavuhe:author:ivan%2Fpetrov",
            "knigavuhe:author:ivan%5Cpetrov",
            "knigavuhe:author:ivan%3Fpage",
            "knigavuhe:author:ivan%23fragment",
            "knigavuhe:author:%2E%2E",
            "knigavuhe:author:ivan%252Fpetrov",
        ).forEach { value ->
            assertFalse(value, isSafeLiveEntityRef(value))
        }
    }

    @Test
    fun encodedRouteSeparatorsAreRejectedInBookKeysToo() {
        assertNotNull(parseLiveBookKey("knigavuhe:normal-book"))
        assertNotNull(parseLiveBookKey("audioboo:fantastika/123-test.html"))

        assertNull(parseLiveBookKey("knigavuhe:book%2Fextra"))
        assertNull(parseLiveBookKey("knigavuhe:book%252Fextra"))
        assertNull(parseLiveBookKey("audioboo:fantastika/123%2F456.html"))
        assertNull(parseLiveBookKey("audioboo:fantastika/123%3Fpage.html"))
    }
}
