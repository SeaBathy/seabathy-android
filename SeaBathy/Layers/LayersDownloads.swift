import Foundation

// Offline tile downloads (Android download/TileFetchClient, TileDownloadRunner)
// and the GMRT seabed file download (storage/SeabedDownload).

/// Fetches tile links with retries. Cancelling stops every later fetch at once.
final class LayersTileFetchClient: NSObject, URLSessionTaskDelegate {
    private static let maxTileBytes = 8 * 1024 * 1024
    private static let retryableCodes: Set<Int> = [429, 500, 502, 503, 504]
    private static let maxLoggedFailures = 50

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        config.httpMaximumConnectionsPerHost = 6
        config.httpAdditionalHeaders = ["User-Agent": "SeaBathy (iOS map download)"]
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    private let stateLock = NSLock()
    private var cancelled = false
    private var loggedFailures = 0

    private var isCancelled: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return cancelled
    }

    func cancelInFlight() {
        stateLock.lock(); cancelled = true; stateLock.unlock()
        session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
    }

    func reset() {
        stateLock.lock(); cancelled = false; loggedFailures = 0; stateLock.unlock()
    }

    private lazy var fetcher = RetryingFetcher(shouldStop: { [weak self] in self?.isCancelled ?? true },
                                               attempt: { [weak self] url in await self?.attempt(url) ?? .permanent })

    func fetch(_ url: String) async -> Data? { await fetcher.fetch(url) }

    func fetchTile(_ template: String, z: Int, x: Int, y: Int) async -> Data? {
        let url = TileSampler.urlFor(template, z: z, x: x, y: y)
        let data = await fetch(url)
        if data == nil && !isCancelled { logFailure("tile fetch failed (after retries): z=\(z) x=\(x) y=\(y) url=\(layersRedactUrl(url))") }
        return data
    }

    private func attempt(_ urlString: String) async -> FetchOutcome {
        guard let url = URL(string: urlString), url.scheme == "http" || url.scheme == "https" else {
            DiagnosticLog.log("OfflineTileDownload", "invalid tile url: \(layersRedactUrl(urlString))")
            return .permanent
        }
        do {
            let (data, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse else { return .retryable }
            if (200..<300).contains(http.statusCode) {
                if data.count > Self.maxTileBytes { return .permanent }
                return .success(data)
            }
            if Self.retryableCodes.contains(http.statusCode) {
                logFailure("retryable http status: url=\(layersRedactUrl(urlString)) status=\(http.statusCode)")
                return .retryable
            }
            logFailure("permanent failure: url=\(layersRedactUrl(urlString)) status=\(http.statusCode)")
            return .permanent
        } catch {
            if isCancelled { return .permanent }
            logFailure("fetch error (retryable): url=\(layersRedactUrl(urlString)): \(error.localizedDescription)")
            return .retryable
        }
    }

    // A redirect may upgrade http to https, never downgrade https to plain http.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        if task.originalRequest?.url?.scheme == "https" && request.url?.scheme != "https" {
            completionHandler(nil)
        } else {
            completionHandler(request)
        }
    }

    /// Only the first few failures are logged: open ocean can fail thousands of times.
    private func logFailure(_ message: String) {
        stateLock.lock()
        loggedFailures += 1
        let count = loggedFailures
        stateLock.unlock()
        if count > Self.maxLoggedFailures { return }
        DiagnosticLog.log("OfflineTileDownload", message)
        if count == Self.maxLoggedFailures { DiagnosticLog.log("OfflineTileDownload", "further per-tile failures are not logged") }
    }
}

/// One offline map download: lists the tiles, fetches a few at a time with retries, writes MBTiles.
final class LayersTileDownloadRunner {
    struct Result { let file: URL; let totalBytes: Int64; let totalTiles: Int64; let failedTiles: Int64 }

    private static let writeBatchSize = 200
    private static let concurrentFetchLimit = 6
    private static let tileFormat = "jpg"
    private static let networkWaitSeconds = 15.0 * 60
    private static let networkPollNanos: UInt64 = 2_000_000_000

