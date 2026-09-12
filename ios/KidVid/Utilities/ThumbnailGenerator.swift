import AVFoundation
import UIKit

enum ThumbnailGenerator {
    private static var cache: [String: UIImage] = [:]
    private static let queue = DispatchQueue(label: "kidvid.thumbs", attributes: .concurrent)

    static func cached(for url: URL) -> UIImage? {
        cache[url.path]
    }

    static func generate(for url: URL, at seconds: Double = 5) async -> UIImage? {
        if let hit = cache[url.path] { return hit }

        return await withCheckedContinuation { cont in
            queue.async {
                let asset = AVURLAsset(url: url)
                let gen = AVAssetImageGenerator(asset: asset)
                gen.appliesPreferredTrackTransform = true
                gen.maximumSize = CGSize(width: 480, height: 480)
                let time = CMTime(seconds: seconds, preferredTimescale: 600)
                do {
                    let cg = try gen.copyCGImage(at: time, actualTime: nil)
                    let img = UIImage(cgImage: cg)
                    cache[url.path] = img
                    cont.resume(returning: img)
                } catch {
                    // Try frame 0
                    do {
                        let cg = try gen.copyCGImage(at: .zero, actualTime: nil)
                        let img = UIImage(cgImage: cg)
                        cache[url.path] = img
                        cont.resume(returning: img)
                    } catch {
                        cont.resume(returning: nil)
                    }
                }
            }
        }
    }

    static func invalidate(path: String) {
        cache.removeValue(forKey: path)
    }
}
