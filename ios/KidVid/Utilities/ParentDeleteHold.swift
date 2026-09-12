import SwiftUI
import UIKit

/// UIKit-backed interactions for a library cell:
/// - short tap → play
/// - still hold ~5s (cancel on any meaningful move) → parent delete armed
///
/// Designed so ScrollView pans win: the hold recognizer waits ~0.4s before
/// beginning (same idea as Android’s delayed `requestDisallowIntercept`),
/// never cancels touches in-view for scroll, and aborts as soon as the finger
/// moves past slop. Progress UI stays quiet until ~1s so flick scrolls feel
/// normal on a dense SE grid.
struct ParentDeleteHoldModifier: ViewModifier {
    let holdSeconds: TimeInterval
    let cancelDistance: CGFloat
    let recognitionDelay: TimeInterval
    let progressRevealDelay: TimeInterval
    let onTap: () -> Void
    let onProgress: (Double) -> Void
    let onArmed: () -> Void
    let onCancel: () -> Void

    func body(content: Content) -> some View {
        content.overlay(
            ParentDeleteHoldRepresentable(
                holdSeconds: holdSeconds,
                cancelDistance: cancelDistance,
                recognitionDelay: recognitionDelay,
                progressRevealDelay: progressRevealDelay,
                onTap: onTap,
                onProgress: onProgress,
                onArmed: onArmed,
                onCancel: onCancel
            )
        )
    }
}

private struct ParentDeleteHoldRepresentable: UIViewRepresentable {
    let holdSeconds: TimeInterval
    let cancelDistance: CGFloat
    let recognitionDelay: TimeInterval
    let progressRevealDelay: TimeInterval
    let onTap: () -> Void
    let onProgress: (Double) -> Void
    let onArmed: () -> Void
    let onCancel: () -> Void

    func makeUIView(context: Context) -> HoldView {
        let v = HoldView()
        v.isUserInteractionEnabled = true
        v.backgroundColor = .clear
        v.isExclusiveTouch = false
        v.apply(
            holdSeconds: holdSeconds,
            cancelDistance: cancelDistance,
            recognitionDelay: recognitionDelay,
            progressRevealDelay: progressRevealDelay,
            onTap: onTap,
            onProgress: onProgress,
            onArmed: onArmed,
            onCancel: onCancel
        )
        return v
    }

    func updateUIView(_ uiView: HoldView, context: Context) {
        uiView.apply(
            holdSeconds: holdSeconds,
            cancelDistance: cancelDistance,
            recognitionDelay: recognitionDelay,
            progressRevealDelay: progressRevealDelay,
            onTap: onTap,
            onProgress: onProgress,
            onArmed: onArmed,
            onCancel: onCancel
        )
    }

    final class HoldView: UIView {
        var holdSeconds: TimeInterval = 5
        var cancelDistance: CGFloat = 24
        var recognitionDelay: TimeInterval = 0.4
        var progressRevealDelay: TimeInterval = 1.0
        var onTap: (() -> Void)?
        var onProgress: ((Double) -> Void)?
        var onArmed: (() -> Void)?
        var onCancel: (() -> Void)?

        private var longPress: UILongPressGestureRecognizer!
        private var tap: UITapGestureRecognizer!
        private var displayLink: CADisplayLink?
        private var pressDownTime: CFTimeInterval = 0
        private var startPoint: CGPoint = .zero
        private var armed = false
        private var movedTooFar = false
        private var holdActive = false
        private weak var lockedScrollView: UIScrollView?

        func apply(
            holdSeconds: TimeInterval,
            cancelDistance: CGFloat,
            recognitionDelay: TimeInterval,
            progressRevealDelay: TimeInterval,
            onTap: @escaping () -> Void,
            onProgress: @escaping (Double) -> Void,
            onArmed: @escaping () -> Void,
            onCancel: @escaping () -> Void
        ) {
            self.holdSeconds = holdSeconds
            self.cancelDistance = cancelDistance
            self.recognitionDelay = recognitionDelay
            self.progressRevealDelay = progressRevealDelay
            self.onTap = onTap
            self.onProgress = onProgress
            self.onArmed = onArmed
            self.onCancel = onCancel
            longPress?.minimumPressDuration = recognitionDelay
            longPress?.allowableMovement = cancelDistance
        }

