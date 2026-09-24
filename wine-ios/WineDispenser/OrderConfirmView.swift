//
//  OrderConfirmView.swift
//  WineDispenser
//
//  下单确认页 + 支付 + 放杯出酒流程
//  支付流程：创建订单 → 调起通联国际收银台(ASWebAuthenticationSession) → 轮询订单状态 → 履约
//

import SwiftUI
import AuthenticationServices

struct OrderConfirmView: View {
    @EnvironmentObject var authVM: AuthViewModel
    let wine: WineItem
    let cup: CupOption

    @State private var order: OrderResponse?
    @State private var phase: Phase = .confirm
    @State private var countdown: Int = 3
    @State private var cupPlaced: Bool = false
    @State private var errorMsg = ""
    @State private var cashierLoading = false

    enum Phase {
        case confirm, paying, ready, dispensing, done
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(wine.wineName).font(.title2.bold())
                Text("\(cup.volumeName) - \(cup.volumeMl)ml").font(.subheadline)
                Text("SGD \(cup.price, specifier: "%.2f")").font(.title).foregroundColor(.red)

                Spacer()

                switch phase {
                case .confirm:
                    Button("下单支付") { Task { await createAndPay() } }
                        .buttonStyle(.borderedProminent)

                case .paying:
                    ProgressView("支付中...请在收银台完成付款")
                    if let url = order?.cashierUrl, !url.isEmpty {
                        Text("收银台已打开，完成后会自动跳转").font(.caption).foregroundColor(.secondary)
                    } else {
                        Text("（mock 模式）正在等待通联异步回调").font(.caption).foregroundColor(.secondary)
                    }
                    if cashierLoading, let url = order?.cashierUrl, !url.isEmpty {
                        Button("重新打开收银台") { Task { await openCashier(url: url) } }
                    }

                case .ready:
                    VStack(spacing: 16) {
                        Image(systemName: "cup.and.saucer.fill")
                            .font(.system(size: 64)).foregroundColor(.blue)
                        Text("请将酒杯放置于 \(cup.slotNo ?? 0) 号出酒口下方")
                            .font(.headline)
                        Toggle("我已放好酒杯", isOn: $cupPlaced)
                        if cupPlaced {
                            Text(countdown > 0 ? "\(countdown)" : "可以出酒")
                                .font(.system(size: 48, weight: .bold))
                                .foregroundColor(countdown > 0 ? .orange : .green)
                            Button("开始出酒") {
                                Task { await startDispense() }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(countdown > 0)
                        }
                    }

                case .dispensing:
                    ProgressView("出酒中...").font(.title2)
                    Text("正在为您倒酒，请稍候")

                case .done:
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 64)).foregroundColor(.green)
                    Text("出酒完成，请慢用！").font(.title2)
                }

