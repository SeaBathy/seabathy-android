import SwiftUI
import UIKit
import WebKit

/// The full-screen 3D view: the page's web view, or a busy screen while it is being rebuilt.
struct Terrain3DScreen: View {
    @ObservedObject var feature: TerrainFeature
    @EnvironmentObject var app: AppModel

    var body: some View {
        ZStack {
            Color(uiColor: TerrainWebHost.background).ignoresSafeArea()

            if let webView = feature.webView {
                TerrainWebContainer(webView: webView)
            } else {
                VStack(spacing: 14) {
                    ProgressView().tint(Theme.textPrimary)
                    Text("Building 3D view…")
                        .font(.subheadline)
                        .foregroundColor(Theme.textMuted)
                }
                VStack {
                    HStack {
                        Button {
                            feature.close()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(Theme.textPrimary)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Theme.surface))
                        }
                        .accessibilityLabel("Close 3D view")
                        Spacer()
                    }
                    Spacer()
                }
                .padding(12)
            }

            // The chart's toasts sit under this full-screen view, so show them here too.
            if let text = app.toastText {
                VStack {
                    Spacer()
                    Text(text)
                        .font(.subheadline)
                        .foregroundColor(Theme.textPrimary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceRaised.opacity(0.95)))
                        .padding(.horizontal, 24)
                        .padding(.bottom, 96)
                }
                .transition(.opacity)
                .allowsHitTesting(false)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: app.toastText)
        // Sheets opened from 3D (the waypoint editor) can't show from the chart underneath.
        .sheet(item: $app.sheet) { presented in
            presented.content
                .presentationDetents(presented.detents)
                .presentationDragIndicator(.visible)
        }
    }
}

/// Holds the current web view; a rebuilt one replaces the old in place.
private struct TerrainWebContainer: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear
        attach(to: container)
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        if webView.superview !== uiView {
            uiView.subviews.forEach { $0.removeFromSuperview() }
            attach(to: uiView)
        }
    }

    private func attach(to container: UIView) {
        webView.removeFromSuperview()
        webView.frame = container.bounds
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(webView)
    }
}

/**
 Serves and listens to one web view of the 3D page. Every request goes through
 the private `seabathy3d://` scheme: the page's files from the bundle, the grid
 from memory and map tiles via TerrainFeature; anything else is refused.
 */
@MainActor
final class TerrainWebHost: NSObject, WKScriptMessageHandler, WKURLSchemeHandler, WKUIDelegate, WKNavigationDelegate {
    static let scheme = "seabathy3d"
    static let hostName = "app"
    static let handlerName = "seabathy3d"
    static let promptPrefix = "seabathy3d:"
    static let background = UIColor(red: 0x07 / 255.0, green: 0x11 / 255.0, blue: 0x1C / 255.0, alpha: 1)

    static var pageURL: URL { URL(string: "\(scheme)://\(hostName)/terrain3d/index.html")! }

    private weak var feature: TerrainFeature?
    private var closed = false
    /// Tile requests in flight; a request the page stops is dropped from here and never answered.
    private var running: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(feature: TerrainFeature) {
        self.feature = feature
        super.init()
    }

