import CoreLocation
import QuartzCore
import UIKit

/**
 Moving flow lines over the chart (Android FlowLinesView): specks that drift
 with the current (or the wind, waves or swell) and leave short fading trails.
 Drawn on a view laid over the map view, not by the map itself.

 The specks and their trails live in web-Mercator coordinates, so they stay put
 while the map is panned or zoomed. Each frame one transform from Mercator to
 the screen is taken from the map (three projected points).
 */
final class ConditionsFlowLinesView: UIView {

    /// The flow at a point: east and north parts (any unit); nil where there's none (land, off the area).
    typealias Field = (Double, Double) -> (Double, Double)?

    /// Map projection, supplied by the feature (screen point of a coordinate, in this view's space).
    var project: ((CLLocationCoordinate2D) -> CGPoint)?
    /// The map's centre.
    var centre: (() -> CLLocationCoordinate2D?)?

    private static let trail = 12
    private static let newestAlpha: CGFloat = 230 / 255
    private static let oldestAlpha: CGFloat = 20 / 255
    private static let lineWidth: CGFloat = 1.3
    private static let maxStep: Double = 2.4
    private static let pointsPerSpeck: Double = 750
    private static let minSpecks = 200
    private static let maxSpecks = 900
    private static let minLife = 50
    private static let maxLife = 140
    private static let spawnTries = 6

    private var field: Field?
    private var reference = 1.0
    private var south = 0.0, west = 0.0, north = 0.0, east = 0.0
    private var southY = 0.0, northY = 0.0

    private var displayLink: CADisplayLink?
    private(set) var running = false

    // Specks: x is longitude and y Mercator northing, both in radians. Each keeps
    // its last `trail` positions in a ring (newest at head).
    private var count = 0
    private var trailX: [Double] = []
    private var trailY: [Double] = []
    private var head: [Int] = []
    private var length: [Int] = []
    private var ages: [Int] = []
    private var lifetimes: [Int] = []

