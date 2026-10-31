package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmToneTest {
    @Test
    fun missingAndUnknownIdsUseStandard() {
        listOf(null, "", "unbekannt", " ").forEach { id ->
            assertEquals("standard", AlarmTone.normalize(id))
        }
    }

    @Test
    fun validIdsArePreserved() {
        listOf("standard", "sirene", "martinshorn").forEach { id ->
            assertEquals(id, AlarmTone.normalize(id))
        }
    }

    @Test
    fun allIdsHaveDisplayNames() {
        assertEquals(listOf("standard", "sirene", "martinshorn"), AlarmTone.ids)
        assertEquals(listOf("Standard-Alarm", "Sirene", "Martinshorn"), AlarmTone.ids.map { AlarmTone.displayNames[it] })
        AlarmTone.ids.forEach { id ->
            assertTrue(AlarmTone.displayNames[id]?.isNotBlank() == true)
        }
    }
}
