package app.termora.plugins.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SyncIntervalTest {
    @Test
    fun intervalChoicesMapToExpectedDurations() {
        assertNull(SyncInterval.Disabled.duration)
        assertEquals(1.minutes, SyncInterval.OneMinute.duration)
        assertEquals(5.minutes, SyncInterval.FiveMinutes.duration)
        assertEquals(10.minutes, SyncInterval.TenMinutes.duration)
        assertEquals(1.hours, SyncInterval.OneHour.duration)
    }
}
