import Foundation
import Combine

/// Background sync: apply pending deletes, download new videos, drain remote queue.
/// Mirrors Android `SyncService` against `https://files.signal.observer`.
@MainActor
final class SyncService: ObservableObject {
    @Published private(set) var isSyncing = false
    @Published private(set) var lastStatus: String = ""
    @Published private(set) var lastSyncDate: Date?

    private var library: VideoLibrary?
    private let api = ServerAPI()
    private var lastAutoSync: Date?

    func attach(library: VideoLibrary) {
        self.library = library
    }

    func syncIfNeeded() async {
        if let last = lastAutoSync, Date().timeIntervalSince(last) < AppConfig.syncInterval {
            return
        }
        await sync(manual: false)
    }

    func sync(manual: Bool = true) async {
        guard !isSyncing else { return }
        guard let library else {
            lastStatus = "Library not ready"
            return
        }

        isSyncing = true
        lastStatus = "Syncing…"
        defer { isSyncing = false }

        api.baseURL = AppConfig.serverBaseURL
        let device = AppConfig.deviceID
        library.ensureDirectories()

        // 1) Apply pending remote deletes
        var pendingNames = Set<String>()
        do {
            let pending = try await api.pendingDeletes(device: device)
            for name in pending where library.isSafeFilename(name) {
                pendingNames.insert(name)
                let ok = library.deleteLocal(filename: name)
                if ok {
                    _ = await api.ackDelete(filename: name, device: device)
                } else {
                    print("[KidVid] Local delete incomplete for \(name); will retry")
                }
            }
        } catch {
            print("[KidVid] /deletes unavailable: \(error)")
        }

        // 2) List + download
        let remote: [RemoteVideo]
        do {
            remote = try await api.listVideos()
        } catch {
            lastStatus = "No server (check network)"
            print("[KidVid] listVideos failed: \(error)")
            library.reload()
            return
        }

        var downloaded = 0
        for v in remote {
            guard library.isSafeFilename(v.name) else { continue }

            if pendingNames.contains(v.name) {
                _ = await api.deleteFromQueue(filename: v.name)
                continue
            }

            let dest = library.videosDirectory.appendingPathComponent(v.name)
            if FileManager.default.fileExists(atPath: dest.path) {
                if let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path),
                   let size = attrs[.size] as? Int64,
                   size == v.size {
                    _ = await api.deleteFromQueue(filename: v.name)
                    continue
                }
            }

            lastStatus = "Downloading \(v.name)…"
            do {
                try await api.download(relativeOrAbsolute: v.url, to: dest)
                downloaded += 1
                _ = await api.deleteFromQueue(filename: v.name)
            } catch {
                print("[KidVid] Download failed \(v.name): \(error)")
            }
        }

        library.reload()
        lastSyncDate = Date()
        lastAutoSync = Date()
        lastStatus = downloaded > 0
            ? "Synced \(downloaded) new · \(library.videos.count) total"
            : "Up to date · \(library.videos.count) videos"
        _ = manual
    }

    /// Parent PIN delete: local file + remote queue + tee pending for phone+fire.
    func parentDelete(filename: String) async -> (local: Bool, remote: Bool) {
        guard let library else { return (false, false) }
        let local = library.deleteLocal(filename: filename)
        api.baseURL = AppConfig.serverBaseURL
        let remote = await api.parentDeleteRemote(filename: filename)
        return (local, remote)
    }
}
