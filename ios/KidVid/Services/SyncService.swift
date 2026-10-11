import Foundation
import Combine

/// Background sync: apply pending deletes, download new videos, ack per-device.
/// Shared library stays on the server until 7-day age-out — clients never DELETE after download.
/// Priority: goldie/dvd/youtube first; internet_archive last. Keep ~5GB free on device.
@MainActor
final class SyncService: ObservableObject {
    @Published private(set) var isSyncing = false
    @Published private(set) var lastStatus: String = ""
    @Published private(set) var lastSyncDate: Date?

    /// Leave this much free after any download (iPhone SE 64GB budget).
    private static let freeReserveBytes: Int64 = 5 * 1024 * 1024 * 1024

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

        // 1) Apply pending remote deletes (this device + legacy phone bucket)
        var pendingNames = Set<String>()
        for bucket in AppConfig.deleteBuckets {
            do {
                let pending = try await api.pendingDeletes(device: bucket)
                for name in pending where library.isSafeFilename(name) {
                    pendingNames.insert(name)
                    let ok = library.deleteLocal(filename: name)
                    if ok {
                        _ = await api.ackDelete(filename: name, device: bucket)
                    } else {
                        print("[KidVid] Local delete incomplete for \(name); will retry")
                    }
                }
            } catch {
                print("[KidVid] /deletes unavailable for \(bucket): \(error)")
            }
        }

        // 2) List unacked library files for this device + download (priority order)
        let remoteRaw: [RemoteVideo]
        do {
            remoteRaw = try await api.listVideos(device: device)
        } catch {
            lastStatus = "No server (check network)"
            print("[KidVid] listVideos failed: \(error)")
            library.reload()
            return
        }

        let remote = remoteRaw.sorted {
            if $0.syncPriority != $1.syncPriority { return $0.syncPriority < $1.syncPriority }
            return $0.name < $1.name
        }

        var downloaded = 0
        for v in remote {
            guard library.isSafeFilename(v.name) else { continue }

            if pendingNames.contains(v.name) {
                continue
            }

            let dest = library.videosDirectory.appendingPathComponent(v.name)
            if FileManager.default.fileExists(atPath: dest.path) {
                if let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path),
                   let size = attrs[.size] as? Int64,
                   size == v.size {
                    // Already on disk — ack so server stops offering; do not DELETE library
                    _ = await api.ackDownload(filename: v.name, device: device)
                    await ensureSidecarJpg(for: v, library: library)
                    continue
                }
            }

            // Free-space gate: prefer primary; skip archive when tight; evict archive if needed for primary
            if !(await ensureSpace(forBytes: v.size, preferArchiveEvict: !v.isArchive, library: library, device: device)) {
                if v.isArchive {
                    print("[KidVid] Skip archive (low space): \(v.name)")
                    continue
                }
                print("[KidVid] Skip primary (still low space): \(v.name)")
                continue
            }

            lastStatus = "Downloading \(v.name)…"
            do {
                try await api.download(relativeOrAbsolute: v.url, to: dest)
                downloaded += 1
                _ = await api.ackDownload(filename: v.name, device: device)
                await ensureSidecarJpg(for: v, library: library)
            } catch {
                print("[KidVid] Download failed \(v.name): \(error)")
            }
        }

        await backfillMissingThumbs(library: library)

        library.reload()
        lastSyncDate = Date()
        lastAutoSync = Date()
        lastStatus = downloaded > 0
            ? "Synced \(downloaded) new · \(library.videos.count) total"
            : "Up to date · \(library.videos.count) videos"
        _ = manual
    }

    /// Parent PIN delete: local file + remote library DELETE (+ server tees pending deletes).
    func parentDelete(filename: String) async -> (local: Bool, remote: Bool) {
        guard let library else { return (false, false) }
        let local = library.deleteLocal(filename: filename)
        api.baseURL = AppConfig.serverBaseURL
        let remote = await api.parentDeleteRemote(filename: filename)
        return (local, remote)
    }

    /// Return true if `need` bytes can fit while keeping freeReserve.
    private func ensureSpace(forBytes need: Int64, preferArchiveEvict: Bool, library: VideoLibrary, device: String) async -> Bool {
        func usable() -> Int64 {
            let vals = try? library.videosDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            if let c = vals?.volumeAvailableCapacityForImportantUsage { return Int64(c) }
            let attrs = try? FileManager.default.attributesOfFileSystem(forPath: library.videosDirectory.path)
            if let free = attrs?[.systemFreeSize] as? Int64 { return free }
            return 0
        }
        let reserve = Self.freeReserveBytes
        if usable() - need >= reserve { return true }
        guard preferArchiveEvict else { return false }

        // Evict local archive-tier files (name heuristics matching server) until space frees
        let dir = library.videosDirectory
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return false
        }
        let archiveHints = [
            "popeye", "superman", "skeleton", "casper", "silly-symphon", "jetsons",
            "woody_woodpecker", "mighty_mouse", "noveltoon", "cobweb", "big_bad_wolf",
            "hectors_", "bb_minnie", "bb_snow", "steamboat", "flip_frog",
            "disney-animated", "santas-workshop", "mother_goose",
        ]
        var archiveFiles = files.filter { url in
            guard url.pathExtension.lowercased() == "mp4" else { return false }
            let low = url.lastPathComponent.lowercased()
            return archiveHints.contains { low.contains($0) }
        }
        archiveFiles.sort { a, b in
            let sa = (try? a.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            let sb = (try? b.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return sa > sb
        }
        for url in archiveFiles {
            if usable() - need >= reserve { break }
            let name = url.lastPathComponent
            print("[KidVid] Evict archive for space: \(name)")
            try? FileManager.default.removeItem(at: url)
            let jpg = url.deletingPathExtension().appendingPathExtension("jpg")
            try? FileManager.default.removeItem(at: jpg)
            _ = await api.unackDownload(filename: name, device: device)
        }
        return usable() - need >= reserve
    }

    private func ensureSidecarJpg(for v: RemoteVideo, library: VideoLibrary) async {
        guard v.name.lowercased().hasSuffix(".mp4") else { return }
        let jpgName = String(v.name.dropLast(4)) + ".jpg"
        let dest = library.videosDirectory.appendingPathComponent(jpgName)
        if FileManager.default.fileExists(atPath: dest.path) {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path),
               let size = attrs[.size] as? Int64, size > 0 {
                return
            }
        }
        let urlString: String
        if let t = v.thumb_url, !t.isEmpty {
            urlString = t
        } else {
            urlString = "/videos/" + jpgName
        }
        do {
            try await api.download(relativeOrAbsolute: urlString, to: dest)
            print("[KidVid] Thumb fetched " + jpgName)
        } catch {
            print("[KidVid] Thumb miss " + jpgName)
        }
    }

    private func backfillMissingThumbs(library: VideoLibrary) async {
        let dir = library.videosDirectory
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for fileURL in files where fileURL.pathExtension.lowercased() == "mp4" {
            let jpg = fileURL.deletingPathExtension().appendingPathExtension("jpg")
            if FileManager.default.fileExists(atPath: jpg.path) { continue }
            let jpgName = jpg.lastPathComponent
            do {
                try await api.download(relativeOrAbsolute: "/videos/" + jpgName, to: jpg)
            } catch {
                print("[KidVid] Thumb backfill miss " + jpgName)
            }
        }
    }

}
