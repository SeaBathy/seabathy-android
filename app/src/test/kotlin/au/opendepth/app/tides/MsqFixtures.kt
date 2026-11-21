package au.opendepth.app.tides

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Synthetic Maritime Safety Queensland high/low and 10-minute CSVs, laid out like the published ones. */
object MsqFixtures {

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val HEADER = DateTimeFormatter.ofPattern("dd MM yyyy HH:mm")

    /** One row: local (Queensland) time, 1 for high or -1 for low, height above LAT. */
    data class Row(val local: LocalDateTime, val ind: Int, val height: Double)

    /**
     * Highs and lows every 6 h 12 min through [year] (2025 on): lows near 1 m, highs near
     * 5 m, carrying on from one year into the next as real files do.
     */
    fun yearRows(year: Int): List<Row> {
        val rows = mutableListOf<Row>()
        var t = LocalDateTime.of(2025, 1, 1, 2, 41)
        var high = false
        var i = 0
        while (t.year <= year) {
            if (t.year == year) {
                val wobble = 0.3 * kotlin.math.sin(i / 30.0)
                rows += Row(t, if (high) 1 else -1, if (high) 5.0 + wobble else 1.0 - wobble / 2)
            }
            high = !high
            i++
            t = t.plusMinutes(372)
        }
        return rows
    }

    fun csv(
        rows: List<Row>,
        id: String = "060010A",
        name: String = "HALF TIDE TUG HARBOUR",
        lat: String = "-21 17",
        lng: String = " 149 18",
        zone: String = "1000E",
        datum: String = "LOWEST ASTRONOMICAL TIDE DATUM",
        dataClass: String = "HILO",
        validReadings: Int = rows.size,
        lastReading: String? = rows.lastOrNull()?.local?.format(HEADER)
    ): String = buildString {
        fun line(s: String) = append(s).append("\r\n")
        line("Tidal readings provided on 30 Oct 2025 14:54 by:-")
        line("The Tidal Unit Maritime Safety Queensland - Brisbane")
        line("IDENTIFICATION")
        line("Tidal Station Number                       =,$id")
        line("Tidal Station Name                         =,$name")
        line("Geographical Quadrant North/East =,4")
        line("Latitude  Degrees Minutes                  =,$lat")
        line("Longitude Degrees Minutes                  =,$lng")
        line("Tidal Heights                              =,HT")
        line("Time Zone                                  =,$zone")
        line("Station Datum                              =,LOWEST ASTRONOMICAL TIDE")
        line("READINGS")
        line("Data Type is Prediction   =,P")
        line("Data Class                                 =,$dataClass")
        line("Readings in this file are referred to       ,$datum")
        line("      which is the following height (metres) above/below the Station Datum    =,0.000 ,")
        line("Number of valid readings                   =,$validReadings")
        line("Date and time of first reading             =,01 01 2026 02:41")
        lastReading?.let { line("Date and time of last reading              =,$it") }
        line("Date of Minimum Height                     =,12 08 2026 16:42")
        line("INDICATOR CODES (Ind)")
        line("High Water/Linear Stream Maximum           =, 1")
        line("Low Water/Linear Stream Minimum            =,-1")
        line("Date       , Time  ,Ind , Reading")
        rows.forEach { r ->
            line(String.format(java.util.Locale.US, "%s , %s , %2d , %7.3f", r.local.format(DATE), r.local.format(TIME), r.ind, r.height))
        }
    }

    private val EPOCH = LocalDateTime.of(2025, 1, 1, 0, 0)

    /** A mixed tide (a strong once-a-day part, where half cosines fit badly), metres above LAT to the millimetre. */
    fun mixedHeight(t: LocalDateTime): Double {
        val hours = java.time.Duration.between(EPOCH, t).toMinutes() / 60.0
        val h = 2.0 + 1.1 * kotlin.math.cos(2 * Math.PI * hours / 12.42) + 0.7 * kotlin.math.cos(2 * Math.PI * hours / 23.93 + 1.0)
        return Math.round(h * 1000) / 1000.0
    }

    /** Every 10 minutes (Ind 9) through [year], carrying on from one year into the next. */
    fun intervalRows(year: Int, stepMinutes: Long = 10): List<Row> {
        val rows = mutableListOf<Row>()
        var t = LocalDateTime.of(year, 1, 1, 0, 0)
        while (t.year == year) {
            rows += Row(t, 9, mixedHeight(t))
            t = t.plusMinutes(stepMinutes)
        }
        return rows
    }

