import Foundation
import AppKit

// M7208W eSCL 守护进程 —— 开机自启的系统级扫描服务
// (图像捕捉/预览 7x24 可见扫描仪, 无需打开主App)

// 跟随主App的IP设置(读主App的偏好域), 变化自动热更新
let appDefaults = UserDefaults(suiteName: "com.zml.m7208wstudio") ?? .standard
var ip = appDefaults.string(forKey: "printerIP") ?? "192.168.1.100"
ESCLProxy.shared.start(ip: ip)
print("M7208W eSCL daemon: 服务已启动 :8090 (打印机 \(ip))")

// 常驻: 定期确认打印机在线, IP变了自动跟随
let timer = Timer(timeInterval: 30, repeats: true) { _ in
    let saved = UserDefaults(suiteName: "com.zml.m7208wstudio")?.string(forKey: "printerIP") ?? ip
    if saved != ESCLProxy.shared.printerIP {
        ESCLProxy.shared.printerIP = saved
        ip = saved
        print("daemon: 打印机IP已跟随 → \(saved)")
    }
}
RunLoop.current.add(timer, forMode: .default)
RunLoop.current.run()
