package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class KnigavuheHomeSectionUrlTest {
    @Test
    fun homeDiscoverySectionsUseKnigavuheRoutes() {
        assertEquals(
            "https://knigavuhe.org/new/",
            AbredKnigavuheParserV2.homeSectionUrl(AbredKnigavuheParserV2.HOME_SECTION_NEW),
        )
        assertEquals(
            "https://knigavuhe.org/popular/?w=today",
            AbredKnigavuheParserV2.homeSectionUrl(AbredKnigavuheParserV2.HOME_SECTION_POPULAR_TODAY),
        )
        assertEquals(
            "https://knigavuhe.org/popular/?w=week",
            AbredKnigavuheParserV2.homeSectionUrl(AbredKnigavuheParserV2.HOME_SECTION_POPULAR_WEEK),
        )
        assertEquals(
            "https://knigavuhe.org/popular/?w=month",
            AbredKnigavuheParserV2.homeSectionUrl(AbredKnigavuheParserV2.HOME_SECTION_POPULAR_MONTH),
        )
    }
}