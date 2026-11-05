package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unused sites lose their permissions (SitePermissions.findUnused), as Chrome's do after 60 days. */
class UnusedPermissionsTest {

    private val day = 86_400_000L
    private val now = 1_000 * day

    @Test fun grantsUnusedFor60DaysAreTakenBack() {
        val answers = mapOf(
            "cammic|https://old.test" to SitePermissions.ALLOW,
            "location|https://old.test" to SitePermissions.ALLOW,
            "location|https://recent.test" to SitePermissions.ALLOW
        )
        val used = mapOf("https://old.test" to now - 61 * day, "https://recent.test" to now - 59 * day)
        val found = SitePermissions.findUnused(answers, used, now)
        assertEquals(listOf("cammic|https://old.test", "location|https://old.test"), found.revoke.sorted())
        assertEquals(emptyList<String>(), found.stamp)
    }

    @Test fun refusalsStayRefused() {
        val answers = mapOf("popups|https://old.test" to SitePermissions.DENY)
        val used = mapOf("https://old.test" to now - 400 * day)
        assertEquals(emptyList<String>(), SitePermissions.findUnused(answers, used, now).revoke)
    }

    @Test fun grantsFromBeforeTheClockStartItRatherThanGo() {
        val answers = mapOf("popups|https://legacy.test" to SitePermissions.ALLOW)
        val found = SitePermissions.findUnused(answers, emptyMap<String, Long>(), now)
        assertEquals(emptyList<String>(), found.revoke)
        assertEquals(listOf("https://legacy.test"), found.stamp)
    }
}
