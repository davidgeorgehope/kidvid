import AVFoundation
import Combine
import Foundation

/// Thin AVPlayer wrapper for fullscreen kid playback.
@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()

    @Published private(set) var isPaused = false
    @Published private(set) var currentIndex = 0
    @Published var progress: Double = 0

    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var videos: [VideoItem] = []

    init() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                let duration = self.player.currentItem?.duration.seconds ?? 0
                if duration.isFinite, duration > 0 {
                    self.progress = time.seconds / duration
                }
            }
        }
    }

    deinit {
        // AVPlayer cleanup; observer tokens are value types / opaque.
        // Prefer stopping from onDisappear in the view when possible.
    }

    func teardown() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    func setPlaylist(_ items: [VideoItem], startAt index: Int = 0) {
        videos = items
        guard !items.isEmpty else { return }
        play(index: max(0, min(index, items.count - 1)))
    }

    func play(index: Int) {
        guard !videos.isEmpty else { return }
        currentIndex = ((index % videos.count) + videos.count) % videos.count
        let item = AVPlayerItem(url: videos[currentIndex].url)
        player.replaceCurrentItem(with: item)
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                // Loop current video (Android MediaPlayer.setLooping(true))
                self?.player.seek(to: .zero)
                self?.player.play()
            }
        }
        isPaused = false
        player.play()
    }

    func next() {
        guard !videos.isEmpty else { return }
        play(index: currentIndex + 1)
    }

    func previous() {
        guard !videos.isEmpty else { return }
        play(index: currentIndex - 1)
    }

    func togglePause() {
        if isPaused {
            player.play()
            isPaused = false
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
            player.play()
            isPaused = false
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

    /// After a delete, keep index valid and optionally switch file.
    func handleLibraryChange(_ items: [VideoItem], deletedFilename: String?) {
        let wasCurrent = currentVideo?.filename == deletedFilename
        let oldName = currentVideo?.filename
        videos = items
        if items.isEmpty {
            player.replaceCurrentItem(with: nil)
            currentIndex = 0
            return
        }
        if wasCurrent {
            play(index: min(currentIndex, items.count - 1))
        } else if let oldName, let idx = items.firstIndex(where: { $0.filename == oldName }) {
            currentIndex = idx
        } else {
            play(index: min(currentIndex, items.count - 1))
        }
    }
}
