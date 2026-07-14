@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.notifications

import dev.opentomac.shared.protocol.FilterUpdate
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.NotifAction
import dev.opentomac.shared.protocol.NotificationAction
import dev.opentomac.shared.protocol.NotificationDismissed
import dev.opentomac.shared.protocol.NotificationPosted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationMirrorTest {

    @Test
    fun agentFiltersPostedContentBeforeItLeavesTheDevice() = runTest {
        val source = FakeNotificationSource()
        val sent = mutableListOf<Message>()
        val agent = NotificationAgent(source, sent::add, ownPackageId = "dev.opentomac")
        agent.start(backgroundScope)
        agent.onMessage(FilterUpdate(listOf("com.denied"), paused = false))

        source.emit(NotificationEvent.Posted(posted("denied", "com.denied")))
        source.emit(NotificationEvent.Posted(posted("self", "dev.opentomac")))
        source.emit(NotificationEvent.Posted(posted("allowed", "com.allowed")))
        runCurrent()

        assertEquals(listOf<Message>(posted("allowed", "com.allowed")), sent)
    }

    @Test
    fun pausedPolicyDropsPostsUntilItIsReplaced() = runTest {
        val source = FakeNotificationSource()
        val sent = mutableListOf<Message>()
        val agent = NotificationAgent(source, sent::add, ownPackageId = "dev.opentomac")
        agent.start(backgroundScope)

        agent.onMessage(FilterUpdate(paused = true))
        source.emit(NotificationEvent.Posted(posted("paused", "com.chat")))
        runCurrent()
        agent.onMessage(FilterUpdate(paused = false))
        source.emit(NotificationEvent.Posted(posted("resumed", "com.chat")))
        runCurrent()

        assertEquals(listOf<Message>(posted("resumed", "com.chat")), sent)
    }

    @Test
    fun dismissalsAreForwardedUnlessTheirKnownPackageIsDeniedOrSelf() = runTest {
        val source = FakeNotificationSource()
        val sent = mutableListOf<Message>()
        val agent = NotificationAgent(source, sent::add, ownPackageId = "dev.opentomac")
        agent.start(backgroundScope)
        agent.onMessage(FilterUpdate(listOf("com.denied"), paused = true))

        source.emit(NotificationEvent.Posted(posted("denied", "com.denied")))
        source.emit(NotificationEvent.Posted(posted("self", "dev.opentomac")))
        source.emit(NotificationEvent.Posted(posted("allowed", "com.allowed")))
        source.emit(NotificationEvent.Dismissed("denied"))
        source.emit(NotificationEvent.Dismissed("self"))
        source.emit(NotificationEvent.Dismissed("allowed"))
        source.emit(NotificationEvent.Dismissed("unknown"))
        runCurrent()

        assertEquals(
            listOf<Message>(NotificationDismissed("allowed"), NotificationDismissed("unknown")),
            sent,
        )
    }

    @Test
    fun agentDelegatesActionFieldsExactlyToTheSource() = runTest {
        val source = FakeNotificationSource()
        val agent = NotificationAgent(source, {}, ownPackageId = "dev.opentomac")

        agent.onMessage(NotificationAction("notification-key", 7, "reply text"))

        assertEquals(listOf(ActionCall("notification-key", 7, "reply text")), source.actions)
    }

    @Test
    fun companionPresentsWithdrawsAndSendsControlMessages() = runTest {
        val presenter = FakeNotificationPresenter()
        val sent = mutableListOf<Message>()
        val companion = NotificationCompanion(presenter, sent::add)
        val payload = posted("key", "com.chat")

        companion.onMessage(payload)
        companion.onMessage(NotificationDismissed("key"))
        companion.sendAction("key", 2, "yes")
        companion.updateFilter(FilterPolicy(setOf("com.noisy"), paused = true))

        assertEquals(listOf(payload), presenter.presented)
        assertEquals(listOf("key"), presenter.withdrawn)
        assertEquals(NotificationAction("key", 2, "yes"), sent[0])
        assertEquals(FilterUpdate(listOf("com.noisy"), paused = true), sent[1])
    }

    @Test
    fun actionRoutingUsesNotificationKeyNotPackageId() = runTest {
        val source = FakeNotificationSource()
        val presenter = FakeNotificationPresenter()
        lateinit var agent: NotificationAgent
        lateinit var companion: NotificationCompanion
        agent = NotificationAgent(source, { companion.onMessage(it) }, ownPackageId = "dev.opentomac")
        companion = NotificationCompanion(presenter, { agent.onMessage(it) })
        agent.start(backgroundScope)

        source.emit(NotificationEvent.Posted(posted("whatsapp-A", "com.whatsapp")))
        source.emit(NotificationEvent.Posted(posted("whatsapp-B", "com.whatsapp")))
        runCurrent()
        companion.sendAction("whatsapp-B", actionIndex = 1, remoteInputText = "On my way")

        assertEquals(listOf("whatsapp-A", "whatsapp-B"), presenter.presented.map { it.key })
        assertEquals(listOf(ActionCall("whatsapp-B", 1, "On my way")), source.actions)
        assertIs<NotifAction>(presenter.presented.last().actions.single())
        assertTrue(source.actions.none { it.key == "whatsapp-A" })
    }
}

private data class ActionCall(
    val key: String,
    val actionIndex: Int,
    val remoteInputText: String?,
)

private class FakeNotificationSource : NotificationSource {
    private val eventFlow = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 16)
    val actions = mutableListOf<ActionCall>()

    override fun events(): Flow<NotificationEvent> = eventFlow

    override suspend fun performAction(key: String, actionIndex: Int, remoteInputText: String?) {
        actions += ActionCall(key, actionIndex, remoteInputText)
    }

    suspend fun emit(event: NotificationEvent) {
        eventFlow.emit(event)
    }
}

private class FakeNotificationPresenter : NotificationPresenter {
    val presented = mutableListOf<NotificationPosted>()
    val withdrawn = mutableListOf<String>()

    override suspend fun present(posted: NotificationPosted) {
        presented += posted
    }

    override suspend fun withdraw(key: String) {
        withdrawn += key
    }
}

private fun posted(key: String, packageId: String): NotificationPosted = NotificationPosted(
    key = key,
    packageId = packageId,
    appName = packageId.substringAfterLast('.'),
    title = "Title for $key",
    body = "Body for $key",
    postedAt = 123L,
    actions = listOf(NotifAction(index = 1, title = "Reply", isRemoteInput = true)),
)
