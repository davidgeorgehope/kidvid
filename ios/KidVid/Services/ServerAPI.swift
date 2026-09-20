import Foundation

/// Thin HTTP client for the KidVid video-server API
/// (`https://files.signal.observer` — same contract as Android).
final class ServerAPI: @unchecked Sendable {
    var baseURL: URL

    init(baseURL: URL = AppConfig.serverBaseURL) {
        self.baseURL = baseURL
    }

    /// Shared library listing. With `device`, only files not yet acked by that install.
    func listVideos(device: String? = nil) async throws -> [RemoteVideo] {
        var query: [String: String] = [:]
        if let device, !device.isEmpty {
            query["device"] = device
        }
        let data = try await get(path: "/videos", query: query)
        return try JSONDecoder().decode([RemoteVideo].self, from: data)
    }

    /// Pending deletes for one device (`GET /deletes?device=...`).
    func pendingDeletes(device: String) async throws -> [String] {
        let data = try await get(path: "/deletes", query: ["device": device])
        if let arr = try? JSONDecoder().decode([String].self, from: data) {
            return arr
        }
        if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
           let arr = obj[device] as? [String] {
            return arr
        }
        return []
    }

    /// Record that this device has the file. Does **not** remove the shared library copy.
    @discardableResult
    func ackDownload(filename: String, device: String) async -> Bool {
        await put(path: "/acked/\(encoded(filename))", query: ["device": device])
    }

    /// Parent/CoS: remove from shared library (server also tees pending deletes).
    @discardableResult
    func deleteFromLibrary(filename: String) async -> Bool {
        await delete(path: "/videos/\(encoded(filename))")
    }

    @discardableResult
    func queuePendingDelete(filename: String, device: String) async -> Bool {
        await put(path: "/deletes/\(encoded(filename))", query: ["device": device])
    }

    @discardableResult
    func ackDelete(filename: String, device: String) async -> Bool {
        await delete(path: "/deletes/\(encoded(filename))", query: ["device": device])
    }

    /// Parent delete: library DELETE + tee legacy phone/fire buckets.
    func parentDeleteRemote(filename: String) async -> Bool {
        let libraryGone = await deleteFromLibrary(filename: filename)
        let phone = await queuePendingDelete(filename: filename, device: "phone")
        let fire = await queuePendingDelete(filename: filename, device: "fire")
        return libraryGone || phone || fire
    }

    func download(relativeOrAbsolute urlString: String, to destination: URL) async throws {
        let full = resolveURL(urlString)

        var request = URLRequest(url: full)
        request.timeoutInterval = 5 * 60
        let (tempURL, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let tmpDest = destination.appendingPathExtension("tmp")
        try? FileManager.default.removeItem(at: tmpDest)
        try FileManager.default.moveItem(at: tempURL, to: tmpDest)
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: tmpDest, to: destination)
    }

    // MARK: - HTTP helpers

    private func resolveURL(_ urlString: String) -> URL {
        if urlString.hasPrefix("http://") || urlString.hasPrefix("https://"),
           let u = URL(string: urlString) {
            return u
        }
        var path = urlString
        if !path.hasPrefix("/") { path = "/" + path }
        var comp = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        // Preserve query on base if any; replace path
        let parts = path.split(separator: "?", maxSplits: 1).map(String.init)
        comp.path = parts[0]
        if parts.count > 1 {
            comp.query = parts[1]
        }
        return comp.url ?? baseURL.appendingPathComponent(urlString)
    }

    private func get(path: String, query: [String: String] = [:]) async throws -> Data {
        let url = makeURL(path: path, query: query)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    @discardableResult
    private func delete(path: String, query: [String: String] = [:]) async -> Bool {
        let url = makeURL(path: path, query: query)
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.timeoutInterval = 30
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            return code == 200 || code == 404
        } catch {
            print("[KidVid] DELETE failed \(url): \(error)")
            return false
        }
    }

    @discardableResult
    private func put(path: String, query: [String: String] = [:]) async -> Bool {
        let url = makeURL(path: path, query: query)
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.httpBody = Data()
        request.setValue("0", forHTTPHeaderField: "Content-Length")
        request.timeoutInterval = 30
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            return code == 200 || code == 201 || code == 204
        } catch {
            print("[KidVid] PUT failed \(url): \(error)")
            return false
        }
    }

    private func makeURL(path: String, query: [String: String]) -> URL {
        var comp = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        comp.path = path.hasPrefix("/") ? path : "/" + path
        if !query.isEmpty {
            comp.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return comp.url!
    }

    private func encoded(_ filename: String) -> String {
        filename.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? filename
    }
}
