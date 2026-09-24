//
//  MainTabView.swift
//  WineDispenser
//
//  底部导航
//

import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            MenuView()
                .tabItem { Label("酒单", systemImage: "wineglass") }
            OrderHistoryView()
                .tabItem { Label("订单", systemImage: "list.bullet") }
            ProfileView()
                .tabItem { Label("我的", systemImage: "person") }
        }
    }
}

struct OrderHistoryView: View {
    var body: some View {
        NavigationStack {
            List {
                Text("暂无订单记录").foregroundColor(.secondary)
            }
            .navigationTitle("订单历史")
        }
    }
}

struct ProfileView: View {
    @EnvironmentObject var authVM: AuthViewModel
    var body: some View {
        NavigationStack {
            Form {
                Section("用户") {
                    Text("昵称: \(authVM.nickname)")
                    Text("ID: \(authVM.userId ?? 0)")
                }
                Section {
                    Button("退出登录", role: .destructive) {
                        authVM.token = nil
                    }
                }
            }
            .navigationTitle("我的")
        }
    }
}