    // Mercator (radians) to screen points: screen = m * merc + t.
    private var m00 = 0.0, m01 = 0.0, m10 = 0.0, m11 = 0.0, tx = 0.0, ty = 0.0
    private var haveTransform = false
    private var lastSize = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        contentMode = .redraw
        isHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("made in code only")
    }

    /// What to draw: `field` over the area, a speck moving fastest at `reference` strength. Nil stops it.
    func show(field: Field?, reference: Double, south: Double, west: Double, north: Double, east: Double) {
        let newArea = south != self.south || west != self.west || north != self.north || east != self.east
        self.field = field
        self.reference = max(reference, 1e-6)
        self.south = south; self.west = west; self.north = north; self.east = east
        southY = ConditionsPicture.mercatorY(south)
        northY = ConditionsPicture.mercatorY(north)
        guard field != nil else {
            stop()
            return
        }
        if newArea { count = 0 }
        isHidden = false
        if !running {
            running = true
            let proxy = ConditionsDisplayLinkProxy(view: self)
            let link = CADisplayLink(target: proxy, selector: #selector(ConditionsDisplayLinkProxy.tick(_:)))
            link.preferredFramesPerSecond = 25
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
    }

    func stop() {
        running = false
        displayLink?.invalidate()
        displayLink = nil
        count = 0
        isHidden = true
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // A new screen size wants a different number of specks.
        if bounds.size != lastSize {
            lastSize = bounds.size
            count = 0
        }
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil && running { stop() }
    }

    fileprivate func frameTick() {
        guard running else { return }
        step()
        setNeedsDisplay()
    }

    /// Moves every speck one frame along the flow.
    private func step() {
        guard let field else { return }
        haveTransform = takeTransform()
        let width = Double(bounds.width), height = Double(bounds.height)
        if !haveTransform || width == 0 || height == 0 { return }
        if count == 0 { seed() }
        let det = m00 * m11 - m01 * m10
        if det == 0 { return }
        let t = Self.trail
        for i in 0..<count {
            ages[i] += 1
            let at = i * t + head[i]
            let x = trailX[at]
            let y = trailY[at]
            guard ages[i] <= lifetimes[i], let flow = field(ConditionsPicture.latitudeOf(y), x * 180.0 / Double.pi) else {
                respawn(i)
                continue
            }
            let strength = hypot(flow.0, flow.1)
            if strength < 1e-6 { continue }
            // Which way east-and-north points on the screen (the map may be turned).
            let sx = m00 * flow.0 + m01 * flow.1
            let sy = m10 * flow.0 + m11 * flow.1
            let len = hypot(sx, sy)
            if len == 0 { continue }
            let pixels = Self.maxStep * min(1, (strength / reference).squareRoot())
            let dx = sx / len * pixels
            let dy = sy / len * pixels
            // Back to Mercator through the inverse transform.
            let nx = x + (m11 * dx - m01 * dy) / det
            let ny = y + (-m10 * dx + m00 * dy) / det
            let screenX = m00 * nx + m01 * ny + tx
            let screenY = m10 * nx + m11 * ny + ty
            let nLng = nx * 180.0 / Double.pi
            // Off the screen, or out of the area (where the colours stop): start again.
            let offScreen: Bool = screenX < 0.0 || screenY < 0.0 || screenX > width || screenY > height
            let offArea: Bool = nLng < west || nLng > east || ny < southY || ny > northY
            if offScreen || offArea {
                respawn(i)
                continue
            }
            head[i] = (head[i] + 1) % t
            trailX[i * t + head[i]] = nx
            trailY[i * t + head[i]] = ny
            if length[i] < t { length[i] += 1 }
        }
    }

    override func draw(_ rect: CGRect) {
        guard running, haveTransform, count > 0, let ctx = UIGraphicsGetCurrentContext() else { return }
        let t = Self.trail
        // One path per trail step, oldest faintest.
        var paths = (0..<(t - 1)).map { _ in CGMutablePath() }
        for i in 0..<count {
            let n = length[i]
            if n < 2 { continue }
            var k = head[i]
            var x0 = m00 * trailX[i * t + k] + m01 * trailY[i * t + k] + tx
            var y0 = m10 * trailX[i * t + k] + m11 * trailY[i * t + k] + ty
            for s in 0..<(n - 1) {
                k = (k - 1 + t) % t
                let x1 = m00 * trailX[i * t + k] + m01 * trailY[i * t + k] + tx
                let y1 = m10 * trailX[i * t + k] + m11 * trailY[i * t + k] + ty
                paths[s].move(to: CGPoint(x: x0, y: y0))
                paths[s].addLine(to: CGPoint(x: x1, y: y1))
                x0 = x1
                y0 = y1
            }
        }
        ctx.setLineWidth(Self.lineWidth)
        ctx.setLineCap(.round)
        for s in 0..<paths.count where !paths[s].isEmpty {
            let alpha = Self.newestAlpha - (Self.newestAlpha - Self.oldestAlpha) * CGFloat(s) / CGFloat(t - 2)
            ctx.setStrokeColor(UIColor(white: 1, alpha: alpha).cgColor)
            ctx.addPath(paths[s])
            ctx.strokePath()
        }
        paths.removeAll()
    }

    /// Reads the map's current Mercator-to-screen transform. False while the map can't say.
    private func takeTransform() -> Bool {
        guard let project, let centre = centre?() else { return false }
        let lat = min(max(centre.latitude, -80), 80)
        let lng = centre.longitude
        let d = 0.01
        let p0 = project(CLLocationCoordinate2D(latitude: lat, longitude: lng))
        let pe = project(CLLocationCoordinate2D(latitude: lat, longitude: lng + d * 180.0 / Double.pi))
        let y0 = ConditionsPicture.mercatorY(lat)
        let pn = project(CLLocationCoordinate2D(latitude: ConditionsPicture.latitudeOf(y0 + d), longitude: lng))
        m00 = Double(pe.x - p0.x) / d
        m10 = Double(pe.y - p0.y) / d
        m01 = Double(pn.x - p0.x) / d
        m11 = Double(pn.y - p0.y) / d
        let x0 = lng * Double.pi / 180.0
        tx = Double(p0.x) - (m00 * x0 + m01 * y0)
        ty = Double(p0.y) - (m10 * x0 + m11 * y0)
        return m00.isFinite && m11.isFinite && m01.isFinite && m10.isFinite
    }

    private func seed() {
        let area = Double(bounds.width * bounds.height)
        count = min(max(Int(area / Self.pointsPerSpeck), Self.minSpecks), Self.maxSpecks)
        let t = Self.trail
        if trailX.count < count * t {
            trailX = [Double](repeating: 0, count: count * t)
            trailY = [Double](repeating: 0, count: count * t)
            head = [Int](repeating: 0, count: count)
            length = [Int](repeating: 0, count: count)
            ages = [Int](repeating: 0, count: count)
            lifetimes = [Int](repeating: 0, count: count)
        }
        for i in 0..<count {
            respawn(i)
            // Start part way through their lives, so they don't all fade at once.
            ages[i] = Int.random(in: 0..<max(lifetimes[i], 1))
        }
    }

    /// Puts a speck somewhere on screen over the area's water, with no trail yet.
    private func respawn(_ i: Int) {
        let t = Self.trail
        lifetimes[i] = Int.random(in: Self.minLife..<Self.maxLife)
        ages[i] = 0
        head[i] = 0
        length[i] = 1
        let det = m00 * m11 - m01 * m10
        if det != 0, let field {
            let width = Double(bounds.width), height = Double(bounds.height)
            for _ in 0..<Self.spawnTries {
                let sx = Double.random(in: 0..<max(width, 1)) - tx
                let sy = Double.random(in: 0..<max(height, 1)) - ty
                let x = (m11 * sx - m01 * sy) / det
                let y = (-m10 * sx + m00 * sy) / det
                let lat = ConditionsPicture.latitudeOf(y)
                let lng = x * 180.0 / Double.pi
                let inArea: Bool = lat >= south && lat <= north && lng >= west && lng <= east
                if inArea && field(lat, lng) != nil {
                    trailX[i * t] = x
                    trailY[i * t] = y
                    return
                }
            }
        }
        // Nowhere found this time (the area is mostly off screen): park it off screen until next try.
        trailX[i * t] = west * Double.pi / 180.0 - 1
        trailY[i * t] = ConditionsPicture.mercatorY(south)
        ages[i] = lifetimes[i]
        length[i] = 0
    }
}

/// Keeps the display link from holding the view (a display link retains its target).
@MainActor
private final class ConditionsDisplayLinkProxy: NSObject {
    weak var view: ConditionsFlowLinesView?

    init(view: ConditionsFlowLinesView) {
        self.view = view
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let view else {
            link.invalidate()
            return
        }
        view.frameTick()
    }
}
