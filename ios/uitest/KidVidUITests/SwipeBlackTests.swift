import XCTest

/// Swipe through the player and make sure the screen never sits black.
final class SwipeBlackTests: XCTestCase {
    private var outDir: String {
        ProcessInfo.processInfo.environment["KV_OUT"] ?? "/tmp/kidvid-uitest"
    }
    private var log: [String] = []

    override func setUp() {
        continueAfterFailure = true
        try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
    }

    func testSwipingNeverLeavesScreenBlack() {
        let app = XCUIApplication()
        app.launch()
        sleep(4)
        _ = shot("00_start", afterSwipe: false)

        var stuckBlack = 0
        var flashBlack = 0
        var step = 0
        // Single swipes, mixed direction.
        let pattern: [Bool] = [true, true, true, false, true, true, false, false, true, true,
                               true, false, true, true, true, false, true, true, true, true]
        for up in pattern {
            step += 1
            if up { app.swipeUp(velocity: .fast) } else { app.swipeDown(velocity: .fast) }
            let t0 = Date()
            for target in [0.1, 0.6, 1.5] {
                let wait = target - Date().timeIntervalSince(t0)
                if wait > 0 { Thread.sleep(forTimeInterval: wait) }
                let name = String(format: "s%02d_%@_%.1fs", step, up ? "up" : "down", target)
                if shot(name, afterSwipe: true) {
                    if target >= 0.6 { stuckBlack += 1 } else { flashBlack += 1 }
                }
            }
        }
        // Kid-style bursts: 3 fast swipes back to back, then look.
        for burst in 1...5 {
            for k in 0..<3 {
                let up = (burst + k) % 4 != 0
                if up { app.swipeUp(velocity: .fast) } else { app.swipeDown(velocity: .fast) }
            }
            let t0 = Date()
            for target in [0.6, 1.5] {
                let wait = target - Date().timeIntervalSince(t0)
                if wait > 0 { Thread.sleep(forTimeInterval: wait) }
                if shot(String(format: "burst%02d_%.1fs", burst, target), afterSwipe: true) {
                    stuckBlack += 1
                }
            }
        }
        log.append("RESULT flashBlack(0.1s)=\(flashBlack) stuckBlack(>=0.6s)=\(stuckBlack)")
        try? log.joined(separator: "\n").write(toFile: outDir + "/summary.txt", atomically: true, encoding: .utf8)
        XCTAssertEqual(stuckBlack, 0, "screen still black >=0.6s after a swipe \(stuckBlack) times")
    }

    /// Saves a screenshot; returns true when the centre video band is black.
    @discardableResult
    private func shot(_ name: String, afterSwipe: Bool) -> Bool {
        let s = XCUIScreen.main.screenshot()
        let png = s.pngRepresentation
        try? png.write(to: URL(fileURLWithPath: outDir + "/" + name + ".png"))
        let (mean, maxV) = centreLuma(s.image)
        var black = mean < 4 && maxV < 24
        let state = activeSlotState()
        // "showing" = the player layer has a decoded frame on screen, so a black
        // picture is the video's own content (fade-in), not the bug.
        if black && state == "showing" { black = false }
        log.append(String(format: "%@ mean=%.1f max=%.0f state=%@ %@", name, mean, maxV, state,
                          black ? "BLACK" : (mean < 4 && maxV < 24 ? "dark-content" : "ok")))
        return black
    }

    /// Accessibility value of the visible player slot (fixed build only; "n/a" before).
    private var app: XCUIApplication { XCUIApplication() }
    private func activeSlotState() -> String {
        for i in 0..<3 {
            let e = app.descendants(matching: .any)["kidvid.slot.\(i)"]
            guard e.exists else { return "n/a" }
            if let v = e.value as? String, v != "idle" { return v }
        }
        return "none"
    }

    /// Luma of the centre band (where a landscape video sits in portrait).
    private func centreLuma(_ image: UIImage) -> (Double, Double) {
        guard let cg = image.cgImage else { return (0, 0) }
        let w = 60, h = 40
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: w * 4, space: cs,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return (0, 0) }
        let W = CGFloat(cg.width), H = CGFloat(cg.height)
        // centre 70% wide x 16% tall band (avoids the 🎬 button and the slider)
        let crop = CGRect(x: W * 0.15, y: H * 0.42, width: W * 0.7, height: H * 0.16)
        guard let sub = cg.cropping(to: crop) else { return (0, 0) }
        ctx.draw(sub, in: CGRect(x: 0, y: 0, width: w, height: h))
        var sum = 0.0, mx = 0.0
        for i in stride(from: 0, to: px.count, by: 4) {
            let y = 0.299 * Double(px[i]) + 0.587 * Double(px[i + 1]) + 0.114 * Double(px[i + 2])
            sum += y; mx = max(mx, y)
        }
        return (sum / Double(w * h), mx)
    }
}
