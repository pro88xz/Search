package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Time left on a running download (DownloadEta): rounded, and silent when it would mean nothing. */
class DownloadEtaTest {

    @Test fun roundsToReadableSteps() {
        assertEquals("a few seconds left", DownloadEta.format(3))
        assertEquals("15 s left", DownloadEta.format(12))
        assertEquals("1 min left", DownloadEta.format(60))
        assertEquals("2 min left", DownloadEta.format(61))
        assertEquals("1 h left", DownloadEta.format(3600))
        assertEquals("1 h 5 min left", DownloadEta.format(3600 + 4 * 60 + 1))
        assertEquals("2 h left", DownloadEta.format(2 * 3600 - 10))
    }

    @Test fun fromBytesAndSpeed() {
        // 120 MB left at 2 MB/s: one minute.
        assertEquals("1 min left", DownloadEta.text(120L * 1_000_000, 2_000_000.0))
    }

    @Test fun nothingWhenItWouldMeanNothing() {
        assertEquals("", DownloadEta.text(0, 1000.0))
        assertEquals("", DownloadEta.text(1000, 0.0))
        assertEquals("", DownloadEta.format(2 * 86_400L))
    }
}
