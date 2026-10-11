import AVKit
import SwiftUI
import UIKit

/// Fullscreen kid player: swipe next/prev, tap L/R seek, long-press pause.
struct PlayerView: View {
    @ObservedObject var model: PlayerModel
    @Binding var showPicker: Bool

    @State private var showSeekHint: String?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Poster of the current video sits underneath, so while a video is
                // still opening (or can't be played) you see its picture, never black.
                ActivePoster(slot: model.activeSlot, item: model.currentVideo)
                    .ignoresSafeArea()

                // Every player keeps its own layer for the whole session; a swipe just
                // changes which one is visible.
                ForEach(model.slots) { slot in
                    SlotLayerView(slot: slot, isActive: slot.id == model.activeSlotID)
                        .ignoresSafeArea()
                }

                KidPlayerGestureOverlay(
                    onSwipeUp: { model.next() },
                    onSwipeDown: { model.previous() },
                    onTapLeft: { seconds in
                        model.seek(bySeconds: -seconds)
                        flashHint("⏪ \(Int(seconds))s")
                    },
                    onTapRight: { seconds in
                        model.seek(bySeconds: seconds)
                        flashHint("⏩ \(Int(seconds))s")
                    },
                    onLongPress: { model.togglePause() }
                )
                .ignoresSafeArea()

                if let hint = showSeekHint {
                    Text(hint)
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .padding(16)
                        .background(Color.black.opacity(0.45))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }

                if model.isPaused {
                    Image(systemName: "pause.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.white.opacity(0.7))
                        .allowsHitTesting(false)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .overlay(alignment: .topTrailing) {
                Button {
                    showPicker = true
                } label: {
                    Text("🎬")
                        .font(.system(size: 28))
                        .padding(14)
                        .background(Color.black.opacity(0.45))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(.trailing, 20)
                .padding(.top, 16)
            }
            .overlay(alignment: .bottom) {
                Slider(
                    value: Binding(
                        get: { model.progress },
                        set: { model.seekToProgress($0) }
                    ),
                    in: 0...1
                )
                .tint(.white)
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
                .opacity(0.55)
            }
        }
        .ignoresSafeArea()
        // Kids swipe from the very edge: make the first edge swipe stay in the app
        // instead of pulling down Notification Center or going Home.
        .defersSystemGestures(on: .vertical)
    }

    private func flashHint(_ text: String) {
        withAnimation { showSeekHint = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            withAnimation { showSeekHint = nil }
        }
    }
}

/// One slot's AVPlayerLayer. Always in the hierarchy (bound to the same AVPlayer
/// forever); shown only when it's the active slot and has a real frame.
struct SlotLayerView: View {
    @ObservedObject var slot: PlayerSlot
    let isActive: Bool

    var body: some View {
        VideoPlayerLayer(slot: slot)
            .opacity(isActive && slot.hasFrame ? 1 : 0)
            .allowsHitTesting(false)
            .accessibilityElement()
            .accessibilityIdentifier("kidvid.slot.\(slot.id)")
            .accessibilityValue(isActive ? stateLabel : "idle")
    }

    private var stateLabel: String {
        if slot.hasFrame { return "showing" }
        if slot.failed { return "failed" }
        if slot.noPicture { return "noPicture" }
        return "loading"
    }
}

/// Bare AVPlayer layer — no system playback chrome.
struct VideoPlayerLayer: UIViewRepresentable {
    let slot: PlayerSlot

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.backgroundColor = .clear
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = slot.player
        slot.layer = view.playerLayer
        view.onReadyForDisplay = { [weak slot] in
            slot?.layerReadyChanged()
        }
        return view
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        // Never re-assign playerLayer.player here: re-binding a layer blanks it
        // until the next decoded frame.
        if uiView.playerLayer.player !== slot.player {
            uiView.playerLayer.player = slot.player
        }
        if slot.layer !== uiView.playerLayer { slot.layer = uiView.playerLayer }
    }
}

final class PlayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    var onReadyForDisplay: (() -> Void)?
    private var readyObservation: NSKeyValueObservation?

    override init(frame: CGRect) {
        super.init(frame: frame)
        readyObservation = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.onReadyForDisplay?() }
        }
    }

    required init?(coder: NSCoder) { fatalError() }
}