    private let client = LayersTileFetchClient()
    private let isOnline: () async -> Bool

    init(isOnline: @escaping () async -> Bool) { self.isOnline = isOnline }

    func cancel() { client.cancelInFlight() }

    static var downloadsFolder: URL { AppPaths.documentsFolder("OfflineDownloads") }

    /// `progress(done, total, bytesDone)`; `waiting` when the connection drops.
    func download(source: DataSource, bounds: GeoBounds, zoomRange: ClosedRange<Int>,
                  waiting: @escaping () -> Void,
                  progress: @escaping (Int64, Int64, Int64) -> Void) async throws -> Result {
        client.reset()
        let dir = Self.downloadsFolder
        let removed = LayersFileNames.deleteStaleDownloadParts(in: dir)
        if !removed.isEmpty {
            DiagnosticLog.log("OfflineTileDownload", "removed partial files left by an interrupted download: \(removed.map { $0.lastPathComponent })")
        }
        let destination = LayersFileNames.freeMbtilesFile(in: dir, baseName: LayersFileNames.downloadBaseName(sourceName: source.name, bounds: bounds))
        let partial = LayersFileNames.partFile(for: destination)
        let tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange: zoomRange)
        let failed: Int64
        do {
            failed = try await downloadTiles(source: source, bounds: bounds, zoomRange: zoomRange, tiles: tiles,
                                             into: partial, waiting: waiting, progress: progress)
        } catch {
            Self.deletePart(partial)
            throw error
        }
        let fm = FileManager.default
        do {
            try fm.moveItem(at: partial, to: destination)
        } catch {
            Self.deletePart(partial)
            throw LayersError("Download finished but couldn't be saved as \(destination.lastPathComponent)")
        }
        try? fm.removeItem(atPath: partial.path + "-journal")
        let size = ((try? fm.attributesOfItem(atPath: destination.path))?[.size] as? NSNumber)?.int64Value ?? 0
        if size <= 0 { throw LayersError("Download finished but the file is missing: nothing was saved") }
        DiagnosticLog.log("OfflineTileDownload", "verified on disk: \(destination.lastPathComponent) bytes=\(size)")
        return Result(file: destination, totalBytes: size, totalTiles: Int64(tiles.count), failedTiles: failed)
    }

    private static func deletePart(_ partial: URL) {
        try? FileManager.default.removeItem(at: partial)
        try? FileManager.default.removeItem(atPath: partial.path + "-journal")
    }

    private func downloadTiles(source: DataSource, bounds: GeoBounds, zoomRange: ClosedRange<Int>, tiles: [LayersTileCoord],
                               into file: URL, waiting: @escaping () -> Void,
                               progress: @escaping (Int64, Int64, Int64) -> Void) async throws -> Int64 {
        let total = Int64(tiles.count)
        var done: Int64 = 0, bytesDone: Int64 = 0, succeeded: Int64 = 0, failed: Int64 = 0
        var failedCoords: [LayersTileCoord] = []
        var gaveUpWaiting = false
        var lastProgress = Date()
        let template = source.urlTemplate
        let client = self.client
        DiagnosticLog.log("OfflineTileDownload", "downloadTiles start: source=\(source.name) zooms=\(zoomRange) totalTiles=\(total)")

        try? FileManager.default.removeItem(at: file)
        let writer = try MbtilesWriter(url: file)
        defer { writer.close() }
        try writer.writeMetadata(name: source.name, format: Self.tileFormat, bounds: bounds,
                                 minZoom: zoomRange.lowerBound, maxZoom: zoomRange.upperBound, attribution: source.attribution)

        var start = 0
        while start < tiles.count {
            try Task.checkCancellation()
            let chunk = Array(tiles[start..<min(start + Self.writeBatchSize, tiles.count)])
            start += chunk.count
            // Signal drops at sea: wait for it rather than failing every remaining tile.
            if !gaveUpWaiting, !(await waitForNetwork(waiting)) { gaveUpWaiting = true }
            if gaveUpWaiting {
                failed += Int64(chunk.count)
                failedCoords += chunk
                done += Int64(chunk.count)
                progress(done, total, bytesDone)
                continue
            }
            let fetched = await layersBoundedConcurrentMap(chunk, concurrency: Self.concurrentFetchLimit) { t in
                (t, await client.fetchTile(template, z: t.z, x: t.x, y: t.y))
            }
            try Task.checkCancellation()
            try writer.inTransaction {
                for (t, data) in fetched {
                    if let data {
                        try writer.writeTile(z: t.z, x: t.x, y: t.y, data: data)
                        succeeded += 1
                        bytesDone += Int64(data.count)
                    } else {
                        failed += 1
                        failedCoords.append(t)
                    }
                    done += 1
                }
            }
            // One report per batch (at most 200 tiles), or at least once a second.
            if Date().timeIntervalSince(lastProgress) >= 0.25 || done == total {
                progress(done, total, bytesDone)
                lastProgress = Date()
            }
        }

        // One more go at whatever failed, once the connection is back.
        if !failedCoords.isEmpty, !gaveUpWaiting, await waitForNetwork(waiting) {
            DiagnosticLog.log("OfflineTileDownload", "retrying \(failedCoords.count) failed tiles")
            var i = 0
            while i < failedCoords.count {
                try Task.checkCancellation()
                if !(await waitForNetwork(waiting)) { break }
                let chunk = Array(failedCoords[i..<min(i + Self.writeBatchSize, failedCoords.count)])
                i += chunk.count
                let fetched = await layersBoundedConcurrentMap(chunk, concurrency: Self.concurrentFetchLimit) { t in
                    (t, await client.fetchTile(template, z: t.z, x: t.x, y: t.y))
                }
                try writer.inTransaction {
                    for (t, data) in fetched {
                        guard let data else { continue }
                        try writer.writeTile(z: t.z, x: t.x, y: t.y, data: data)
                        succeeded += 1
                        failed -= 1
                        bytesDone += Int64(data.count)
                    }
                }
                progress(done, total, bytesDone)
            }
        }

        DiagnosticLog.log("OfflineTileDownload", "downloadTiles finished: succeeded=\(succeeded) failed=\(failed) bytes=\(bytesDone)")
        if succeeded == 0 { throw LayersError("None of the \(total) tiles in this area could be downloaded") }
        return failed
    }

    private func waitForNetwork(_ onWaiting: () -> Void) async -> Bool {
        if await isOnline() { return true }
        onWaiting()
        DiagnosticLog.log("OfflineTileDownload", "no internet: waiting for the connection to come back")
        let deadline = Date().addingTimeInterval(Self.networkWaitSeconds)
        while Date() < deadline {
            if Task.isCancelled { return false }
            try? await Task.sleep(nanoseconds: Self.networkPollNanos)
            if await isOnline() { return true }
        }
        return false
    }
}