    func makeWebView(userScript: String) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(self, forURLScheme: Self.scheme)
        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: userScript, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        controller.add(self, name: Self.handlerName)
        config.userContentController = controller
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.backgroundColor = Self.background
        view.scrollView.backgroundColor = Self.background
        view.scrollView.isScrollEnabled = false
        view.scrollView.bounces = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.allowsBackForwardNavigationGestures = false
        view.uiDelegate = self
        view.navigationDelegate = self
        return view
    }

    /// Stops everything for `view`; nothing from it reaches the feature afterwards.
    func tearDown(_ view: WKWebView) {
        closed = true
        running.values.forEach { $0.cancel() }
        running.removeAll()
        view.stopLoading()
        view.configuration.userContentController.removeScriptMessageHandler(forName: Self.handlerName)
        view.configuration.userContentController.removeAllUserScripts()
        view.uiDelegate = nil
        view.navigationDelegate = nil
        view.removeFromSuperview()
    }

    // Bridge --------------------------------------------------------------------------

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !closed, let body = message.body as? [String: Any], let name = body["name"] as? String else { return }
        let args = body["args"] as? [Any] ?? []
        feature?.handleBridge(name, args)
    }

    /// Calls that return a value arrive as `prompt` (answered synchronously from the page's side).
    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        guard !closed, prompt.hasPrefix(Self.promptPrefix), let feature else {
            completionHandler(nil)
            return
        }
        let name = String(prompt.dropFirst(Self.promptPrefix.count))
        var args: [Any] = []
        if let text = defaultText, let data = text.data(using: .utf8),
           let parsed = (try? JSONSerialization.jsonObject(with: data)) as? [Any] {
            args = parsed
        }
        completionHandler(feature.handleSyncBridge(name, args))
    }

    // Navigation ----------------------------------------------------------------------

    /// The page never navigates; anything else would leave the viewer.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(navigationAction.request.url?.scheme == Self.scheme ? .allow : .cancel)
    }

    /// The page's process ended (usually iOS reclaiming memory): bring it back lighter.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard !closed else { return }
        feature?.webContentProcessEnded()
    }

    // Requests ------------------------------------------------------------------------

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        guard !closed, let feature, let url = urlSchemeTask.request.url, url.host == Self.hostName else {
            respond(urlSchemeTask, status: 404, mime: "text/plain", data: Data())
            return
        }
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? url.path

        if path == "/terrain/meta.json", let meta = feature.payloadMeta {
            respond(urlSchemeTask, status: 200, mime: "application/json", data: meta)
        } else if path == "/terrain/grid.bin", let grid = feature.payloadGrid {
            respond(urlSchemeTask, status: 200, mime: "application/octet-stream", data: grid)
        } else if path.hasPrefix("/drape/") {
            // /drape/<id>/<z>/<x>/<y>
            let parts = path.dropFirst("/drape/".count).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 4, let id = parts[0].removingPercentEncoding,
                  let z = Int(parts[1]), let x = Int(parts[2]), let y = Int(parts[3]) else {
                respond(urlSchemeTask, status: 404, mime: "text/plain", data: Data())
                return
            }
            let task = urlSchemeTask
            running[key] = Task { [weak self] in
                let data = await feature.tileData(drapeId: id, z: z, x: x, y: y)
                guard let self, self.running.removeValue(forKey: key) != nil, !self.closed else { return }
                if let data {
                    self.respond(task, status: 200, mime: Self.imageMime(data), data: data)
                } else {
                    self.respond(task, status: 404, mime: "text/plain", data: Data())
                }
            }
        } else if path.hasPrefix("/terrain3d/"), !path.contains(".."),
                  let file = Self.assetURL(String(path.dropFirst("/terrain3d/".count))),
                  let data = try? Data(contentsOf: file) {
            respond(urlSchemeTask, status: 200, mime: Self.mime(for: file.pathExtension), data: data)
        } else {
            respond(urlSchemeTask, status: 404, mime: "text/plain", data: Data())
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        running.removeValue(forKey: key)?.cancel()
    }

    private func respond(_ task: WKURLSchemeTask, status: Int, mime: String, data: Data) {
        let url = task.request.url ?? Self.pageURL
        let headers = [
            "Content-Type": mime,
            "Content-Length": String(data.count),
            "Cache-Control": "no-store",
            "Access-Control-Allow-Origin": "*",
        ]
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) else {
            task.didFailWithError(URLError(.badServerResponse))
            return
        }
        task.didReceive(response)
        if !data.isEmpty { task.didReceive(data) }
        task.didFinish()
    }

    /// A file of the bundled page, by its path inside the `terrain3d` folder.
    private static func assetURL(_ relative: String) -> URL? {
        guard !relative.isEmpty,
              let index = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "terrain3d") else { return nil }
        let decoded = relative.removingPercentEncoding ?? relative
        let file = index.deletingLastPathComponent().appendingPathComponent(decoded)
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    nonisolated private static func mime(for ext: String) -> String {
        switch ext.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "js": return "application/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json"
        case "png": return "image/png"
        case "svg": return "image/svg+xml"
        default: return "application/octet-stream"
        }
    }

    nonisolated static func imageMime(_ bytes: Data) -> String {
        let b = [UInt8](bytes.prefix(12))
        if b.count > 3 && b[0] == 0xFF && b[1] == 0xD8 { return "image/jpeg" }
        if b.count >= 12 && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50 { return "image/webp" }
        return "image/png"
    }
}
