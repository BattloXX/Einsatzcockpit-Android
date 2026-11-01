package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.*
import org.junit.Test

class EcpWidgetLogicTest {
    @Test
    fun displayPriorityCoversAllEightCombinations() {
        val expected = listOf(
            EcpWidgetLogic.Display.EMPTY, EcpWidgetLogic.Display.GSL_LIVE,
            EcpWidgetLogic.Display.GSL_QUEUE, EcpWidgetLogic.Display.GSL_QUEUE,
            EcpWidgetLogic.Display.INCIDENT, EcpWidgetLogic.Display.INCIDENT,
            EcpWidgetLogic.Display.GSL_QUEUE, EcpWidgetLogic.Display.GSL_QUEUE,
        )
        for (bits in 0..7) {
            assertEquals(expected[bits], EcpWidgetLogic.display(bits and 4 != 0, bits and 2 != 0, bits and 1 != 0))
        }
    }

    @Test
    fun expandedBoundaryRemains150() {
        assertFalse(EcpWidgetLogic.isExpanded(0))
        assertFalse(EcpWidgetLogic.isExpanded(149))
        assertTrue(EcpWidgetLogic.isExpanded(150))
        assertTrue(EcpWidgetLogic.isExpanded(151))
    }

    @Test
    fun labelsKeepExercisePhaseAndCounts() {
        assertEquals("LAUFENDER EINSATZ · ANFAHRT", EcpWidgetLogic.incidentLabel(false, "Anfahrt"))
        assertEquals("LAUFENDE ÜBUNG · LÄUFT", EcpWidgetLogic.incidentLabel(true, "läuft"))
        assertEquals("GROSSSCHADENSLAGE · Nord", EcpWidgetLogic.gslLabel(false, "Nord"))
        assertEquals("LAUFENDE ÜBUNG · Nord", EcpWidgetLogic.gslLabel(true, "Nord"))
        assertEquals("1 neu · 2 in Arbeit · 3 erledigt", EcpWidgetLogic.countsLabel(GslLiveCounts(1, 2, 3, 99)))
    }
}
