//
//  Models.swift
//  WineDispenser
//
//  数据模型
//

import Foundation

// 登录响应
struct LoginResponse: Codable {
    let token: String
    let userId: Int64
    let role: Int
    let ageVerified: Bool
    let nickname: String
    let isNewUser: Bool?
}

// 酒单
struct MenuResponse: Codable {
    let barId: Int64
    let barName: String
    let wines: [WineItem]
}

struct WineItem: Codable, Identifiable {
    let id = UUID()
    let wineSkuId: Int64
    let wineName: String
    let origin: String?
    let vintage: Int?
    let grapeType: String?
    let alcohol: String?
    let coverImage: String?
    let description: String?
    let currentCapacity: Int?
    let cupOptions: [CupOption]

    enum CodingKeys: String, CodingKey {
        case wineSkuId, wineName, origin, vintage, grapeType, alcohol
        case coverImage, description, currentCapacity, cupOptions
    }
}

struct CupOption: Codable {
    let volumeMl: Int
    let volumeName: String
    let price: Double
    let slotNo: Int?
    let dispenserId: Int64?
}

// 订单
struct OrderResponse: Codable {
    let orderNo: String
    let status: Int
    let statusName: String
    let barId: Int64
    let barName: String?
    let dispenserId: Int64
    let slotNo: Int
    let wineSkuId: Int64
    let wineName: String?
    let volumeMl: Int
    let originalAmount: Double
    let discountAmount: Double
    let paidAmount: Double
    let cashierUrl: String?
}

// 分酒机状态
struct DispenserStatus: Codable {
    let status: String
    let cupPresent: Int

    enum CodingKeys: String, CodingKey {
        case status
        case cupPresent = "cup_present"
    }
}
