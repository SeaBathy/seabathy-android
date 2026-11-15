import Foundation

/// Base map styles, matching MapStyleFactory.kt on Android.
enum MapStyles {
    /// OpenFreeMap: OpenStreetMap data, free, with no account or key.
    static let dark = URL(string: "https://tiles.openfreemap.org/styles/dark")!
    static let osm = URL(string: "https://tiles.openfreemap.org/styles/liberty")!

    /// A blank, network-free style, used when the online basemap can't load,
    /// so the map still shows something (and later, the user's own layers).
    static func offlineFallback() -> URL {
        let json = """
        {"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#111418"}}]}
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("seabathy-empty-style.json")
        try? json.data(using: .utf8)?.write(to: url, options: .atomic)
        return url
    }
}
