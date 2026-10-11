import AVFoundation
import UIKit

/// Library thumbs: prefer sidecar `.jpg`, bound memory, cancel stale work.
/// Unbounded `[String:UIImage]` + uncancellable `copyCGImage` was jetsam/crash-prone on SE.
enum ThumbnailGenerator {
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 64
        c.totalCostLimit = 24 * 1024 * 1024
        return c
    }()

    /// Cap concurrent AV frame extracts — LazyVGrid can request dozens at once.
    private static let gate = DispatchSemaphore(value: 2)
    private static let queue = DispatchQueue(label: "kidvid.thumbs", qos: .utility, attributes: .concurrent)
    private static let maxPixel: CGFloat = 480

    private final class CancelFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var _cancelled = false
        var isCancelled: Bool {
            lock.lock(); defer { lock.unlock() }
            return _cancelled
        }
        func cancel() {
            lock.lock(); _cancelled = true; lock.unlock()
        }
    }

    static func cached(for url: URL) -> UIImage? {
        cache.object(forKey: url.path as NSString)
    }

    static func invalidate(path: String) {
        cache.removeObject(forKey: path as NSString)
    }

    static func clearCache() {
        cache.removeAllObjects()
    }

    /// Prefer stem.jpg sidecar next to the video; else frame at `seconds`.
    /// Honors Task cancellation so recycled LazyVGrid cells don't keep decoding.
    static func generate(for url: URL, at seconds: Double = 5) async -> UIImage? {
        let key = url.path as NSString
        if let hit = cache.object(forKey: key) { return hit }
        if Task.isCancelled { return nil }

        if let img = loadSidecar(for: url) {
            store(img, key: key)
            return img
        }
        if Task.isCancelled { return nil }

        let flag = CancelFlag()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<UIImage?, Never>) in
                queue.async {
                    if flag.isCancelled {
                        cont.resume(returning: nil)
                        return
                    }
                    gate.wait()
                    defer { gate.signal() }
                    if flag.isCancelled {
                        cont.resume(returning: nil)
                        return
                    }
                    if let img = loadSidecar(for: url) {
                        store(img, key: key)
                        cont.resume(returning: img)
                        return
                    }
                    let asset = AVURLAsset(url: url)
                    let gen = AVAssetImageGenerator(asset: asset)
                    gen.appliesPreferredTrackTransform = true
                    gen.maximumSize = CGSize(width: maxPixel, height: maxPixel)
                    let time = CMTime(seconds: seconds, preferredTimescale: 600)
                    do {
                        let cg = try gen.copyCGImage(at: time, actualTime: nil)
                        if flag.isCancelled {
                            cont.resume(returning: nil)
                            return
                        }
                        let img = downscale(UIImage(cgImage: cg))
                        store(img, key: key)
                        cont.resume(returning: img)
                    } catch {
                        do {
                            let cg = try gen.copyCGImage(at: .zero, actualTime: nil)
                            if flag.isCancelled {
                                cont.resume(returning: nil)
                                return
                            }
                            let img = downscale(UIImage(cgImage: cg))
                            store(img, key: key)
                            cont.resume(returning: img)
                        } catch {
                            cont.resume(returning: nil)
                        }
                    }
                }
            }
        } onCancel: {
            flag.cancel()
        }
    }

    private static func loadSidecar(for url: URL) -> UIImage? {
        let sidecar = url.deletingPathExtension().appendingPathExtension("jpg")
        guard FileManager.default.fileExists(atPath: sidecar.path) else { return nil }
        if let attrs = try? FileManager.default.attributesOfItem(atPath: sidecar.path),
           let size = attrs[.size] as? NSNumber,
           size.int64Value > 2_000_000 {
            return nil
        }
        guard let data = try? Data(contentsOf: sidecar),
              let img = UIImage(data: data) else { return nil }
        return downscale(img)
    }

    private static func store(_ img: UIImage, key: NSString) {
        cache.setObject(img, forKey: key, cost: estimatedCost(img))
    }

    private static func estimatedCost(_ img: UIImage) -> Int {
        let w = Int(img.size.width * img.scale)
        let h = Int(img.size.height * img.scale)
        return max(1, w * h * 4)
    }

    private static func downscale(_ img: UIImage) -> UIImage {
        let w = img.size.width * img.scale
        let h = img.size.height * img.scale
        let longest = max(w, h)
        guard longest > maxPixel else { return img }
        let scale = maxPixel / longest
        let newSize = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            img.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
