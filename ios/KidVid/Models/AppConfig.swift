import Foundation

/// Shared configuration mirroring Android `SyncService` defaults.
enum AppConfig {
    /// Production video server (same host as Android).
    static let defaultServerURL = URL(string: "https://files.signal.observer")!

    /// Device queue label for pending deletes (`phone` | `fire`).
    /// iPhone defaults to `phone`; override via UserDefaults key `kidvid.device`.
    static var deviceID: String {
        get {
            let raw = UserDefaults.standard.string(forKey: "kidvid.device")?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if let raw, !raw.isEmpty { return raw }
            return "phone"
        }
        set {
            UserDefaults.standard.set(newValue, forKey: "kidvid.device")
        }
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

    /// Movement threshold (points) that cancels a parent-delete hold.
    static let parentDeleteCancelDistance: CGFloat = 40

    /// Seek amounts for left/right taps (product README spirit).
    static let seekTapSeconds: Double = 5
    static let seekDoubleTapSeconds: Double = 15
    static let seekHardPressSeconds: Double = 10

    /// How often background sync may run automatically.
    static let syncInterval: TimeInterval = 15 * 60
}
