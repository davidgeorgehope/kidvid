import SwiftUI

/// Parent PIN gate after a successful 5s hold on a thumbnail.
struct ParentDeletePinView: View {
    let title: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @State private var pin = ""
    @State private var wrong = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Hold confirmed. Enter parent PIN to permanently remove:")
                    .font(.body)
                Text(title)
                    .font(.headline)

                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .onChange(of: pin) { _, _ in wrong = false }

                if wrong {
                    Text("Wrong PIN")
                        .foregroundStyle(.red)
                        .font(.footnote)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("Delete video?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Delete") {
                        if pin.trimmingCharacters(in: .whitespacesAndNewlines) == AppConfig.parentDeletePIN {
                            onConfirm()
                        } else {
                            wrong = true
                            pin = ""
                        }
                    }
                    .foregroundStyle(.red)
                }
            }
            .onAppear { focused = true }
        }
    }
}
