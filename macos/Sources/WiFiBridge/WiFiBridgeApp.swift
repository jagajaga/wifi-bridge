import SwiftUI

@main
struct WiFiBridgeApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            Image(systemName: model.menuIcon)
        }

        Window("Pair a phone", id: "pair") {
            PairView(session: model.pairing)
        }
        .windowResizability(.contentSize)
    }
}