    /** The highs and lows of 10-minute [rows] (their turning points), as a high/low file lists them. */
    fun extremesOf(rows: List<Row>): List<Row> = (1 until rows.size - 1).mapNotNull { i ->
        val (a, b, c) = Triple(rows[i - 1].height, rows[i].height, rows[i + 1].height)
        when {
            b > a && b >= c -> Row(rows[i].local, 1, b)
            b < a && b <= c -> Row(rows[i].local, -1, b)
            else -> null
        }
    }

    fun intervalCsv(
        rows: List<Row>,
        id: String = "777001",
        lat: String = "-20 24",
        lng: String = " 148 54",
        minutes: Int = 10,
        datum: String = "LOWEST ASTRONOMICAL TIDE DATUM",
        dataClass: String = "INTERVAL",
        validReadings: Int = rows.size,
        lastReading: String? = rows.lastOrNull()?.local?.format(HEADER)
    ): String = buildString {
        fun line(s: String) = append(s).append("\r\n")
        line("Tidal readings provided on 29 Oct 2025 14:51 by:-")
        line("IDENTIFICATION")
        line("Tidal Station Number                       =,$id")
        line("Tidal Station Name                         =,STRAIT ISLAND")
        line("Latitude  Degrees Minutes                  =,$lat")
        line("Longitude Degrees Minutes                  =,$lng")
        line("Time Zone                                  =,1000E")
        line("Station Datum                              =,LOWEST ASTRONOMICAL TIDE")
        line("READINGS")
        line("Data Type is Prediction   =,P")
        line("Data Class                                 =,$dataClass")
        line("The readings have a fixed interval minutes    =,$minutes")
        line("Readings in this file are referred to       ,$datum")
        line("Number of valid readings                   =,$validReadings")
        lastReading?.let { line("Date and time of last reading              =,$it") }
        line("Interval reading                           =, 9")
        line("Date       , Time  ,Ind , Reading")
        rows.forEach { r ->
            line(String.format(java.util.Locale.US, "%s , %s , %2d , %7.3f", r.local.format(DATE), r.local.format(TIME), r.ind, r.height))
        }
    }

    const val INDEX = """
{
 "separate": [ {"name": "Behind the island", "south": -21.0, "west": 149.5, "north": -20.9, "east": 149.6} ],
 "stations": [
  {"id": "060010A", "name": "Half Tide Tug Harbour", "lat": -21.2833, "lng": 149.3, "reachKm": 15,
   "dataset": "half-tide-tug-harbour-tide-gauge-predicted-high-low-data",
   "years": {"2026": "https://www.data.qld.gov.au/dataset/a/resource/b/download/q060010a_half-tide-tug-harbour_2026_hilo.csv",
             "2027": "https://www.data.qld.gov.au/dataset/a/resource/c/download/q060010a_half-tide-tug-harbour_2027_hilo.csv"}},
  {"id": "100826", "name": "Mackay", "lat": -21.1, "lng": 149.2167, "reachKm": 15,
   "dataset": "mackay-tide-gauge-predicted-high-low-data", "years": {}, "mslAboveLatM": 2.9},
  {"id": "999001", "name": "Up the River", "lat": -20.95, "lng": 149.35,
   "reachKm": 8, "dataset": "up-the-river-tide-gauge-predicted-high-low-data", "years": {}},
  {"id": "777001", "name": "Strait Island", "lat": -20.4, "lng": 148.9, "reachKm": 15,
   "dataset": "strait-island-tide-gauge-predicted-high-low-data",
   "years": {"2026": "https://www.data.qld.gov.au/dataset/s/resource/h26/download/q777001_strait-island_2026_hilo.csv",
             "2027": "https://www.data.qld.gov.au/dataset/s/resource/h27/download/q777001_strait-island_2027_hilo.csv"},
   "mslAboveLatM": 2.0, "useInterval": true, "intervalDataset": "strait-island-tide-gauge-predicted-interval-data",
   "intervalYears": {"2026": "https://www.data.qld.gov.au/dataset/i/resource/i26/download/p777001_strait-island_2026_10min.csv",
                     "2027": "https://www.data.qld.gov.au/dataset/i/resource/i27/download/p777001_strait-island_2027_10min.csv"}}
 ]
}
"""
}
