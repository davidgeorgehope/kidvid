import SwiftUI

@main
struct KidVidApp: App {
    @StateObject private var library = VideoLibrary()
    @StateObject private var syncService = SyncService()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(syncService)
                .preferredColorScheme(.dark)
                .task {
                    library.reload()
                    syncService.attach(library: library)
                    await syncService.syncIfNeeded()
                }
        }
    }
}
