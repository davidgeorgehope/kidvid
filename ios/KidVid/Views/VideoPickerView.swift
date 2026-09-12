import SwiftUI

/// Thumbnail grid: pins/movies first when `pins.json` exists.
/// Parent delete: hold ~5s (cancel on move) → PIN `123456`.
struct VideoPickerView: View {
    @EnvironmentObject private var library: VideoLibrary
    @EnvironmentObject private var syncService: SyncService

    let currentFilename: String?
    let onSelect: (VideoItem) -> Void
    let onClose: () -> Void

    @State private var deleteTarget: VideoItem?
    @State private var showPinSheet = false
    @State private var toast: String?
    @State private var holdProgress: Double = 0
    @State private var holdingID: String?

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]

    var body: some View {
        ZStack {
            Color(red: 0.08, green: 0.08, blue: 0.12).opacity(0.97)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                header
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(library.videos) { item in
                            VideoThumbCell(
                                item: item,
                                isCurrent: item.filename == currentFilename,
                                holdProgress: holdingID == item.id ? holdProgress : 0,
                                onTap: { onSelect(item) },
                                onHoldArmed: {
                                    deleteTarget = item
                                    showPinSheet = true
                                    holdingID = nil
                                    holdProgress = 0
                                },
                                onHoldProgress: { id, p in
                                    holdingID = id
                                    holdProgress = p
                                },
                                onHoldCancel: {
                                    holdingID = nil
                                    holdProgress = 0
                                }
                            )
                        }
                    }
                    .padding(16)
                }

                if let toast {
                    Text(toast)
                        .font(.footnote)
                        .foregroundStyle(.white)
                        .padding(10)
                } else if !syncService.lastStatus.isEmpty {
                    Text(syncService.lastStatus)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.45))
                        .padding(.bottom, 8)
                }
            }
        }
        .sheet(isPresented: $showPinSheet) {
            ParentDeletePinView(
                title: deleteTarget?.title ?? "this video",
                onConfirm: { Task { await performDelete() } },
                onCancel: {
                    showPinSheet = false
                    deleteTarget = nil
                }
            )
            .presentationDetents([.medium])
        }
    }

    private var header: some View {
        HStack {
            Text("🎬  Pick a Video!")
                .font(.system(size: 20, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)

            Spacer()

            Button {
                Task {
                    await syncService.sync()
                    library.reload()
                    toast = "\(library.videos.count) videos"
                }
            } label: {
                Group {
                    if syncService.isSyncing {
                        ProgressView().tint(.white)
                    } else {
                        Text("🔄")
                            .font(.system(size: 22))
                    }
                }
                .padding(10)
                .background(Color.white.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .disabled(syncService.isSyncing)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Color.white.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 20)
        .padding(.bottom, 8)
    }

    private func performDelete() async {
        guard let target = deleteTarget else { return }
        showPinSheet = false
        toast = "Deleting…"
        let result = await syncService.parentDelete(filename: target.filename)
        ThumbnailGenerator.invalidate(path: target.url.path)
        deleteTarget = nil
        if result.local {
            toast = result.remote
                ? "Deleted: \(target.title)"
                : "Deleted locally; server DELETE failed (may reappear on sync)"
        } else {
            toast = "Delete failed (could not remove file)"
        }
    }
}

private struct VideoThumbCell: View {
    let item: VideoItem
    let isCurrent: Bool
    let holdProgress: Double
    let onTap: () -> Void
    let onHoldArmed: () -> Void
    let onHoldProgress: (String, Double) -> Void
    let onHoldCancel: () -> Void

    @State private var image: UIImage?

    var body: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topTrailing) {
                Group {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Color(white: 0.2)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 120)
                .clipped()
                .overlay {
                    if holdProgress > 0 {
                        Rectangle()
                            .fill(Color.red.opacity(0.35 * holdProgress))
                    }
                }

                if item.isPinned {
                    Text("★")
                        .font(.caption)
                        .padding(6)
                        .background(Color.black.opacity(0.5))
                        .clipShape(Circle())
                        .padding(6)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(item.title)
                .font(.caption)
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.center)
        }
        .padding(8)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(isCurrent
                      ? Color.indigo.opacity(0.35)
                      : Color.white.opacity(0.08))
        )
        .contentShape(Rectangle())
        .parentDeleteHold(
            onTap: onTap,
            onProgress: { onHoldProgress(item.id, $0) },
            onArmed: onHoldArmed,
            onCancel: onHoldCancel
        )
        .task {
            image = await ThumbnailGenerator.generate(for: item.url)
        }
    }
}
