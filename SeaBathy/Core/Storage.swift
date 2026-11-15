import Foundation

/// Where the app keeps its files. Everything is inside the app's own sandbox.
enum AppPaths {
    /// Documents: user-visible files (imported surveys, MBTiles, GPX exports). Shown in the Files app.
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Application Support/SeaBathy: the app's own data (stores, caches it must keep).
    static var support: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SeaBathy", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Caches: anything that can be rebuilt.
    static var caches: URL {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SeaBathy", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A subfolder of `support`, created on demand.
    static func supportFolder(_ name: String) -> URL {
        let url = support.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A subfolder of `documents`, created on demand.
    static func documentsFolder(_ name: String) -> URL {
        let url = documents.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Stored paths are kept relative to Documents where possible, because the
    /// sandbox's absolute path changes between installs and updates.
    static func storable(_ url: URL) -> String {
        let docs = documents.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        if path.hasPrefix(docs + "/") { return "docs:" + String(path.dropFirst(docs.count + 1)) }
        let sup = support.standardizedFileURL.path
        if path.hasPrefix(sup + "/") { return "support:" + String(path.dropFirst(sup.count + 1)) }
        return path
    }

    static func resolve(_ stored: String) -> URL {
        if stored.hasPrefix("docs:") { return documents.appendingPathComponent(String(stored.dropFirst(5))) }
        if stored.hasPrefix("support:") { return support.appendingPathComponent(String(stored.dropFirst(8))) }
        return URL(fileURLWithPath: stored)
    }
}

/// A Codable value saved as one JSON file in Application Support. Unreadable files are kept aside, not lost.
final class JSONFileStore<Value: Codable> {
    let url: URL
    private let defaultValue: () -> Value

    init(name: String, default defaultValue: @autoclosure @escaping () -> Value) {
        self.url = AppPaths.support.appendingPathComponent(name + ".json")
        self.defaultValue = defaultValue
    }

    func load() -> Value {
        guard let data = try? Data(contentsOf: url) else { return defaultValue() }
        do {
            return try JSONDecoder().decode(Value.self, from: data)
        } catch {
            // Keep the unreadable file for diagnosis instead of overwriting it.
            let aside = url.deletingPathExtension().appendingPathExtension("unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: url, to: aside)
            DiagnosticLog.log("JSONFileStore", "couldn't read \(url.lastPathComponent): \(error)")
            return defaultValue()
        }
    }

    func save(_ value: Value) {
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(value)
            try data.write(to: url, options: .atomic)
        } catch {
            DiagnosticLog.log("JSONFileStore", "couldn't save \(url.lastPathComponent): \(error)")
        }
    }
}

/// A small rolling debug log (Settings > Share debug log), like Android's DiagnosticLog.
enum DiagnosticLog {
    private static let queue = DispatchQueue(label: "seabathy.log")
    private static let maxBytes = 512 * 1024

    static var url: URL { AppPaths.caches.appendingPathComponent("debug-log.txt") }

    static func log(_ tag: String, _ message: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        let line = "\(stamp) [\(tag)] \(message)\n"
        #if DEBUG
        print(line, terminator: "")
        #endif
        queue.async {
            let fm = FileManager.default
            if let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int), size > maxBytes {
                let old = url.deletingPathExtension().appendingPathExtension("old.txt")
                try? fm.removeItem(at: old)
                try? fm.moveItem(at: url, to: old)
            }
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(Data(line.utf8))
                try? handle.close()
            } else {
                try? Data(line.utf8).write(to: url)
            }
        }
    }

    static func logError(_ tag: String, _ message: String, _ error: Error) {
        log(tag, "\(message): \(error)")
    }
}
