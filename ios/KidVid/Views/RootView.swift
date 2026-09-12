import SwiftUI

/// Root: fullscreen player with optional video picker overlay.
struct RootView: View {
    @EnvironmentObject private var library: VideoLibrary
    @EnvironmentObject private var syncService: SyncService
    @StateObject private var playerModel = PlayerModel()

    @State private var showPicker = false
    @State private var didBootstrap = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if library.isEmpty {
                EmptyLibraryView(
                    status: syncService.lastStatus,
                    isSyncing: syncService.isSyncing,
                    onSync: { Task { await syncService.sync() } },
                    onBrowse: { showPicker = true }
                )
            } else {
                PlayerView(model: playerModel, showPicker: $showPicker)
            }

            if showPicker {
                VideoPickerView(
                    currentFilename: playerModel.currentVideo?.filename,
                    onSelect: { item in
                        showPicker = false
                        if let idx = library.index(of: item.filename) {
                            playerModel.play(index: idx)
                        }
                    },
                    onClose: { showPicker = false }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(10)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showPicker)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onChange(of: library.videos) { _, newVideos in
            if !didBootstrap, !newVideos.isEmpty {
                playerModel.setPlaylist(newVideos, startAt: 0)
                didBootstrap = true
            } else if didBootstrap {
                playerModel.handleLibraryChange(newVideos, deletedFilename: nil)
            }
        }
        .onAppear {
            if !library.videos.isEmpty && !didBootstrap {
                playerModel.setPlaylist(library.videos, startAt: 0)
                didBootstrap = true
            }
        }
    }
}

private struct EmptyLibraryView: View {
    let status: String
    let isSyncing: Bool
    let onSync: () -> Void
    let onBrowse: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("KidVid")
                .font(.system(size: 42, weight: .bold, design: .rounded))
                .foregroundStyle(.white)

            Text("No videos yet.\nSync from the server, or copy files into Documents/kidvid/videos/.")
                .font(.system(size: 16, design: .rounded))
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.75))
                .padding(.horizontal, 32)

            if !status.isEmpty {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.5))
            }

            Button(action: onSync) {
                HStack {
                    if isSyncing {
                        ProgressView().tint(.white)
                    }
                    Text(isSyncing ? "Syncing…" : "Sync now")
                }
                .font(.headline)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .background(Color.white.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .disabled(isSyncing)
            .foregroundStyle(.white)

            Button("Open library", action: onBrowse)
                .foregroundStyle(.white.opacity(0.7))
        }
    }
}
