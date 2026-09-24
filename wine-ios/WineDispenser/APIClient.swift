//
//  APIClient.swift
//  WineDispenser
//
//  网络层：统一封装后端 API 调用
//  Base URL 通过 Info.plist 的 WINE_API_BASE_URL 配置，默认 http://localhost:8080/api
//

import Foundation

class APIClient {
    static let shared = APIClient()
    private let baseURL: String

    private init() {
        // 优先读 Info.plist 配置；取不到则用默认值（模拟器走本机）
        if let info = Bundle.main.object(forInfoDictionaryKey: "WINE_API_BASE_URL") as? String,
           !info.isEmpty {
            baseURL = info
        } else {
            baseURL = "http://localhost:8080/api"
        }
    }

    /// 带 body 的请求
    func request<T: Decodable, B: Encodable>(_ path: String,
                               method: String = "GET",
                               body: B,
                               bodyData: Data? = nil,
                               token: String? = nil) async throws -> T {
        guard let url = URL(string: baseURL + path) else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let bodyData = bodyData {
            request.httpBody = bodyData
        } else {
            request.httpBody = try JSONEncoder().encode(body)
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResp = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        let wrapper = try JSONDecoder().decode(APIResponse<T>.self, from: data)
        if wrapper.code != 200 {
            throw APIError.business(wrapper.message)
        }
        return wrapper.data
    }

    /// 不带 body 的请求
    func request<T: Decodable>(_ path: String,
                               method: String = "GET",
                               bodyData: Data? = nil,
                               token: String? = nil) async throws -> T {
        guard let url = URL(string: baseURL + path) else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let bodyData = bodyData {
            request.httpBody = bodyData
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResp = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        let wrapper = try JSONDecoder().decode(APIResponse<T>.self, from: data)
        if wrapper.code != 200 {
            throw APIError.business(wrapper.message)
        }
        return wrapper.data
    }

    /// 带 body 的无返回值请求
    func requestVoid<B: Encodable>(_ path: String,
                     method: String = "POST",
                     body: B,
                     bodyData: Data? = nil,
                     token: String? = nil) async throws {
        guard let url = URL(string: baseURL + path) else {
            throw APIError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let bodyData = bodyData {
            request.httpBody = bodyData
        } else {
            request.httpBody = try JSONEncoder().encode(body)
        }
        let (data, _) = try await URLSession.shared.data(for: request)
        let wrapper = try JSONDecoder().decode(APIResponse<EmptyData>.self, from: data)
        if wrapper.code != 200 {
            throw APIError.business(wrapper.message)
        }
    }

    /// 不带 body 的无返回值请求
    func requestVoid(_ path: String,
                     method: String = "POST",
                     bodyData: Data? = nil,
                     token: String? = nil) async throws {
        guard let url = URL(string: baseURL + path) else {
            throw APIError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let bodyData = bodyData {
            request.httpBody = bodyData
        }
        let (data, _) = try await URLSession.shared.data(for: request)
        let wrapper = try JSONDecoder().decode(APIResponse<EmptyData>.self, from: data)
        if wrapper.code != 200 {
            throw APIError.business(wrapper.message)
        }
    }
}

struct APIResponse<T: Decodable>: Decodable {
    let code: Int
    let message: String
    let data: T
}

struct EmptyData: Decodable {}

enum APIError: Error, LocalizedError {
    case invalidURL
    case invalidResponse
    case business(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "无效的URL"
        case .invalidResponse: return "无效的响应"
        case .business(let msg): return msg
        }
    }
}
