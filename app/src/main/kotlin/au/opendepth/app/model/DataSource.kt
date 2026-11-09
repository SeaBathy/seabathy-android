package au.opendepth.app.model

/**
 * An online `{z}/{x}/{y}` tile source that an area can be downloaded from as
 * an MBTiles file. A bad link only shows when the tiles fail to come back.
 * [attribution] is shown with any layer downloaded from it; the user is
 * responsible for being allowed to download a source they add.
 */
data class DataSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val attribution: String = "",
    val builtIn: Boolean = false
) {
    companion object {
        /**
         * EOX's Sentinel-2 cloudless (Copernicus Sentinel-2 imagery). Only
         * the 2016 edition is CC BY 4.0; later editions, including this
         * 2024 layer, are CC BY-NC-SA 4.0 (non-commercial use). Attribution
         * wording per EOX's terms: https://s2maps.eu.
         */
        val DEFAULT_PRESET = DataSource(
            id = "eox-s2cloudless",
            name = "Sentinel-2 cloudless (EOX)",
            urlTemplate = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg",
            attribution = "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH " +
                "(Contains modified Copernicus Sentinel data 2024)",
            builtIn = true
        )
    }
}
