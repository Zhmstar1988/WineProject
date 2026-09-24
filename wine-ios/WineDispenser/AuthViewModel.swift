//
//  AuthViewModel.swift
//  WineDispenser
//
//  认证与用户状态管理
//

import Foundation

class AuthViewModel: ObservableObject {
    @Published var token: String? = nil
    @Published var ageVerified: Bool = false
    @Published var userId: Int64? = nil
    @Published var nickname: String = ""

    func sendSMS(phone: String) async throws {
        try await APIClient.shared.requestVoid("/auth/sms/send", body: ["phone": phone])
    }

    func login(phone: String, code: String) async throws {
        let body: [String: Any] = ["phone": phone, "code": code, "loginType": 1]
        let data = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: URL(string: "http://localhost:8080/api/auth/login")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = data

        let (respData, _) = try await URLSession.shared.data(for: request)
        let wrapper = try JSONDecoder().decode(APIResponse<LoginResponse>.self, from: respData)
        if wrapper.code != 200 { throw APIError.business(wrapper.message) }

        await MainActor.run {
            self.token = wrapper.data.token
            self.userId = wrapper.data.userId
            self.ageVerified = wrapper.data.ageVerified
            self.nickname = wrapper.data.nickname
        }
    }

    func verifyAge(birthDate: String) async throws {
        try await APIClient.shared.requestVoid("/auth/age-verify", body: ["birthDate": birthDate], token: token)
        await MainActor.run { self.ageVerified = true }
    }
}
