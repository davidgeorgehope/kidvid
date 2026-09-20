import Foundation

/// Shared configuration mirroring Android `SyncService` defaults.
enum AppConfig {
    /// Production video server (same host as Android).
    static let defaultServerURL = URL(string: "https://files.signal.observer")!

    private static let deviceIDKey = "kidvid.device"

    /// Stable per-install device id for `/videos?device=` and `/acked`.
    /// Override via UserDefaults (`kidvid.device`) e.g. `iphone-yellow`.
    /// If unset, generates and persists `iphone-<8 hex>` once.
    static var deviceID: String {
        get {
            let raw = UserDefaults.standard.string(forKey: deviceIDKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if let raw, !raw.isEmpty { return raw }
            let generated = "iphone-" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))
            UserDefaults.standard.set(generated, forKey: deviceIDKey)
            return generated
        }
        set {
            UserDefaults.standard.set(newValue, forKey: deviceIDKey)
        }
    }

    /// Buckets checked for pending remote deletes (this install + legacy CoS `phone`).
    static var deleteBuckets: [String] {
        Array(Set([deviceID, "phone"]))
    }

    static var serverBaseURL: URL {
        get {
            if let s = UserDefaults.standard.string(forKey: "kidvid.serverURL"),
               let u = URL(string: s) {
                return u
            }
            return defaultServerURL
        }
        set {
            UserDefaults.standard.set(newValue.absoluteString, forKey: "kidvid.serverURL")
        }
    }

    /// Parent-gated delete PIN (same as Android).
    static let parentDeletePIN = "123456"

    /// Hold duration before PIN dialog (same as Android).
    static let parentDeleteHoldSeconds: TimeInterval = 5.0

    /// Still-press delay before a hold commits (lets ScrollView claim flicks).
    /// Mirrors Android `PARENT_DELETE_LOCK_SCROLL_MS` (~450ms).
    static let parentDeleteRecognitionDelay: TimeInterval = 0.4

    /// Delay before hold progress UI appears (mirrors Android ~1s toast/red tint).
    static let parentDeleteProgressRevealDelay: TimeInterval = 1.0

    /// Movement threshold (points) that cancels a parent-delete hold.
    /// ~3× default touch slop so intentional holds tolerate tremor, but real
    /// scroll/drags abort immediately (same idea as Android `slop * 3`).
    static let parentDeleteCancelDistance: CGFloat = 30

    /// Seek amounts for left/right taps (product README spirit).
    static let seekTapSeconds: Double = 5
    static let seekDoubleTapSeconds: Double = 15
    static let seekHardPressSeconds: Double = 10

    /// How often background sync may run automatically.
    static let syncInterval: TimeInterval = 15 * 60
}
