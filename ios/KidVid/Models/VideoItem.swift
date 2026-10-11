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
    let thumb: String?
    let thumb_url: String?
    let thumb_size: Int64?
    /// Lower = download first (goldie/dvd/youtube). Archive ≈ 9.
    let priority: Int?
    let tier: String?

    enum CodingKeys: String, CodingKey {
        case name, size, url, thumb, thumb_url, thumb_size, priority, tier
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decode(Int64.self, forKey: .size)
        url = try c.decode(String.self, forKey: .url)
        thumb = try c.decodeIfPresent(String.self, forKey: .thumb)
        thumb_url = try c.decodeIfPresent(String.self, forKey: .thumb_url)
        thumb_size = try c.decodeIfPresent(Int64.self, forKey: .thumb_size)
        priority = try c.decodeIfPresent(Int.self, forKey: .priority)
        tier = try c.decodeIfPresent(String.self, forKey: .tier)
    }

    var syncPriority: Int { priority ?? 1 }
    var isArchive: Bool { (tier ?? "") == "archive" || syncPriority >= 9 }
}
