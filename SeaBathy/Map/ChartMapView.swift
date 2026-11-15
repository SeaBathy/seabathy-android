import MapLibre
import SwiftUI

/// The MapLibre map with the OpenFreeMap dark basemap.
struct ChartMapView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: MapStyles.dark)
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.backgroundColor = Theme.mapBackgroundUI
        map.delegate = context.coordinator

        let camera = CameraStore.load()
        map.setCenter(camera.center, zoomLevel: camera.zoom, direction: camera.bearing, animated: false)
        return map
    }

    func updateUIView(_ uiView: MLNMapView, context: Context) {}

    final class Coordinator: NSObject, MLNMapViewDelegate {
        private var usedFallback = false

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            CameraStore.save(MapCamera(center: mapView.centerCoordinate,
                                       zoom: mapView.zoomLevel,
                                       bearing: mapView.direction))
        }

        /// No internet on first load: fall back to a blank local style rather than an empty screen.
        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            guard !usedFallback else { return }
            usedFallback = true
            mapView.styleURL = MapStyles.offlineFallback()
        }
    }
}
