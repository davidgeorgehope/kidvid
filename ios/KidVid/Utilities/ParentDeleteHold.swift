import SwiftUI
import UIKit

/// UIKit-backed interactions for a library cell:
/// - short tap → play
/// - hold ~5s (cancel on move) → parent delete armed
struct ParentDeleteHoldModifier: ViewModifier {
    let holdSeconds: TimeInterval
    let cancelDistance: CGFloat
    let onTap: () -> Void
    let onProgress: (Double) -> Void
    let onArmed: () -> Void
    let onCancel: () -> Void

    func body(content: Content) -> some View {
        content.overlay(
            ParentDeleteHoldRepresentable(
                holdSeconds: holdSeconds,
                cancelDistance: cancelDistance,
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
    let onTap: () -> Void
    let onProgress: (Double) -> Void
    let onArmed: () -> Void
    let onCancel: () -> Void

    func makeUIView(context: Context) -> HoldView {
        let v = HoldView()
        v.isUserInteractionEnabled = true
        v.backgroundColor = .clear
        v.apply(holdSeconds: holdSeconds, cancelDistance: cancelDistance,
                onTap: onTap, onProgress: onProgress, onArmed: onArmed, onCancel: onCancel)
        return v
    }

    func updateUIView(_ uiView: HoldView, context: Context) {
        uiView.apply(holdSeconds: holdSeconds, cancelDistance: cancelDistance,
                     onTap: onTap, onProgress: onProgress, onArmed: onArmed, onCancel: onCancel)
    }

    final class HoldView: UIView {
        var holdSeconds: TimeInterval = 5
        var cancelDistance: CGFloat = 40
        var onTap: (() -> Void)?
        var onProgress: ((Double) -> Void)?
        var onArmed: (() -> Void)?
        var onCancel: (() -> Void)?

        private var displayLink: CADisplayLink?
        private var startTime: CFTimeInterval = 0
        private var startPoint: CGPoint = .zero
        private var armed = false
        private var movedTooFar = false

        func apply(
            holdSeconds: TimeInterval,
            cancelDistance: CGFloat,
            onTap: @escaping () -> Void,
            onProgress: @escaping (Double) -> Void,
            onArmed: @escaping () -> Void,
            onCancel: @escaping () -> Void
        ) {
            self.holdSeconds = holdSeconds
            self.cancelDistance = cancelDistance
            self.onTap = onTap
            self.onProgress = onProgress
            self.onArmed = onArmed
            self.onCancel = onCancel
        }

        override init(frame: CGRect) {
            super.init(frame: frame)
            let long = UILongPressGestureRecognizer(target: self, action: #selector(handle(_:)))
            long.minimumPressDuration = 0.01
            long.allowableMovement = 10_000
            long.cancelsTouchesInView = true
            long.delegate = self
            addGestureRecognizer(long)
        }

        required init?(coder: NSCoder) { fatalError() }

        @objc private func handle(_ g: UILongPressGestureRecognizer) {
            switch g.state {
            case .began:
                armed = false
                movedTooFar = false
                startPoint = g.location(in: self)
                startTime = CACurrentMediaTime()
                startLink()
            case .changed:
                let p = g.location(in: self)
                let dx = p.x - startPoint.x
                let dy = p.y - startPoint.y
                if (dx * dx + dy * dy) > (cancelDistance * cancelDistance) {
                    movedTooFar = true
                    stopLink(cancelled: true)
                }
            case .ended, .cancelled, .failed:
                let wasArmed = armed
                let elapsed = CACurrentMediaTime() - startTime
                stopLink(cancelled: !wasArmed)
                // Short press without arming → treat as tap (Android: normal tap still plays)
                if !wasArmed && !movedTooFar && g.state == .ended && elapsed < holdSeconds {
                    onTap?()
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
            let elapsed = CACurrentMediaTime() - startTime
            let p = min(1, elapsed / holdSeconds)
            onProgress?(p)
            if p >= 1 {
                armed = true
                displayLink?.invalidate()
                displayLink = nil
                onArmed?()
            }
        }

        private func stopLink(cancelled: Bool) {
            displayLink?.invalidate()
            displayLink = nil
            if cancelled {
                onCancel?()
            }
        }
    }
}

extension ParentDeleteHoldRepresentable.HoldView: UIGestureRecognizerDelegate {
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
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
            onTap: onTap,
            onProgress: onProgress,
            onArmed: onArmed,
            onCancel: onCancel
        ))
    }
}
