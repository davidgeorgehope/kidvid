import AVFoundation
import Combine
import Foundation
import UIKit
import VideoToolbox

/// One AVPlayer that stays bound to its own AVPlayerLayer for the app's lifetime.
///
/// PlayerView renders every slot's layer all the time and only changes which one
/// is visible, so a swipe never detaches a layer or swaps the item out from under
/// the layer that's on screen (which is what left the screen black).
@MainActor
final class PlayerSlot: ObservableObject, Identifiable {
    let id: Int
    let player = AVPlayer()

    /// Library index currently loaded in this slot (nil = empty).
    fileprivate(set) var videoIndex: Int?
    fileprivate(set) var url: URL?

    /// The slot's AVPlayerLayer has a decoded frame for the loaded item.
    @Published fileprivate(set) var hasFrame = false
    /// The loaded file can't be played (truncated / corrupt download).
    @Published fileprivate(set) var failed = false
    /// Sound plays but this phone can't decode the picture (e.g. AV1 on iPhone SE).
    @Published fileprivate(set) var noPicture = false

    /// Set by the slot's PlayerUIView; the layer is the source of truth for "frame on screen".
    weak var layer: AVPlayerLayer?
    private var codecTask: Task<Void, Never>?
    fileprivate var statusObservation: NSKeyValueObservation?
    fileprivate var endObserver: NSObjectProtocol?
    fileprivate var failObserver: NSObjectProtocol?
    fileprivate var timeObserver: Any?
    fileprivate var onEnd: (() -> Void)?

    init(id: Int) {
        self.id = id
        player.actionAtItemEnd = .pause
        // Local files: start as soon as there is data, don't wait for "likely to keep up".
        player.automaticallyWaitsToMinimizeStalling = false
        player.isMuted = true
    }

    /// Called by the slot's PlayerUIView whenever AVPlayerLayer.isReadyForDisplay changes.
    func layerReadyChanged() {
        refreshHasFrame()
    }

    /// Read the real state every time (a reused layer may stay "ready" across an
    /// item swap without sending a KVO change, so a cached flag would get stuck).
    func refreshHasFrame() {
        let itemReady = player.currentItem?.status == .readyToPlay
        let value = itemReady && (layer?.isReadyForDisplay ?? false)
        if hasFrame != value { hasFrame = value }
    }

    fileprivate func markNoPicture() {
        guard !noPicture else { return }
        NSLog("KidVid: no picture for \(url?.lastPathComponent ?? "?") (audio only)")
        noPicture = true
        refreshHasFrame()
    }

    fileprivate func load(url: URL, index: Int) {
        removeItemObservers()

        let item = AVPlayerItem(url: url)
        // Small forward buffer while it's only a pre-rolled neighbour.
        item.preferredForwardBufferDuration = 2
        self.url = url
        self.videoIndex = index
        failed = false
        noPicture = false
        hasFrame = false
        player.pause()
        player.replaceCurrentItem(with: item)
        checkVideoCodec(of: item)

        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            let status = item.status
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                if status == .failed {
                    NSLog("KidVid: cannot play \(url.lastPathComponent): \(String(describing: item.error))")
                    self.failed = true
                }
                self.refreshHasFrame()
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.onEnd?() }
        }
        // Half-copied files open fine and then run out of data mid-play.
        failObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                NSLog("KidVid: \(url.lastPathComponent) stopped early (incomplete file?)")
                self.failed = true
            }
        }
    }

    private func removeItemObservers() {
        statusObservation = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
        endObserver = nil
        failObserver = nil
    }

    /// AV1 / VP9 files (some YouTube downloads) play sound but no picture on phones
    /// without a hardware decoder. Detect up front so we show the poster, not black.
    private func checkVideoCodec(of item: AVPlayerItem) {
        codecTask?.cancel()
        let asset = item.asset
        codecTask = Task { @MainActor [weak self] in
            guard let tracks = try? await asset.loadTracks(withMediaType: .video),
                  let track = tracks.first,
                  let descs = try? await track.load(.formatDescriptions),
                  let desc = descs.first else { return }
            let codec = CMFormatDescriptionGetMediaSubType(desc)
            let needsHW = codec == kCMVideoCodecType_AV1 || codec == kCMVideoCodecType_VP9
            guard let self, !Task.isCancelled, self.player.currentItem === item else { return }
            if needsHW && !VTIsHardwareDecodeSupported(codec) {
                self.markNoPicture()
            }
        }
    }

    fileprivate func unload() {
        codecTask?.cancel()
        removeItemObservers()
        player.pause()
        player.replaceCurrentItem(with: nil)
        url = nil
        videoIndex = nil
        failed = false
        noPicture = false
        hasFrame = false
    }
}

