//
//  MenuView.swift
//  WineDispenser
//
//  酒单浏览页：按酒吧展示可售酒款及杯量规格
//

import SwiftUI

struct MenuView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var menu: MenuResponse?
    @State private var barCode: String = "SG_BAR_01"
    @State private var selectedWine: WineItem?
    @State private var selectedCup: CupOption?

    var body: some View {
        NavigationStack {
            VStack {
                HStack {
                    TextField("酒吧编码", text: $barCode)
                        .textFieldStyle(.roundedBorder)
                    Button("查询") { Task { await loadMenu() } }
                        .buttonStyle(.borderedProminent)
                }
                .padding()

                if let menu = menu {
                    Text(menu.barName).font(.headline)
                    List(menu.wines) { wine in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(wine.wineName).font(.headline)
                            if let origin = wine.origin { Text(origin).font(.caption).foregroundColor(.secondary) }
                            HStack {
                                ForEach(wine.cupOptions, id: \.volumeMl) { cup in
                                    Button("\(cup.volumeName) \(cup.price)SGD") {
                                        selectedWine = wine
                                        selectedCup = cup
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }
                            if let cap = wine.currentCapacity {
                                Text("余量: \(cap)ml").font(.caption2).foregroundColor(cap < 100 ? .orange : .gray)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                } else {
                    ProgressView().padding()
                }
            }
            .navigationTitle("酒单")
            .onAppear { Task { await loadMenu() } }
            .sheet(item: Binding(
                get: { selectedWine.map { SheetItem(wine: $0, cup: selectedCup!) } },
                set: { _ in selectedWine = nil }
            )) { item in
                OrderConfirmView(wine: item.wine, cup: item.cup)
            }
        }
    }

    private func loadMenu() async {
        do {
            menu = try await APIClient.shared.request("/menu/bar/\(barCode)")
        } catch {
            print("加载酒单失败: \(error)")
        }
    }
}

struct SheetItem: Identifiable {
    let id = UUID()
    let wine: WineItem
    let cup: CupOption
}
