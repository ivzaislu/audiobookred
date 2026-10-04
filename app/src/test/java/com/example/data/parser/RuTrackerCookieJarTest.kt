package com.example.data.parser

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerCookieJarTest {
    @Test
    fun hostOnlyAndPathCookiesAreScopedPerRequest() {
        val jar = RuTrackerCookieJar()
        val loginUrl = "https://rutracker.org/forum/login.php".toHttpUrl()
        val forumOnly = Cookie.parse(
            loginUrl,
            "forum_session=abc; Path=/forum; Secure; HttpOnly",
        )!!
        val rootDomain = Cookie.parse(
            loginUrl,
            "root_session=def; Domain=rutracker.org; Path=/; Secure",
        )!!

        jar.saveFromResponse(loginUrl, listOf(forumOnly, rootDomain))

        assertEquals(
            setOf("forum_session", "root_session"),
            jar.loadForRequest(
                "https://rutracker.org/forum/tracker.php".toHttpUrl()
            ).mapTo(mutableSetOf()) { it.name },
        )
        assertEquals(
            listOf("root_session"),
            jar.loadForRequest(
                "https://rutracker.org/outside".toHttpUrl()
            ).map { it.name },
        )
        assertEquals(
            listOf("root_session"),
            jar.loadForRequest(
                "https://sub.rutracker.org/forum/viewtopic.php".toHttpUrl()
            ).map { it.name },
        )
        assertTrue(
            jar.loadForRequest(
                "https://example.org/forum/viewtopic.php".toHttpUrl()
            ).isEmpty()
        )
    }

    @Test
    fun expiredCookieDeletesExistingCookieWithSameScope() {
        var now = 1_700_000_000_000L
        val jar = RuTrackerCookieJar(nowMs = { now })
        val url = "https://rutracker.org/forum/".toHttpUrl()
        val live = Cookie.Builder()
            .name("sid")
            .value("live")
            .expiresAt(now + 60_000L)
            .hostOnlyDomain("rutracker.org")
            .path("/forum")
            .secure()
            .build()
        jar.saveFromResponse(url, listOf(live))
        assertTrue(jar.hasCookiesFor(url.toString()))

        val deleted = Cookie.Builder()
            .name("sid")
            .value("gone")
            .expiresAt(now - 1L)
            .hostOnlyDomain("rutracker.org")
            .path("/forum")
            .secure()
            .build()
        jar.saveFromResponse(url, listOf(deleted))

        assertFalse(jar.hasCookiesFor(url.toString()))
    }

    @Test
    fun onlyPersistentCookiesSurviveJarRecreation() {
        val now = 1_700_000_000_000L
        var persisted = emptySet<String>()
        val url = "https://rutracker.org/forum/login.php".toHttpUrl()
        val first = RuTrackerCookieJar(
            persistRecords = { persisted = it },
            nowMs = { now },
        )
        val persistent = Cookie.Builder()
            .name("bb_session")
            .value("persistent")
            .expiresAt(now + 86_400_000L)
            .domain("rutracker.org")
            .path("/forum")
            .secure()
            .httpOnly()
            .build()
        val session = Cookie.Builder()
            .name("temporary")
            .value("session")
            .hostOnlyDomain("rutracker.org")
            .path("/")
            .secure()
            .build()

        first.saveFromResponse(url, listOf(persistent, session))

        assertTrue(persisted.isNotEmpty())
        val restored = RuTrackerCookieJar(
            persistentRecords = persisted,
            nowMs = { now },
        )
        val names = restored.loadForRequest(
            "https://rutracker.org/forum/tracker.php".toHttpUrl()
        ).map { it.name }

        assertEquals(listOf("bb_session"), names)
    }

    @Test
    fun restoredCookieKeepsDomainPathSecureAndHttpOnlyAttributes() {
        val now = 1_700_000_000_000L
        var persisted = emptySet<String>()
        val sourceUrl = "https://rutracker.org/forum/login.php".toHttpUrl()
        val first = RuTrackerCookieJar(
            persistRecords = { persisted = it },
            nowMs = { now },
        )
        val cookie = Cookie.Builder()
            .name("sid")
            .value("value")
            .expiresAt(now + 86_400_000L)
            .domain("rutracker.org")
            .path("/forum")
            .secure()
            .httpOnly()
            .build()
        first.saveFromResponse(sourceUrl, listOf(cookie))

        val restored = RuTrackerCookieJar(
            persistentRecords = persisted,
            nowMs = { now },
        )
        val loaded = restored.loadForRequest(
            "https://sub.rutracker.org/forum/viewtopic.php".toHttpUrl()
        ).single()

        assertEquals("rutracker.org", loaded.domain)
        assertEquals("/forum", loaded.path)
        assertTrue(loaded.secure)
        assertTrue(loaded.httpOnly)
        assertFalse(loaded.hostOnly)
        assertTrue(loaded.persistent)
    }

    @Test
    fun browserCookiesAreHostOnlySessionCookiesAndNeverLeaveRuTracker() {
        var persisted = emptySet<String>()
        val jar = RuTrackerCookieJar(
            persistRecords = { persisted = it },
        )

        jar.importBrowserCookieHeader(
            targetUrl = "https://rutracker.org/forum/viewtopic.php?t=1",
            cookieHeader = "cf_clearance=abc123; bb_session=xyz",
        )

        assertEquals(
            setOf("cf_clearance", "bb_session"),
            jar.loadForRequest(
                "https://rutracker.org/forum/tracker.php".toHttpUrl()
            ).mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(
            jar.loadForRequest(
                "https://sub.rutracker.org/forum/tracker.php".toHttpUrl()
            ).isEmpty()
        )
        assertTrue(
            jar.loadForRequest(
                "https://example.org/".toHttpUrl()
            ).isEmpty()
        )
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun staleChallengeRevisionCannotClearFreshBrowserCookies() {
        val jar = RuTrackerCookieJar()
        val target = "https://rutracker.org/forum/viewtopic.php?t=1"

        jar.importBrowserCookieHeader(
            targetUrl = target,
            cookieHeader = "cf_clearance=old; bb_session=login",
        )
        val staleRevision = jar.revision()

        jar.importBrowserCookieHeader(
            targetUrl = target,
            cookieHeader = "cf_clearance=fresh; bb_session=login",
        )

        assertFalse(
            jar.clearForChallengeIfUnchanged(
                targetUrl = target,
                expectedRevision = staleRevision,
            )
        )
        assertTrue(jar.cookieHeaderFor(target).contains("cf_clearance=fresh"))
        assertTrue(jar.cookieHeaderFor(target).contains("bb_session=login"))
    }

    @Test
    fun repeatedBrowserImportStillAdvancesChallengeRevision() {
        val jar = RuTrackerCookieJar()
        val target = "https://rutracker.org/forum/viewtopic.php?t=1"

        jar.importBrowserCookieHeader(
            targetUrl = target,
            cookieHeader = "cf_clearance=same; bb_session=same",
        )
        val beforeChallenge = jar.revision()

        jar.importBrowserCookieHeader(
            targetUrl = target,
            cookieHeader = "cf_clearance=same; bb_session=same",
        )

        assertTrue(jar.revision() > beforeChallenge)
        assertFalse(
            jar.clearForChallengeIfUnchanged(
                targetUrl = target,
                expectedRevision = beforeChallenge,
            )
        )
    }

    @Test
    fun currentChallengeRevisionCanClearStaleCookies() {
        val jar = RuTrackerCookieJar()
        val target = "https://rutracker.org/forum/viewtopic.php?t=1"

        jar.importBrowserCookieHeader(
            targetUrl = target,
            cookieHeader = "cf_clearance=stale; bb_session=old",
        )
        val currentRevision = jar.revision()

        assertTrue(
            jar.clearForChallengeIfUnchanged(
                targetUrl = target,
                expectedRevision = currentRevision,
            )
        )
        assertFalse(jar.hasCookiesFor(target))
    }

    @Test
    fun nonHttpsRuTrackerRequestDoesNotReceiveCookies() {
        val jar = RuTrackerCookieJar()
        jar.importBrowserCookieHeader(
            targetUrl = "https://rutracker.org/forum/",
            cookieHeader = "cf_clearance=abc123",
        )

        assertTrue(
            jar.loadForRequest(
                "http://rutracker.org/forum/".toHttpUrl()
            ).isEmpty()
        )
    }
}
