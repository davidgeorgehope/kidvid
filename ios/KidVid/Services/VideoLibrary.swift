import Foundation
import Combine

/// Manages the on-device video library under Documents/kidvid/.
///
/// Intended clone path from Android (follow-up script):
///   Documents/kidvid/videos/*.mp4
///   Documents/kidvid/pins.json   (optional)
///   Documents/kidvid/manifest.json (optional title map)
@MainActor
final class VideoLibrary: ObservableObject {
    @Published private(set) var videos: [VideoItem] = []
    @Published private(set) var isEmpty: Bool = true

    private let fileManager = FileManager.default

    /// `…/Documents/kidvid/`
    var kidvidRoot: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("kidvid", isDirectory: true)
    }

    /// `…/Documents/kidvid/videos/`
    var videosDirectory: URL {
        kidvidRoot.appendingPathComponent("videos", isDirectory: true)
    }

    var pinsURL: URL { kidvidRoot.appendingPathComponent("pins.json") }
    var manifestURL: URL { kidvidRoot.appendingPathComponent("manifest.json") }

    func ensureDirectories() {
        try? fileManager.createDirectory(at: videosDirectory, withIntermediateDirectories: true)
    }

    func reload() {
        ensureDirectories()
        let titles = loadManifestTitles()
        let pins = loadPins()
        let pinnedSet = Set(pins)

        let exts: Set<String> = ["mp4", "mkv", "webm", "mov", "m4v", "3gp"]
        let files = (try? fileManager.contentsOfDirectory(
            at: videosDirectory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        var items: [VideoItem] = files.compactMap { url in
            let ext = url.pathExtension.lowercased()
            guard exts.contains(ext) else { return nil }
            let name = url.lastPathComponent
            return VideoItem(
                url: url,
                title: titles[name],
                isPinned: pinnedSet.contains(name)
            )
        }

        // Pins / favorites first (same spirit as requested “movies/pins first”).
        items.sort { a, b in
            if a.isPinned != b.isPinned { return a.isPinned && !b.isPinned }
            return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
        }

        videos = items
        isEmpty = items.isEmpty
    }

    func index(of filename: String) -> Int? {
        videos.firstIndex { $0.filename == filename }
    }

    @discardableResult
    func deleteLocal(filename: String) -> Bool {
        let url = videosDirectory.appendingPathComponent(filename)
        guard fileManager.fileExists(atPath: url.path) else {
            reload()
            return true
        }
        do {
            try fileManager.removeItem(at: url)
            reload()
            return true
        } catch {
            print("[KidVid] Local delete failed: \(error)")
            return false
        }
    }

    // MARK: - pins.json

    /// Supported shapes:
    ///   ["a.mp4", "b.mp4"]
    ///   {"pins": ["a.mp4"]}
    ///   {"movies": ["a.mp4"]}  // alias
    private func loadPins() -> [String] {
        guard fileManager.fileExists(atPath: pinsURL.path),
              let data = try? Data(contentsOf: pinsURL) else { return [] }
        if let arr = try? JSONDecoder().decode([String].self, from: data) {
            return arr.filter { isSafeFilename($0) }
        }
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for key in ["pins", "movies", "favorites"] {
                if let arr = obj[key] as? [String] {
                    return arr.filter { isSafeFilename($0) }
                }
            }
        }
        return []
    }

    // MARK: - manifest.json (optional titles)

    private func loadManifestTitles() -> [String: String] {
        guard fileManager.fileExists(atPath: manifestURL.path),
              let data = try? Data(contentsOf: manifestURL),
              let content = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !content.isEmpty else { return [:] }

        var map: [String: String] = [:]
        do {
            if content.hasPrefix("[") {
                let arr = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []
                for v in arr {
                    if let filename = v["filename"] as? String,
                       let title = v["title"] as? String {
                        map[filename] = title
                    }
                }
            } else if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let arr = obj["videos"] as? [[String: Any]] {
                for v in arr {
                    if let filename = v["filename"] as? String,
                       let title = v["title"] as? String {
                        map[filename] = title
                    }
                }
            }
        } catch {
            print("[KidVid] Bad manifest.json: \(error)")
        }
        return map
    }

    func isSafeFilename(_ name: String) -> Bool {
        !name.isEmpty
            && !name.contains("/")
            && !name.contains("\\")
            && !name.contains("..")
            && name != "."
            && name != "deletes.json"
    }
}
