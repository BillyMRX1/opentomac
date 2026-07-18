import Foundation
import UserNotifications

/// Presents mirrored phone notifications as native macOS banners and routes inline
/// replies back to the phone. Reply metadata (notification key and action index) rides
/// in the request userInfo so a reply reaches the correct notification instance.
@MainActor
final class NotificationBridge: NSObject, UNUserNotificationCenterDelegate {
    static let replyCategory = "opentomac.reply"
    private let center = UNUserNotificationCenter.current()
    private var onReply: ((String, Int, String) -> Void)?

    func start(onReply: @escaping (String, Int, String) -> Void) {
        self.onReply = onReply
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            NSLog("opentomac notifications: authorization granted=\(granted) error=\(error?.localizedDescription ?? "none")")
        }
        let replyAction = UNTextInputNotificationAction(
            identifier: "opentomac.reply.action",
            title: "Reply",
            options: [],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Reply"
        )
        let category = UNNotificationCategory(
            identifier: Self.replyCategory,
            actions: [replyAction],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([category])
    }

    func present(title: String, body: String, key: String, replyIndex: Int) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = ["key": key, "actionIndex": replyIndex]
        if replyIndex >= 0 {
            content.categoryIdentifier = Self.replyCategory
        }
        let request = UNNotificationRequest(identifier: key, content: content, trigger: nil)
        center.add(request) { error in
            if let error {
                NSLog("opentomac notifications: banner failed for \(key): \(error.localizedDescription)")
            } else {
                NSLog("opentomac notifications: banner accepted for \(key)")
            }
        }
    }

    /** Reports whether macOS currently allows this app to post notifications. */
    nonisolated func authorized(_ completion: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let ok = settings.authorizationStatus == .authorized
                || settings.authorizationStatus == .provisional
            completion(ok)
        }
    }

    // Show banners even while opentomac is the active app.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let textResponse = response as? UNTextInputNotificationResponse {
            let info = response.notification.request.content.userInfo
            let key = info["key"] as? String ?? ""
            let actionIndex = info["actionIndex"] as? Int ?? -1
            let text = textResponse.userText
            if !key.isEmpty && actionIndex >= 0 {
                Task { @MainActor in self.onReply?(key, actionIndex, text) }
            }
        }
        completionHandler()
    }
}