                if !errorMsg.isEmpty {
                    Text(errorMsg).foregroundColor(.red)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("确认订单")
            .onChange(of: cupPlaced) { _, placed in
                if placed { startCountdown() }
            }
        }
    }

    private func startCountdown() {
        countdown = 3
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { t in
            countdown -= 1
            if countdown <= 0 { t.invalidate() }
        }
    }

    /// 创建订单 + 调起支付 + 打开收银台 + 轮询状态
    private func createAndPay() async {
        errorMsg = ""
        do {
            // 1. 创建订单
            let body: [String: Any] = [
                "barId": 1001,
                "dispenserId": cup.dispenserId ?? 0,
                "slotNo": cup.slotNo ?? 0,
                "wineSkuId": wine.wineSkuId,
                "volumeMl": cup.volumeMl
            ]
            let data = try JSONSerialization.data(withJSONObject: body)
            let created: OrderResponse = try await APIClient.shared.request(
                "/order/create", method: "POST", bodyData: data, token: authVM.token
            )
            order = created
            // 2. 调起支付，拿到通联收银台 URL
            let paid: OrderResponse = try await APIClient.shared.request(
                "/payment/pay/\(created.orderNo)", method: "POST", token: authVM.token
            )
            order = paid
            phase = .paying
            // 3. 打开收银台让用户输卡号 + 3DS（mock 模式下没有 cashierUrl，跳过）
            if let urlStr = paid.cashierUrl, let url = URL(string: urlStr), !urlStr.isEmpty {
                await openCashier(url: url)
            }
            // 4. 轮询订单状态，命中 PAID 后进入履约
            try await pollOrderStatus(orderNo: paid.orderNo)
            phase = .ready
        } catch {
            errorMsg = error.localizedDescription
            phase = .confirm
        }
    }

    /// 用 ASWebAuthenticationSession 打开通联国际收银台
    private func openCashier(url: URL) async {
        await MainActor.run { cashierLoading = true }
        WebAuthBridge.shared.open(url: url) { result in
            // 收银台关闭（用户取消或回调跳转）后无需处理
            // 真实状态由后端异步通知触发，前端轮询拉取
            Task { @MainActor in
                cashierLoading = false
                if case .failure = result {
                    errorMsg = "收银台已关闭，请等待支付结果"
                }
            }
        }
    }

    /// 每 2 秒拉取一次订单状态，命中 PAID 返回；最多 5 分钟
    private func pollOrderStatus(orderNo: String) async throws {
        let maxAttempts = 150
        for _ in 0..<maxAttempts {
            try await Task.sleep(nanoseconds: 2_000_000_000)
            do {
                let latest: OrderResponse = try await APIClient.shared.request(
                    "/order/\(orderNo)", method: "GET", token: authVM.token
                )
                order = latest
                if latest.status >= OrderStatus.paid {
                    return
                }
                if latest.status == OrderStatus.refunded {
                    throw APIError.business("支付失败或已退款")
                }
            } catch APIError.business(let msg) {
                throw APIError.business(msg)
            } catch {
                // 瞬时网络错误忽略，继续重试
                continue
            }
        }
        throw APIError.business("支付状态查询超时")
    }

    private func startDispense() async {
        guard let orderNo = order?.orderNo else { return }
        do {
            phase = .dispensing
            try await APIClient.shared.requestVoid("/dispense/start/\(orderNo)", token: authVM.token)
            // 模拟分酒机回调（真机由设备回传）
            try await Task.sleep(nanoseconds: 1_500_000_000)
            let body: [String: Any] = ["order_id": orderNo, "status": "SUCCESS", "actual_ml": cup.volumeMl]
            let data = try JSONSerialization.data(withJSONObject: body)
            try await APIClient.shared.requestVoid(
                "/dispense/callback", method: "POST",
                bodyData: data, token: nil
            )
            phase = .done
        } catch {
            errorMsg = error.localizedDescription
        }
    }
}

/// 订单状态码（与后端 OrderStatusEnum 对齐）
enum OrderStatus {
    static let pending = 1
    static let paying = 2
    static let paid = 3
    static let completed = 4
    static let refunded = 5
}

/// ASWebAuthenticationSession 的 SwiftUI 桥接，用于打开通联国际收银台
final class WebAuthBridge: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = WebAuthBridge()
    private var session: ASWebAuthenticationSession?

    func open(url: URL, completion: @escaping (Result<URL?, Error>) -> Void) {
        let scheme = "wineapp" // 与通联侧配置的回调 scheme 保持一致
        let session = ASWebAuthenticationSession(
            url: url,
            callbackURLScheme: scheme
        ) { callbackURL, error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(callbackURL))
            }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        session.start()
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // 返回当前 key window（iOS 15+ SceneDelegate 场景）
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if let window = scene.windows.first(where: { $0.isKeyWindow }) {
                return window
            }
        }
        // 退化为任意一个 window，再退化为空 ASPresentationAnchor
        if let scene = scenes.first, let window = scene.windows.first {
            return window
        }
        return ASPresentationAnchor()
    }
}
