import Foundation
import UserNotifications

/// Presents mirrored phone notifications as native macOS banners and routes inline
/// replies back to the phone. Reply metadata (notification key and action index) rides
/// in the request userInfo so a reply reaches the correct notification instance.
@MainActor
final class NotificationBridge: NSObject, UNUserNotificationCenterDelegate {
    static let replyCategory = "opentomac.reply"
    static let dismissCategory = "opentomac.dismiss"
    static let replyDismissCategory = "opentomac.reply-dismiss"
    static let screenshotCategory = "opentomac.screenshot"
    private nonisolated static let screenshotSendAction = "opentomac.screenshot.send"
    private let center = UNUserNotificationCenter.current()
    private var onReply: ((String, Int, String) -> Void)?
    private var onScreenshotSend: ((String) -> Void)?
    private var onDismiss: ((String) -> Void)?

    func start(
        onReply: @escaping (String, Int, String) -> Void,
        onScreenshotSend: @escaping (String) -> Void,
        onDismiss: @escaping (String) -> Void
    ) {
        self.onReply = onReply
        self.onScreenshotSend = onScreenshotSend
        self.onDismiss = onDismiss
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
        let replyCategory = UNNotificationCategory(
            identifier: Self.replyCategory,
            actions: [replyAction],
            intentIdentifiers: [],
            options: []
        )
        let dismissCategory = UNNotificationCategory(
            identifier: Self.dismissCategory,
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let replyDismissCategory = UNNotificationCategory(
            identifier: Self.replyDismissCategory,
            actions: [replyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let sendAction = UNNotificationAction(
            identifier: Self.screenshotSendAction,
            title: "Send to Mac",
            options: []
        )
        let screenshotCategory = UNNotificationCategory(
            identifier: Self.screenshotCategory,
            actions: [sendAction],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([replyCategory, dismissCategory, replyDismissCategory, screenshotCategory])
    }

    /** Offers a fresh phone screenshot; the Send action fetches the original. */
    func presentScreenshot(name: String, mediaId: String) {
        let content = UNMutableNotificationContent()
        content.title = "Screenshot on phone"
        content.body = "\(name) — use Send to Mac to fetch it"
        content.categoryIdentifier = Self.screenshotCategory
        content.userInfo = ["mediaId": mediaId]
        let request = UNNotificationRequest(
            identifier: "screenshot-\(mediaId)",
            content: content,
            trigger: nil
        )
        center.add(request) { error in
            if let error {
                NSLog("opentomac notifications: screenshot offer failed: \(error.localizedDescription)")
            }
        }
    }

    func present(title: String, body: String, key: String, replyIndex: Int, dismissible: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = ["key": key, "actionIndex": replyIndex]
        if dismissible {
            content.categoryIdentifier = replyIndex >= 0 ? Self.replyDismissCategory : Self.dismissCategory
        } else if replyIndex >= 0 {
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

    func withdraw(key: String) {
        center.removePendingNotificationRequests(withIdentifiers: [key])
        center.removeDeliveredNotifications(withIdentifiers: [key])
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
        if response.actionIdentifier == Self.screenshotSendAction {
            let mediaId = response.notification.request.content.userInfo["mediaId"] as? String ?? ""
            if !mediaId.isEmpty {
                Task { @MainActor in self.onScreenshotSend?(mediaId) }
            }
            completionHandler()
            return
        }
        if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            let key = response.notification.request.content.userInfo["key"] as? String ?? ""
            if !key.isEmpty { Task { @MainActor in self.onDismiss?(key) } }
            completionHandler()
            return
        }
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
