import MapLibre
import SwiftUI

/// The MapLibre map. All behaviour lives in MapController and the features; this only wires UIKit events.
struct ChartMapView: UIViewRepresentable {
    let controller: MapController
    let initialCamera: AppSettings.Camera
    let styleURL: URL

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: styleURL)
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.backgroundColor = Theme.mapBackgroundUI
        map.delegate = context.coordinator
        // The credits line under the chart names every source; MapLibre's logo and (i) only took room.
        map.logoView.isHidden = true
        map.attributionButton.isHidden = true
        map.compassViewPosition = .bottomLeft
        map.compassViewMargins = CGPoint(x: 12, y: 64)
        let camera = MLNMapCamera()
        camera.centerCoordinate = CLLocationCoordinate2D(latitude: initialCamera.lat, longitude: initialCamera.lng)
        map.setCenter(camera.centerCoordinate, zoomLevel: initialCamera.zoom, direction: initialCamera.bearing, animated: false)

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        // Let MapLibre's own double-tap zoom win over a single tap.
        for recognizer in map.gestureRecognizers ?? [] where (recognizer as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 {
            tap.require(toFail: recognizer)
        }
        map.addGestureRecognizer(tap)
        let long = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleLongPress(_:)))
        map.addGestureRecognizer(long)
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePan(_:)))
        pan.delegate = context.coordinator
        pan.maximumNumberOfTouches = 1
        map.addGestureRecognizer(pan)

        controller.attach(map)
        return map
    }

    func updateUIView(_ uiView: MLNMapView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, MLNMapViewDelegate, UIGestureRecognizerDelegate {
        let controller: MapController
        init(controller: MapController) { self.controller = controller }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            controller.styleDidLoad(style)
        }

        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            controller.styleDidFail(error)
        }

        func mapViewRegionIsChanging(_ mapView: MLNMapView) {
            controller.cameraDidMove()
        }

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            controller.cameraDidBecomeIdle()
        }

        @objc func handleTap(_ g: UITapGestureRecognizer) {
            guard g.state == .ended, let map = g.view as? MLNMapView else { return }
            let point = g.location(in: map)
            controller.onTap?(map.convert(point, toCoordinateFrom: map), point)
        }

        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let map = g.view as? MLNMapView else { return }
            let point = g.location(in: map)
            controller.onLongPress?(map.convert(point, toCoordinateFrom: map), point)
        }

        @objc func handlePan(_ g: UIPanGestureRecognizer) {
            if g.state == .began { controller.onUserPan?() }
        }

        // Observe drags without stealing them from MapLibre.
        nonisolated func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}