/// Fullscreen kid playback with instant swipes.
///
/// Three players: the current video plus the next and previous ones pre-rolled
/// (paused on their first frame). A swipe just makes the neighbour slot visible
/// and starts it; nothing is torn down on screen, so there is no black gap.
@MainActor
final class PlayerModel: ObservableObject {
    let slots: [PlayerSlot] = (0..<3).map { PlayerSlot(id: $0) }

    @Published private(set) var activeSlotID = 0
    @Published private(set) var isPaused = false
    @Published private(set) var currentIndex = 0
    @Published var progress: Double = 0

    private var videos: [VideoItem] = []
    /// +1 / -1: direction of the last swipe, used to skip unplayable files.
    private var lastStep = 1
    private var skipTask: Task<Void, Never>?
    private var consecutiveSkips = 0
    private var slotCancellables: [AnyCancellable] = []
    private var lifecycleObservers: [NSObjectProtocol] = []

    /// The on-screen player.
    var player: AVPlayer { activeSlot.player }
    var activeSlot: PlayerSlot { slots[activeSlotID] }

    init() {
        Self.activatePlaybackAudioSession()
        for slot in slots {
            slot.onEnd = { [weak self, weak slot] in
                guard let self, let slot, slot.id == self.activeSlotID else { return }
                // Loop current video (Android MediaPlayer.setLooping(true))
                slot.player.seek(to: .zero)
                if !self.isPaused { self.startActive() }
            }
            slot.timeObserver = slot.player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                queue: .main
            ) { [weak self, weak slot] time in
                Task { @MainActor in
                    guard let self, let slot, slot.id == self.activeSlotID else { return }
                    let duration = slot.player.currentItem?.duration.seconds ?? 0
                    if duration.isFinite, duration > 0 {
                        self.progress = time.seconds / duration
                    }
                    slot.refreshHasFrame()
                    // Playing for a while but the layer never got a picture: the
                    // video track can't be decoded here. Show the poster, keep sound.
                    if !slot.hasFrame, !slot.noPicture, time.seconds > 2.0,
                       slot.player.timeControlStatus == .playing {
                        slot.markNoPicture()
                    }
                }
            }
            // A slot that fails while on screen: skip on in the same direction.
            slot.$failed
                .removeDuplicates()
                .sink { [weak self, weak slot] failed in
                    guard failed, let self, let slot else { return }
                    Task { @MainActor in self.activeSlotFailed(slot) }
                }
                .store(in: &slotCancellables)
        }
        let nc = NotificationCenter.default
        lifecycleObservers.append(nc.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.resumeAfterForeground() }
        })
    }

    func teardown() {
        for slot in slots {
            if let t = slot.timeObserver { slot.player.removeTimeObserver(t) }
            slot.timeObserver = nil
            slot.unload()
        }
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        lifecycleObservers.removeAll()
    }

    /// Ensure audio plays with Silent switch on (default session is ambient → muted).
    private static func activatePlaybackAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, options: [])
            try session.setActive(true)
        } catch {
            NSLog("KidVid: AVAudioSession setup failed: \(error)")
        }
    }

    private static var muteForTests: Bool {
        #if KIDVID_UITEST
        return true
        #else
        return false
        #endif
    }

    // MARK: - Playlist

    func setPlaylist(_ items: [VideoItem], startAt index: Int = 0) {
        videos = items
        guard !items.isEmpty else { return }
        play(index: max(0, min(index, items.count - 1)))
    }

    func play(index: Int) {
        guard !videos.isEmpty else { return }
        let target = wrap(index)
        skipTask?.cancel()
        skipTask = nil
        consecutiveSkips = 0

        let previous = activeSlot
        // Reuse a slot that already has this video pre-rolled; otherwise load one.
        let slot = slots.first { $0.videoIndex == target && $0.url == videos[target].url }
            ?? freeSlot(excluding: [previous.id], keep: [])
        if slot.videoIndex != target || slot.url != videos[target].url {
            slot.load(url: videos[target].url, index: target)
        }

        if previous.id != slot.id {
            previous.player.pause()
            previous.player.isMuted = true
            previous.player.seek(to: .zero) // next visit starts from the beginning
        } else {
            slot.player.seek(to: .zero)
        }

        currentIndex = target
        activeSlotID = slot.id
        progress = 0
        isPaused = false
        if slot.failed { activeSlotFailed(slot) }
        startActive()
        preloadNeighbours()
    }

    func next() {
        guard !videos.isEmpty else { return }
        lastStep = 1
        play(index: currentIndex + 1)
    }

    func previous() {
        guard !videos.isEmpty else { return }
        lastStep = -1
        play(index: currentIndex - 1)
    }

    private func startActive() {
        Self.activatePlaybackAudioSession()
        let p = activeSlot.player
        p.currentItem?.preferredForwardBufferDuration = 0 // back to automatic once on screen
        p.isMuted = Self.muteForTests
        p.volume = 1.0
        p.play()
    }

    /// Pre-roll next and previous so the next swipe is instant.
    private func preloadNeighbours() {
        guard videos.count > 1 else { return }
        let active = activeSlot
        var wanted = [wrap(currentIndex + 1)]
        let prev = wrap(currentIndex - 1)
        if !wanted.contains(prev) { wanted.append(prev) }

        var used: Set<Int> = [active.id]
        // Keep slots that already hold a wanted neighbour.
        var missing: [Int] = []
        for idx in wanted {
            if let s = slots.first(where: { !used.contains($0.id) && $0.videoIndex == idx && $0.url == videos[idx].url }) {
                used.insert(s.id)
            } else {
                missing.append(idx)
            }
        }
        for idx in missing {
            guard let s = slots.first(where: { !used.contains($0.id) }) else { break }
            used.insert(s.id)
            s.load(url: videos[idx].url, index: idx)
            s.player.isMuted = true
            // Paused at 0: the layer renders the first frame and waits.
        }
    }

    private func freeSlot(excluding: Set<Int>, keep: Set<Int>) -> PlayerSlot {
        // Prefer an empty slot, then any slot that isn't a direct neighbour.
        let candidates = slots.filter { !excluding.contains($0.id) }
        if let empty = candidates.first(where: { $0.videoIndex == nil }) { return empty }
        return candidates.first ?? slots[0]
    }

    private func wrap(_ index: Int) -> Int {
        ((index % videos.count) + videos.count) % videos.count
    }

    /// Unplayable file on screen (e.g. truncated download): show its poster briefly, then move on.
    private func activeSlotFailed(_ slot: PlayerSlot) {
        guard slot.id == activeSlotID, videos.count > 1 else { return }
        guard consecutiveSkips < videos.count else { return }
        skipTask?.cancel()
        skipTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard let self, !Task.isCancelled, self.activeSlotID == slot.id, slot.failed else { return }
            guard self.consecutiveSkips < self.videos.count else { return } // everything broken: stop
            let skips = self.consecutiveSkips + 1
            self.play(index: self.currentIndex + self.lastStep)
            self.consecutiveSkips = skips
        }
    }

    private func resumeAfterForeground() {
        guard activeSlot.videoIndex != nil else { return }
        if !isPaused { startActive() }
    }

    // MARK: - Transport

    func togglePause() {
        if isPaused {
            isPaused = false
            startActive()
        } else {
            player.pause()
            isPaused = true
        }
    }

    func seek(bySeconds seconds: Double) {
        let current = player.currentTime().seconds
        guard current.isFinite else { return }
        let duration = player.currentItem?.duration.seconds ?? .infinity
        let target = max(0, min(current + seconds, duration.isFinite ? duration : current + seconds))
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
        if isPaused {
            isPaused = false
            startActive()
        }
    }

    func seekToProgress(_ value: Double) {
        let duration = player.currentItem?.duration.seconds ?? 0
        guard duration.isFinite, duration > 0 else { return }
        player.seek(to: CMTime(seconds: value * duration, preferredTimescale: 600))
    }

    var currentVideo: VideoItem? {
        guard !videos.isEmpty, videos.indices.contains(currentIndex) else { return nil }
        return videos[currentIndex]
    }

    /// After a sync/delete the list (and its indices) can change: re-map slots by file.
    func handleLibraryChange(_ items: [VideoItem], deletedFilename: String?) {
        let oldName = currentVideo?.filename
        let wasCurrent = oldName != nil && oldName == deletedFilename
        videos = items
        if items.isEmpty {
            slots.forEach { $0.unload() }
            currentIndex = 0
            return
        }
        for slot in slots {
            guard let url = slot.url else { continue }
            if let idx = items.firstIndex(where: { $0.url == url }) {
                slot.videoIndex = idx
            } else if slot.id != activeSlotID {
                slot.unload()
            } else {
                slot.videoIndex = nil
            }
        }
        if !wasCurrent, let oldName, let idx = items.firstIndex(where: { $0.filename == oldName }) {
            currentIndex = idx
            preloadNeighbours()
        } else {
            play(index: min(currentIndex, items.count - 1))
        }
    }
}
