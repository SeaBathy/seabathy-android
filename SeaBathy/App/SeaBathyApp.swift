import SwiftUI

@main
struct SeaBathyApp: App {
    @StateObject private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(app)
                .preferredColorScheme(.dark)
                .tint(Theme.teal)
                .onOpenURL { url in
                    // A file shared or opened from the Files app.
                    let ext = url.pathExtension.lowercased()
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    let local = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
                    try? FileManager.default.removeItem(at: local)
                    guard (try? FileManager.default.copyItem(at: url, to: local)) != nil else {
                        app.alert("Couldn't open the file", "SeaBathy couldn't read \(url.lastPathComponent).")
                        return
                    }
                    if ext == "gpx" {
                        GpxActions.importFile(app: app, url: local)
                    } else {
                        app.layers.importFile(local)
                    }
                }
        }
    }
}
