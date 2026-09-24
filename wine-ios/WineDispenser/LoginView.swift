//
//  LoginView.swift
//  WineDispenser
//
//  登录页：手机号 SMS OTP + Sign in with Apple
//

import SwiftUI
import AuthenticationServices

struct LoginView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var phone: String = ""
    @State private var code: String = ""
    @State private var showingCode: Bool = false
    @State private var errorMsg: String = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "wineglass")
                    .font(.system(size: 64))
                    .foregroundColor(.red)
                Text("新加坡智能分酒")
                    .font(.title2.bold())

                VStack(spacing: 16) {
                    TextField("手机号 (+65...)", text: $phone)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)

                    if showingCode {
                        HStack {
                            TextField("验证码", text: $code)
                                .textFieldStyle(.roundedBorder)
                                .keyboardType(.numberPad)
                            Button("登录") {
                                Task { await doLogin() }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    } else {
                        Button("发送验证码") {
                            Task { await sendCode() }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(phone.isEmpty)
                    }
                }
                .padding(.horizontal)

                SignInWithAppleButton(.signIn) { request in
                    request.requestedScopes = [.fullName, .email]
                } onCompletion: { result in
                    // Apple 登录回调（一期简化）
                }
                .frame(height: 44)
                .padding(.horizontal)

                if !errorMsg.isEmpty {
                    Text(errorMsg).foregroundColor(.red).font(.caption)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("登录")
        }
    }

    private func sendCode() async {
        do {
            try await authVM.sendSMS(phone: phone)
            showingCode = true
        } catch {
            errorMsg = error.localizedDescription
        }
    }

    private func doLogin() async {
        do {
            try await authVM.login(phone: phone, code: code)
        } catch {
            errorMsg = error.localizedDescription
        }
    }
}