        override init(frame: CGRect) {
            super.init(frame: frame)

            // Tap for play — fails if a hold commits, so scroll/flick never selects.
            let tapGR = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            tapGR.cancelsTouchesInView = false
            tapGR.delegate = self
            addGestureRecognizer(tapGR)
            tap = tapGR

            // Hold only begins after a still press (~Android lock-scroll delay).
            // cancelsTouchesInView = false so ScrollView pans are not eaten during
            // the recognition window; once began we disable the enclosing scroll.
            let long = UILongPressGestureRecognizer(target: self, action: #selector(handleLong(_:)))
            long.minimumPressDuration = recognitionDelay
            long.allowableMovement = cancelDistance
            long.cancelsTouchesInView = false
            long.delegate = self
            addGestureRecognizer(long)
            longPress = long

            tapGR.require(toFail: long)
        }

        required init?(coder: NSCoder) { fatalError() }

        @objc private func handleTap(_ g: UITapGestureRecognizer) {
            guard g.state == .ended, !holdActive, !armed else { return }
            onTap?()
        }

        @objc private func handleLong(_ g: UILongPressGestureRecognizer) {
            switch g.state {
            case .began:
                armed = false
                movedTooFar = false
                holdActive = true
                startPoint = g.location(in: nil)
                // Count the full intentional hold from first contact, not from
                // recognition (recognitionDelay already elapsed while still).
                pressDownTime = CACurrentMediaTime() - recognitionDelay
                lockEnclosingScroll(true)
                startLink()
            case .changed:
                guard holdActive, !armed else { return }
                let p = g.location(in: nil)
                let dx = p.x - startPoint.x
                let dy = p.y - startPoint.y
                if (dx * dx + dy * dy) > (cancelDistance * cancelDistance) {
                    movedTooFar = true
                    abortHold(notifyCancel: true)
                }
            case .ended, .cancelled, .failed:
                // Armed path already presented PIN; just clean up.
                if armed {
                    abortHold(notifyCancel: false)
                } else if holdActive {
                    abortHold(notifyCancel: true)
                }
            default:
                break
            }
        }

        private func startLink() {
            displayLink?.invalidate()
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }

        @objc private func tick() {
            guard holdActive, !armed, !movedTooFar else { return }
            let elapsed = CACurrentMediaTime() - pressDownTime
            // Stay quiet during early press so scroll attempts never flash red.
            if elapsed < progressRevealDelay {
                return
            }
            let p = min(1, elapsed / holdSeconds)
            onProgress?(p)
            if p >= 1 {
                armed = true
                holdActive = false
                displayLink?.invalidate()
                displayLink = nil
                lockEnclosingScroll(false)
                onArmed?()
            }
        }

        private func abortHold(notifyCancel: Bool) {
            displayLink?.invalidate()
            displayLink = nil
            holdActive = false
            armed = false
            movedTooFar = false
            lockEnclosingScroll(false)
            if notifyCancel {
                onCancel?()
            }
        }

        private func lockEnclosingScroll(_ lock: Bool) {
            if lock {
                if lockedScrollView == nil {
                    lockedScrollView = enclosingScrollView()
                }
                lockedScrollView?.isScrollEnabled = false
            } else {
                lockedScrollView?.isScrollEnabled = true
                lockedScrollView = nil
            }
        }

        private func enclosingScrollView() -> UIScrollView? {
            var view: UIView? = superview
            while let current = view {
                if let scroll = current as? UIScrollView {
                    return scroll
                }
                view = current.superview
            }
            return nil
        }
    }
}

extension ParentDeleteHoldRepresentable.HoldView: UIGestureRecognizerDelegate {
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // Never compete with the scroll pan — if the user is dragging, scroll wins
        // and our long-press fails via allowableMovement / cancelDistance.
        if otherGestureRecognizer is UIPanGestureRecognizer {
            return false
        }
        return true
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        false
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        true
    }
}

extension View {
    func parentDeleteHold(
        onTap: @escaping () -> Void,
        onProgress: @escaping (Double) -> Void,
        onArmed: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) -> some View {
        modifier(ParentDeleteHoldModifier(
            holdSeconds: AppConfig.parentDeleteHoldSeconds,
            cancelDistance: AppConfig.parentDeleteCancelDistance,
            recognitionDelay: AppConfig.parentDeleteRecognitionDelay,
            progressRevealDelay: AppConfig.parentDeleteProgressRevealDelay,
            onTap: onTap,
            onProgress: onProgress,
            onArmed: onArmed,
            onCancel: onCancel
        ))
    }
}
