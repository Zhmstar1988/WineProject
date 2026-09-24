//
//  AgeVerifyView.swift
//  WineDispenser
//
//  法定年龄校验（一期手动输入出生日期，不采集 NRIC）
//

import SwiftUI

struct AgeVerifyView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var birthDate = Date()
    @State private var errorMsg = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "person.badge.shield.checkmark")
                    .font(.system(size: 56))
                    .foregroundColor(.blue)

                Text("请确认您已年满 18 周岁")
                    .font(.title3.bold())

                Text("根据新加坡法律，购买酒精饮品需年满 18 周岁。\n我们不会收集您的身份证号码。")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                DatePicker("出生日期", selection: $birthDate, displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .padding()

                Button("确认提交") {
                    Task { await submit() }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isAdult)

                if !errorMsg.isEmpty {
                    Text(errorMsg).foregroundColor(.red)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("年龄确认")
        }
    }

    private var isAdult: Bool {
        Calendar.current.date(byAdding: .year, value: 18, to: birthDate)! <= Date()
    }

    private func submit() async {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        do {
            try await authVM.verifyAge(birthDate: fmt.string(from: birthDate))
        } catch {
            errorMsg = error.localizedDescription
        }
    }
}
