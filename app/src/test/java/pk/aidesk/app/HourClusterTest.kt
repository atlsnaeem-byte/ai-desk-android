package pk.aidesk.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mStructBook.Cfg

class HourClusterTest {
    // 2026-11-25 is a Wednesday. 05:00 UTC = 10:00 PKT (block 08-12)
    private val wed10pkt = java.time.Instant.parse("2026-11-25T05:00:00Z").toEpochMilli()
    private val wed01pkt = java.time.Instant.parse("2026-11-24T20:00:00Z").toEpochMilli()   // Tue 20:00 UTC = Wed 01:00 PKT
    private val sat10pkt = java.time.Instant.parse("2026-11-28T05:00:00Z").toEpochMilli()
    private val fri22pkt = java.time.Instant.parse("2026-11-27T17:00:00Z").toEpochMilli()

    @Test fun bits() {
        assertEquals(2, Cfg.bit(wed10pkt))
        assertEquals(0, Cfg.bit(wed01pkt))
        assertEquals(8, Cfg.bit(sat10pkt))
        assertEquals(5, Cfg.bit(fri22pkt))
        assertEquals("weekday 08-12 PKT", Cfg.label(wed10pkt))
    }

    @Test fun presets() {
        val all = Cfg(); val weak = Cfg(hoursMask = Cfg.SKIP_WEAK); val nw = Cfg(hoursMask = Cfg.NIGHT_WEEKEND)
        assertTrue(all.inHours(wed10pkt) && all.allHours)
        assertFalse(weak.inHours(wed10pkt)); assertFalse(weak.inHours(fri22pkt))
        assertTrue(weak.inHours(wed01pkt)); assertTrue(weak.inHours(sat10pkt))
        assertTrue(nw.inHours(wed01pkt)); assertTrue(nw.inHours(sat10pkt)); assertFalse(nw.inHours(wed10pkt))
        assertEquals(Cfg.SKIP_WEAK, Cfg.of(weak.json()).hoursMask)
        assertEquals(Cfg.ALL_HOURS, Cfg.of(org.json.JSONObject().put("v", 4)).hoursMask)
    }
}
