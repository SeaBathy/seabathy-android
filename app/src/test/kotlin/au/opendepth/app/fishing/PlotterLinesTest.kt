package au.opendepth.app.fishing

import au.opendepth.app.map.DepthContours
import au.opendepth.app.nav.DepthUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotterLinesTest {

    private val placement = object : DepthContours.Placement {
        override fun lng(x: Double) = 153.0 + x * 1e-3
        override fun lat(y: Double) = -27.0 - y * 1e-3
        override fun col(lng: Double) = (lng - 153.0) / 1e-3
        override fun row(lat: Double) = (-27.0 - lat) / 1e-3
    }

    @Test
    fun `a contour is traced at exactly the depth asked for, in degrees`() {
        // Deepening 1 m a column eastward, from 5 m to 54 m.
        val cols = 50
        val rows = 20
        val values = FloatArray(cols * rows) { i -> -(5f + i % cols) }
        val lines = PlotterLines.contourAt(values, cols, rows, 20.0, placement)
        assertEquals(1, lines.size)
        val line = lines.single()
        // Column 15 is 20 m deep: the line runs north-south along it.
        for (i in line.indices step 2) assertEquals(153.015, line[i], 1e-9)
        assertEquals(-27.0, line.maxOf { if (it < 0) it else Double.NEGATIVE_INFINITY }, 1e-9)
        // No water that deep: nothing.
        assertTrue(PlotterLines.contourAt(values, cols, rows, 80.0, placement).isEmpty())
        assertTrue(PlotterLines.contourAt(values, cols, rows, 0.0, placement).isEmpty())
    }

    @Test
    fun `depths are typed in the depth unit and kept as metres`() {
        assertEquals(listOf(10.0, 20.0, 30.0), PlotterLines.parseDepths("30, 10;20 20 nonsense", DepthUnit.METRES))
        assertEquals(listOf(20.0, 40.0), PlotterLines.parseDepths("20m 40 m", DepthUnit.METRES))
        val feet = PlotterLines.parseDepths("60 ft, 100", DepthUnit.FEET)
        assertEquals(18.288, feet[0], 1e-5)
        assertEquals(30.48, feet[1], 1e-5)
        assertEquals("60, 100", PlotterLines.depthsText(feet, DepthUnit.FEET))
        assertTrue(PlotterLines.parseDepths("", DepthUnit.METRES).isEmpty())
        assertEquals(PlotterLines.MAX_DEPTHS, PlotterLines.parseDepths((1..40).joinToString(","), DepthUnit.METRES).size)
    }

    @Test
    fun `suggested depths are a few round numbers within the water on screen`() {
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), PlotterLines.suggestDepths(4.0, 48.0, DepthUnit.METRES))
        assertEquals(listOf(200.0, 400.0, 600.0, 800.0), PlotterLines.suggestDepths(120.0, 900.0, DepthUnit.METRES))
        assertTrue(PlotterLines.suggestDepths(20.0, 20.0, DepthUnit.METRES).isEmpty())
        assertEquals("20 m contour", PlotterLines.contourName(20.0, DepthUnit.METRES))
        assertEquals("60 ft contour", PlotterLines.contourName(18.288, DepthUnit.FEET))
    }

    @Test
    fun `the sea conditions' lines are named for a plotter`() {
        val json = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"kind":"temp","label":"21.5°"},"geometry":{"type":"LineString","coordinates":[[153.1,-27.1],[153.2,-27.2]]}},
            {"type":"Feature","properties":{"kind":"minor","label":"21.25°"},"geometry":{"type":"LineString","coordinates":[[153.1,-27.1],[153.2,-27.2]]}},
            {"type":"Feature","properties":{"kind":"band","label":"22.0°C"},"geometry":{"type":"LineString","coordinates":[[153.1,-27.1],[153.2,-27.2]]}},
            {"type":"Feature","properties":{"kind":"cold_light","label":"-20 cm"},"geometry":{"type":"LineString","coordinates":[[153.1,-27.1],[153.2,-27.2]]}},
            {"type":"Feature","properties":{"kind":"warm","label":"W +25 cm"},"geometry":{"type":"Point","coordinates":[153.3,-27.3]}},
            {"type":"Feature","properties":{"kind":"temp","label":"20°"},"geometry":{"type":"LineString","coordinates":[[153.1,-27.1]]}}
        ]}"""
        val (lines, points) = PlotterLines.seaLines(json, "°C")
        assertEquals(listOf("21.5 °C", "21.25 °C", "Band edge 22.0 °C", "Cold eddy edge -20 cm"), lines.map { it.name })
        assertEquals(153.2, lines[0].points[2], 1e-9)
        assertEquals(-27.2, lines[0].points[3], 1e-9)
        assertEquals("Warm eddy +25 cm", points.single().name)
        assertEquals(-27.3, points.single().lat, 1e-9)
        assertEquals(153.3, points.single().lng, 1e-9)
        assertTrue(PlotterLines.seaLines("not json", "°C").first.isEmpty())
    }
}
