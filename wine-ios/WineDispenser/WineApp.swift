//
//  WineApp.swift
//  WineDispenser
//
//  新加坡红酒智能分酒 - iOS 原生 App
//

import SwiftUI

@main
struct WineApp: App {
    @StateObject private var authVM = AuthViewModel()
    @State private var barId: String?

    var body: some Scene {
        WindowGroup {
            Group {
                if authVM.token == nil {
                    LoginView()
                } else if authVM.ageVerified == false {
                    AgeVerifyView()
                } else {
                    MainTabView()
                }
            }
            .environmentObject(authVM)
            .onOpenURL { url in
                // Universal Link: app.wine.sg/landing?bar_id=SG_BAR_01
                if let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                   let barIdParam = components.queryItems?.first(where: { $0.name == "bar_id" })?.value {
                    self.barId = barIdParam
                }
            }
        }
    }
}