/// Downloads one file with byte progress (the GMRT seabed GeoTIFF).
final class LayersFileDownloader: NSObject, URLSessionDownloadDelegate {
    private var continuation: CheckedContinuation<URL, Error>?
    private var progress: ((Int64) -> Void)?
    private var keptFile: URL?
    private var session: URLSession?
    private var statusError: Error?

    func download(_ url: URL, userAgent: String, progress: @escaping (Int64) -> Void) async throws -> URL {
        self.progress = progress
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<URL, Error>) in
            self.continuation = c
            session.downloadTask(with: request).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        progress?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            statusError = LayersError("The depth service answered \(http.statusCode). Try again later.")
            return
        }
        // The system deletes `location` when this returns, so it's moved first.
        let kept = FileManager.default.temporaryDirectory.appendingPathComponent("seabed-\(UUID().uuidString).part")
        do {
            try FileManager.default.moveItem(at: location, to: kept)
            keptFile = kept
        } catch {
            statusError = error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let c = continuation
        continuation = nil
        session.finishTasksAndInvalidate()
        if let error { c?.resume(throwing: error); return }
        if let statusError { c?.resume(throwing: statusError); return }
        if let keptFile { c?.resume(returning: keptFile) } else { c?.resume(throwing: LayersError("The depth service sent nothing")) }
    }
}
