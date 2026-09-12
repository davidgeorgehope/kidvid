import Foundation

/// A local video available for playback.
struct VideoItem: Identifiable, Hashable {
    let id: String
    let filename: String
    let url: URL
    let title: String
    var isPinned: Bool

    init(url: URL, title: String? = nil, isPinned: Bool = false) {
        self.url = url
        self.filename = url.lastPathComponent
        self.id = filename
        if let title, !title.isEmpty {
            self.title = title
        } else {
            self.title = Self.prettyTitle(from: filename)
        }
        self.isPinned = isPinned
    }

    static func prettyTitle(from filename: String) -> String {
        let base = (filename as NSString).deletingPathExtension
        return base
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
    }
}

/// Remote video descriptor from `GET /videos`.
struct RemoteVideo: Decodable, Hashable {
    let name: String
    let size: Int64
    let url: String
}
