package com.ets2gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFileTest {
    // exactly what the Windows app wrote for Paris -> Frankfurt am Main -> Berlin
    private val windowsFile = """{
  "format": "ets2gps-trip",
  "version": 1,
  "note": "x",
  "trips": [
    { "name": "Paris \u2192 Berlin", "mode": "HEAVY", "avoidFerries": false,
      "start": { "x": -30901.2, "z": 4862, "name": "Paris" },
      "stops": [ { "x": -7045, "z": 1725.9, "name": "Frankfurt am Main" } ],
      "dest": { "x": 8897.6, "z": -11127.3, "name": "Berlin" }, "lengthM": 63022 }
  ]
}"""

    @Test
    fun readsAFileWrittenByTheWindowsApp() {
        val r = TripFile.parse(windowsFile)
        assertNull(r.error)
        assertEquals(1, r.trips.size); assertEquals(0, r.skipped)
        val t = r.trips[0]
        assertEquals("Paris \u2192 Berlin", t.name); assertEquals("HEAVY", t.mode)
        assertEquals("Paris", t.start?.name); assertEquals(-30901.2f, t.start!!.x, 0.01f)
        assertEquals(listOf("Frankfurt am Main"), t.stops.map { it.name })
        assertEquals("Berlin", t.dest.name); assertEquals(63022f, t.lengthM, 0.5f)
    }

    @Test
    fun roundTrips() {
        val a = TripFile.parse(windowsFile).trips
        val b = TripFile.parse(TripFile.toJson(a))
        assertNull(b.error); assertEquals(0, b.skipped)
        assertEquals(a[0].name, b.trips[0].name); assertEquals(a[0].dest.x, b.trips[0].dest.x, 0.01f)
        assertEquals(a[0].stops.size, b.trips[0].stops.size); assertEquals(a[0].start!!.name, b.trips[0].start!!.name)
    }

    @Test
    fun skipsWhatIsNotValid() {
        val bad = """{"format":"ets2gps-trip","version":1,"trips":[
            {"name":"far","mode":"HEAVY","dest":{"x":99999999,"z":1},"stops":[]},
            {"name":"<script>x</script>","mode":"EVIL","dest":{"x":100.5,"z":200.5,"name":"ok"},"stops":[{"x":"nope","z":1}]},
            {"name":"fine","mode":"tourist","dest":{"x":7224,"z":15911,"name":"Somewhere"},"stops":[]}]}"""
        val r = TripFile.parse(bad)
        assertNull(r.error)
        assertEquals(1, r.trips.size); assertEquals(2, r.skipped)
        assertEquals("fine", r.trips[0].name); assertEquals("TOURIST", r.trips[0].mode)
    }

    @Test
    fun rejectsOtherFiles() {
        assertNotNull(TripFile.parse("not json").error)
        assertNotNull(TripFile.parse("{\"format\":\"other\",\"trips\":[]}").error)
        assertNotNull(TripFile.parse("{\"format\":\"ets2gps-trip\",\"version\":99,\"trips\":[]}").error)
        assertNotNull(TripFile.parse("x".repeat(TripFile.MAX_CHARS + 1)).error)
        assertTrue(TripFile.parse("{\"format\":\"ets2gps-trip\",\"trips\":[]}").trips.isEmpty())
    }
}
