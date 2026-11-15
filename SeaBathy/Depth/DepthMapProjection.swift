import Foundation

/**
 Forward and inverse conversion between WGS84 geographic coordinates and the projected
 coordinate system of an imported raster (Android geotiff/MapProjection.kt).

 Only the projections bathymetric GeoTIFFs realistically ship in are supported:
 geographic degrees, Web Mercator and UTM (WGS84, and the GDA94/GDA2020 MGA zones).
 Anything else is rejected at import so the user gets a clear message instead of a
 silently misplaced layer.
 */
enum DepthMapProjection: Equatable {
    case geographic
    case webMercator
    case utm(zone: Int, northernHemisphere: Bool)

    static let earthRadius = 6378137.0

    private static let mercatorLatLimit = 85.05112878
    private static let flattening = 1.0 / 298.257223563
    private static let falseEasting = 500000.0
    private static let southernFalseNorthing = 10000000.0
    private static let scaleFactor = 0.9996
    private static let meridionalRadius = 6367449.145823415

    private static let alpha: [Double] = [8.377318206244698e-4, 7.608527773572309e-7, 1.197645503329453e-9, 2.429170728856296e-12]
    private static let beta: [Double] = [8.377321640579486e-4, 5.905869722089504e-8, 1.673482665343455e-10, 2.165020615537011e-13]
    private static let delta: [Double] = [3.356551485604286e-3, 6.571873263127465e-6, 1.764656426977931e-8, 5.400482155864202e-11]

    /// Human readable name shown in the layer panel and error messages.
    var label: String {
        switch self {
        case .geographic: return "WGS84 geographic"
        case .webMercator: return "Web Mercator"
        case let .utm(zone, north): return "UTM zone \(zone)\(north ? "N" : "S")"
        }
    }

    /// Geographic degrees to projected units, as (x, y).
    func forward(lat: Double, lng: Double) -> (x: Double, y: Double) {
        switch self {
        case .geographic:
            return (lng, lat)
        case .webMercator:
            let clamped = min(max(lat, -Self.mercatorLatLimit), Self.mercatorLatLimit)
            return (Self.earthRadius * Self.rad(lng),
                    Self.earthRadius * log(tan(Double.pi / 4 + Self.rad(clamped) / 2)))
        case let .utm(zone, north):
            let centralMeridian = Self.rad(Double(zone) * 6.0 - 183.0)
            let falseNorthing = north ? 0.0 : Self.southernFalseNorthing
            let phi = Self.rad(min(max(lat, -89.999999), 89.999999))
            let deltaLambda = Self.rad(lng) - centralMeridian
            let n = Self.flattening / (2.0 - Self.flattening)
            let twoRootN = 2.0 * n.squareRoot() / (1.0 + n)

            let t = sinh(Self.atanhValue(sin(phi)) - twoRootN * Self.atanhValue(twoRootN * sin(phi)))
            let xiPrime = atan(t / cos(deltaLambda))
            let etaPrime = Self.atanhValue(sin(deltaLambda) / (1.0 + t * t).squareRoot())

            var xi = xiPrime
            var eta = etaPrime
            for index in Self.alpha.indices {
                let order = 2.0 * Double(index + 1)
                xi += Self.alpha[index] * sin(order * xiPrime) * cosh(order * etaPrime)
                eta += Self.alpha[index] * cos(order * xiPrime) * sinh(order * etaPrime)
            }
            return (Self.falseEasting + Self.scaleFactor * Self.meridionalRadius * eta,
                    falseNorthing + Self.scaleFactor * Self.meridionalRadius * xi)
        }
    }

    /// Projected units to (lat, lng) degrees, or nil when unrepresentable.
    func inverse(x: Double, y: Double) -> (lat: Double, lng: Double)? {
        switch self {
        case .geographic:
            if abs(x) > 180 || abs(y) > 90 { return nil }
            return (y, x)
        case .webMercator:
            let lng = Self.deg(x / Self.earthRadius)
            let lat = Self.deg(2.0 * atan(exp(y / Self.earthRadius)) - Double.pi / 2)
            if abs(lng) > 180 || abs(lat) > 90 { return nil }
            return (lat, lng)
        case let .utm(zone, north):
            let centralMeridian = Self.rad(Double(zone) * 6.0 - 183.0)
            let falseNorthing = north ? 0.0 : Self.southernFalseNorthing
            let xi = (y - falseNorthing) / (Self.scaleFactor * Self.meridionalRadius)
            let eta = (x - Self.falseEasting) / (Self.scaleFactor * Self.meridionalRadius)

            var xiPrime = xi
            var etaPrime = eta
            for index in Self.beta.indices {
                let order = 2.0 * Double(index + 1)
                xiPrime -= Self.beta[index] * sin(order * xi) * cosh(order * eta)
                etaPrime -= Self.beta[index] * cos(order * xi) * sinh(order * eta)
            }
            let chi = asin(sin(xiPrime) / cosh(etaPrime))
            var phi = chi
            for index in Self.delta.indices {
                phi += Self.delta[index] * sin(2.0 * Double(index + 1) * chi)
            }
            let lat = Self.deg(phi)
            let lng = Self.deg(centralMeridian + atan(sinh(etaPrime) / cos(xiPrime)))
            if !lat.isFinite || !lng.isFinite { return nil }
            if abs(lat) > 90 || abs(lng) > 180 { return nil }
            return (lat, lng)
        }
    }

    /// Resolves a GeoTIFF projected CRS code, or nil when unsupported.
    static func fromProjectedCode(_ code: Int) -> DepthMapProjection? {
        switch code {
        case 3857, 3785, 900913: return .webMercator
        case 32601...32660: return .utm(zone: code - 32600, northernHemisphere: true)
        case 32701...32760: return .utm(zone: code - 32700, northernHemisphere: false)
        // GDA94 and GDA2020 MGA zones: UTM south on GRS80, whose flattening differs
        // from WGS84's by far less than a raster cell.
        case 28348...28358: return .utm(zone: code - 28300, northernHemisphere: false)
        case 7846...7859: return .utm(zone: code - 7800, northernHemisphere: false)
        default: return nil
        }
    }

    /// Resolves a GeoTIFF geographic CRS code, or nil when unsupported.
    static func fromGeographicCode(_ code: Int) -> DepthMapProjection? {
        switch code {
        case 0, 4326, 4269, 4283, 4979, 7844: return .geographic
        default: return nil
        }
    }

    private static func rad(_ d: Double) -> Double { d * Double.pi / 180 }
    private static func deg(_ r: Double) -> Double { r * 180 / Double.pi }
    private static func atanhValue(_ value: Double) -> Double { 0.5 * log((1.0 + value) / (1.0 - value)) }
}
