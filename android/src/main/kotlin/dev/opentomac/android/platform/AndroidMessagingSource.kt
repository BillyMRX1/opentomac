package dev.opentomac.android.platform

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.opentomac.android.R
import dev.opentomac.shared.messaging.MessagingSource
import dev.opentomac.shared.protocol.CallLogEntry
import dev.opentomac.shared.protocol.SmsMessage
import dev.opentomac.shared.protocol.SmsThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class AndroidMessagingSource(
    context: Context,
    private val scope: CoroutineScope,
) : MessagingSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun threads(limit: Int): List<SmsThread>? = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_SMS)) return@withContext null
        try {
            queryThreads(limit)
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun thread(
        threadId: String,
        limit: Int,
    ): Pair<String, List<SmsMessage>>? = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_SMS)) return@withContext null
        try {
            queryThread(threadId, limit)
        } catch (_: SecurityException) {
            null
        }
    }

    override suspend fun send(
        id: String,
        address: String,
        body: String,
        onResult: suspend (Result<Unit>) -> Unit,
    ) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            onResult(Result.failure(SecurityException("SMS send permission is not granted")))
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            onResult(Result.failure(SecurityException("Notification permission is required to confirm SMS")))
            return
        }
        if (address.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Phone number is empty")))
            return
        }
        if (body.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Message is empty")))
            return
        }
        val smsManager = appContext.getSystemService(SmsManager::class.java)
        val parts = runCatching { smsManager.divideMessage(body).toList() }
            .getOrElse {
                onResult(Result.failure(it))
                return
            }
        if (parts.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Message is empty")))
            return
        }
        if (parts.size > MAX_SMS_PARTS) {
            onResult(
                Result.failure(
                    IllegalArgumentException("Message is too long (maximum $MAX_SMS_PARTS SMS parts)"),
                ),
            )
            return
        }

        val pending = PendingSms(
            id = id,
            address = address,
            body = body,
            parts = parts,
            notificationId = nextNotificationId.getAndIncrement(),
            scope = scope,
            onResult = onResult,
        )
        if (!register(pending)) {
            onResult(Result.failure(IllegalStateException("duplicate SMS operation")))
            return
        }
        if (!postConfirmation(appContext, pending)) {
            complete(
                appContext,
                id,
                PendingState.AWAITING_CONFIRMATION,
                Result.failure(IllegalStateException("Could not show SMS confirmation")),
            )
            return
        }
        scope.launch {
            delay(CONFIRMATION_EXPIRY_MS)
            expireConfirmation(appContext, id)
        }
    }

    override suspend fun callLog(limit: Int): List<CallLogEntry>? = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return@withContext null
        try {
            queryCallLog(limit)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun queryThreads(limit: Int): List<SmsThread> {
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
        )
        val items = LinkedHashMap<String, SmsThread>()
        val names = mutableMapOf<String, String>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val threadColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext() && items.size < limit) {
                val threadId = cursor.getLong(threadColumn).toString()
                if (threadId in items) continue
                val address = cursor.getString(addressColumn).orEmpty()
                items[threadId] = SmsThread(
                    threadId = threadId,
                    address = address,
                    contactName = names.getOrPut(address) { contactName(address) },
                    snippet = cursor.getString(bodyColumn).orEmpty(),
                    dateMs = cursor.getLong(dateColumn),
                    unread = cursor.getInt(readColumn) == 0,
                )
            }
        }
        return items.values.toList()
    }

    private fun queryThread(threadId: String, limit: Int): Pair<String, List<SmsMessage>> {
        var address = ""
        val newestFirst = mutableListOf<SmsMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
            ),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext() && newestFirst.size < limit) {
                val rowAddress = cursor.getString(addressColumn).orEmpty()
                if (address.isBlank() && rowAddress.isNotBlank()) address = rowAddress
                newestFirst += SmsMessage(
                    body = cursor.getString(bodyColumn).orEmpty(),
                    dateMs = cursor.getLong(dateColumn),
                    incoming = cursor.getInt(typeColumn) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                )
            }
        }
        return address to newestFirst.asReversed()
    }

    private fun queryCallLog(limit: Int): List<CallLogEntry> {
        val entries = mutableListOf<CallLogEntry>()
        val names = mutableMapOf<String, String>()
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && entries.size < limit) {
                val number = cursor.getString(numberColumn).orEmpty()
                val cachedName = cursor.getString(nameColumn).orEmpty()
                entries += CallLogEntry(
                    number = number,
                    contactName = cachedName.ifBlank {
                        names.getOrPut(number) { contactName(number) }
                    },
                    type = callType(cursor.getInt(typeColumn)),
                    dateMs = cursor.getLong(dateColumn),
                    durationSec = cursor.getLong(durationColumn)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt(),
                )
            }
        }
        return entries
    }

    private fun contactName(address: String): String {
        if (address.isBlank() || !hasPermission(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address),
            )
            resolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
            }.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "other"
    }

    companion object {
        private const val CHANNEL_ID = "opentomac_sms"
        private const val ACTION_CONFIRM = "dev.opentomac.android.SMS_CONFIRM"
        private const val ACTION_CANCEL = "dev.opentomac.android.SMS_CANCEL"
        private const val ACTION_SENT = "dev.opentomac.android.SMS_SENT"
        private const val EXTRA_ID = "sms_operation_id"
        private const val EXTRA_PART_INDEX = "sms_part_index"
        private const val MAX_SMS_PARTS = 10
        private const val PREVIEW_LENGTH = 120
        private const val CONFIRMATION_EXPIRY_MS = 16_000L
        private const val SENT_STATUS_EXPIRY_MS = 60_000L

        private val pendingLock = Any()
        private val pendingSends = mutableMapOf<String, PendingSms>()
        private val nextNotificationId = AtomicInteger(0x53000000)

        internal fun handleBroadcast(context: Context, intent: Intent, resultCode: Int) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            when (intent.action) {
                ACTION_CONFIRM -> confirm(context.applicationContext, id)
                ACTION_CANCEL -> complete(
                    context.applicationContext,
                    id,
                    PendingState.AWAITING_CONFIRMATION,
                    Result.failure(IllegalStateException("cancelled")),
                )
                ACTION_SENT -> recordPartResult(
                    context.applicationContext,
                    id,
                    intent.getIntExtra(EXTRA_PART_INDEX, -1),
                    resultCode,
                )
            }
        }

        private fun register(pending: PendingSms): Boolean = synchronized(pendingLock) {
            if (pending.id in pendingSends) {
                false
            } else {
                pendingSends[pending.id] = pending
                true
            }
        }

        private fun postConfirmation(context: Context, pending: PendingSms): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SMS requests from Mac",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val sendIntent = actionPendingIntent(context, ACTION_CONFIRM, pending.id, "confirm")
            val cancelIntent = actionPendingIntent(context, ACTION_CANCEL, pending.id, "cancel")
            val previewText = pending.body
                .replace(Regex("\\s+"), " ")
                .trim()
            val preview = truncateCodePoints(previewText, PREVIEW_LENGTH)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_clipboard)
                .setContentTitle("Send SMS from Mac?")
                .setContentText("To ${pending.address}: $preview")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("To: ${pending.address}\n$preview"),
                )
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setTimeoutAfter(CONFIRMATION_EXPIRY_MS)
                .addAction(0, "Send", sendIntent)
                .addAction(0, "Cancel", cancelIntent)
                .build()
            return runCatching { manager.notify(pending.notificationId, notification) }.isSuccess
        }

        private fun actionPendingIntent(
            context: Context,
            action: String,
            id: String,
            path: String,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SmsSendReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("opentomac://sms/$path/${Uri.encode(id)}")
                putExtra(EXTRA_ID, id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT,
        )

        private fun confirm(context: Context, id: String) {
            val pending = synchronized(pendingLock) {
                pendingSends[id]?.takeIf { it.state == PendingState.AWAITING_CONFIRMATION }?.also {
                    it.state = PendingState.SENDING
                }
            } ?: return
            context.getSystemService(NotificationManager::class.java).cancel(pending.notificationId)

            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                complete(
                    context,
                    id,
                    PendingState.SENDING,
                    Result.failure(SecurityException("SMS send permission is not granted")),
                )
                return
            }

            val smsManager = context.getSystemService(SmsManager::class.java)
            val sentIntents = pending.parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, SmsSendReceiver::class.java).apply {
                        action = ACTION_SENT
                        data = Uri.parse("opentomac://sms/sent/${Uri.encode(id)}/$index")
                        putExtra(EXTRA_ID, id)
                        putExtra(EXTRA_PART_INDEX, index)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_ONE_SHOT,
                )
            }
            runCatching {
                if (pending.parts.size == 1) {
                    smsManager.sendTextMessage(
                        pending.address,
                        null,
                        pending.parts.single(),
                        sentIntents.single(),
                        null,
                    )
                } else {
                    smsManager.sendMultipartTextMessage(
                        pending.address,
                        null,
                        ArrayList(pending.parts),
                        ArrayList(sentIntents),
                        null,
                    )
                }
            }.onFailure {
                complete(context, id, PendingState.SENDING, Result.failure(it))
            }

            pending.scope.launch {
                delay(SENT_STATUS_EXPIRY_MS)
                expireSentStatus(context, id)
            }
        }

        private fun recordPartResult(context: Context, id: String, partIndex: Int, resultCode: Int) {
            var completion: Result<Unit>? = null
            synchronized(pendingLock) {
                val pending = pendingSends[id]?.takeIf { it.state == PendingState.SENDING } ?: return
                if (partIndex !in pending.parts.indices || partIndex in pending.partResults) return
                pending.partResults[partIndex] = resultCode
                if (pending.partResults.size == pending.parts.size) {
                    val failure = pending.partResults.values.firstOrNull { it != Activity.RESULT_OK }
                    completion = if (failure == null) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException(sentFailureMessage(failure)))
                    }
                    pending.state = PendingState.COMPLETING
                }
            }
            completion?.let { complete(context, id, PendingState.COMPLETING, it) }
        }

        private fun expireConfirmation(context: Context, id: String) {
            complete(
                context,
                id,
                PendingState.AWAITING_CONFIRMATION,
                Result.failure(IllegalStateException("confirmation expired")),
            )
        }

        private fun expireSentStatus(context: Context, id: String) {
            complete(
                context,
                id,
                PendingState.SENDING,
                Result.failure(IllegalStateException("SMS status timed out")),
            )
        }

        private fun complete(
            context: Context,
            id: String,
            expectedState: PendingState,
            result: Result<Unit>,
        ) {
            val pending = synchronized(pendingLock) {
                pendingSends[id]?.takeIf { it.state == expectedState }?.also {
                    pendingSends.remove(id)
                }
            } ?: return
            context.getSystemService(NotificationManager::class.java).cancel(pending.notificationId)
            pending.scope.launch { pending.onResult(result) }
        }

        private fun truncateCodePoints(value: String, maxCodePoints: Int): String {
            val count = value.codePointCount(0, value.length)
            if (count <= maxCodePoints) return value
            val end = value.offsetByCodePoints(0, maxCodePoints)
            return "${value.substring(0, end)}…"
        }

        private fun sentFailureMessage(resultCode: Int): String = when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS send failed"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "SMS provider rejected the message"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile radio is off"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS sending limit exceeded"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Recipient is blocked by fixed dialing"
            else -> "SMS send failed (code $resultCode)"
        }
    }
}

private data class PendingSms(
    val id: String,
    val address: String,
    val body: String,
    val parts: List<String>,
    val notificationId: Int,
    val scope: CoroutineScope,
    val onResult: suspend (Result<Unit>) -> Unit,
    var state: PendingState = PendingState.AWAITING_CONFIRMATION,
    val partResults: MutableMap<Int, Int> = mutableMapOf(),
)

private enum class PendingState {
    AWAITING_CONFIRMATION,
    SENDING,
    COMPLETING,
}

class SmsSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidMessagingSource.handleBroadcast(context, intent, resultCode)
    }
}