/// Poster for the current video, hidden once its player has a real frame.
struct ActivePoster: View {
    @ObservedObject var slot: PlayerSlot
    let item: VideoItem?

    var body: some View {
        PosterView(item: item)
            .overlay(alignment: .bottom) {
                if slot.noPicture {
                    // Sound only: this phone can't decode the picture of this file.
                    Text("🎵")
                        .font(.system(size: 44))
                        .padding(.bottom, 90)
                }
            }
            .opacity(slot.hasFrame ? 0 : 1)
            .allowsHitTesting(false)
    }
}

/// Still picture for the current video (sidecar .jpg or a generated frame).
struct PosterView: View {
    let item: VideoItem?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else if let item {
                Text(item.title)
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(32)
            }
        }
        .task(id: item?.url) {
            guard let url = item?.url else { image = nil; return }
            if let hit = ThumbnailGenerator.cached(for: url) {
                image = hit
                return
            }
            image = nil
            let generated = await ThumbnailGenerator.generate(for: url, at: 0.5)
            guard !Task.isCancelled, item?.url == url else { return }
            image = generated
        }
    }
}

// MARK: - Kid gestures (UIKit)

struct KidPlayerGestureOverlay: UIViewRepresentable {
    var onSwipeUp: () -> Void
    var onSwipeDown: () -> Void
    var onTapLeft: (Double) -> Void
    var onTapRight: (Double) -> Void
    var onLongPress: () -> Void

    func makeUIView(context: Context) -> GestureView {
        let v = GestureView()
        v.onSwipeUp = onSwipeUp
        v.onSwipeDown = onSwipeDown
        v.onTapLeft = onTapLeft
        v.onTapRight = onTapRight
        v.onLongPress = onLongPress
        v.backgroundColor = .clear
        v.isMultipleTouchEnabled = false
        return v
    }

    func updateUIView(_ uiView: GestureView, context: Context) {
        uiView.onSwipeUp = onSwipeUp
        uiView.onSwipeDown = onSwipeDown
        uiView.onTapLeft = onTapLeft
        uiView.onTapRight = onTapRight
        uiView.onLongPress = onLongPress
    }

    final class GestureView: UIView, UIGestureRecognizerDelegate {
        var onSwipeUp: (() -> Void)?
        var onSwipeDown: (() -> Void)?
        var onTapLeft: ((Double) -> Void)?
        var onTapRight: ((Double) -> Void)?
        var onLongPress: (() -> Void)?

        private var lastTapAt: TimeInterval = 0
        private var lastTapLeft = false

        override init(frame: CGRect) {
            super.init(frame: frame)

            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            tap.delegate = self
            addGestureRecognizer(tap)

            let long = UILongPressGestureRecognizer(target: self, action: #selector(handleLong(_:)))
            long.minimumPressDuration = 0.45
            long.delegate = self
            addGestureRecognizer(long)

            let up = UISwipeGestureRecognizer(target: self, action: #selector(handleSwipe(_:)))
            up.direction = .up
            up.delegate = self
            addGestureRecognizer(up)

            let down = UISwipeGestureRecognizer(target: self, action: #selector(handleSwipe(_:)))
            down.direction = .down
            down.delegate = self
            addGestureRecognizer(down)

            tap.require(toFail: long)
        }

        required init?(coder: NSCoder) { fatalError() }

        @objc private func handleTap(_ g: UITapGestureRecognizer) {
            let x = g.location(in: self).x
            let left = x < bounds.width / 2
            let now = CACurrentMediaTime()
            var seconds = AppConfig.seekTapSeconds
            if now - lastTapAt < 0.35 && lastTapLeft == left {
                seconds = AppConfig.seekDoubleTapSeconds
            }
            lastTapAt = now
            lastTapLeft = left
            if left {
                onTapLeft?(seconds)
            } else {
                onTapRight?(seconds)
            }
        }

        @objc private func handleLong(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began else { return }
            onLongPress?()
        }

        @objc private func handleSwipe(_ g: UISwipeGestureRecognizer) {
            if g.direction == .up {
                onSwipeUp?()
            } else if g.direction == .down {
                onSwipeDown?()
            }
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            false
        }
    }
}
