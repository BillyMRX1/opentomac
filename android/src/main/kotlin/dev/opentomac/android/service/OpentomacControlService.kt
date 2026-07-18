package dev.opentomac.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.opentomac.shared.protocol.InputKey
import dev.opentomac.shared.protocol.InputSwipe
import dev.opentomac.shared.protocol.InputTap
import dev.opentomac.shared.protocol.InputText
import dev.opentomac.shared.protocol.Message

/** Accessibility-backed receiver for authenticated Mac input while mirroring is active. */
class OpentomacControlService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var inputState = MirroringState(generation = 0, active = false)
    @Volatile
    private var attached = false
    private var gestureInFlight = false
    private var pendingGesture: QueuedGesture? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach(this)
        super.onDestroy()
    }

    private fun enqueue(message: Message, generation: Long) {
        mainHandler.post {
            if (!accepts(generation)) return@post
            when (message) {
                is InputTap -> queueTap(message, generation)
                is InputSwipe -> queueSwipe(message, generation)
                is InputKey -> key(message, generation)
                is InputText -> text(message, generation)
                else -> Unit
            }
        }
    }

    private fun queueTap(input: InputTap, generation: Long) {
        val (x, y) = displayPoint(input.x, input.y) ?: return
        val path = Path().apply { moveTo(x, y) }
        queueGesture(
            generation,
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
        )
    }

    private fun queueSwipe(input: InputSwipe, generation: Long) {
        val (x1, y1) = displayPoint(input.x1, input.y1) ?: return
        val (x2, y2) = displayPoint(input.x2, input.y2) ?: return
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        queueGesture(
            generation,
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        input.durationMs.coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS).toLong(),
                    ),
                )
                .build(),
        )
    }

    private fun queueGesture(generation: Long, gesture: GestureDescription) {
        if (!accepts(generation)) return
        val queued = QueuedGesture(generation, gesture)
        if (gestureInFlight) {
            // One latest-wins pending slot bounds sustained 60 ms Mac scroll input
            // while the current Android gesture completes (typically about 100 ms).
            pendingGesture = queued
            return
        }
        dispatchGesture(queued)
    }

    private fun dispatchGesture(queued: QueuedGesture) {
        if (!accepts(queued.generation)) return
        gestureInFlight = true
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                finishGesture(queued.generation)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                finishGesture(queued.generation)
            }
        }
        if (!dispatchGesture(queued.gesture, callback, mainHandler)) {
            finishGesture(queued.generation)
        }
    }

    private fun finishGesture(generation: Long) {
        if (!accepts(generation)) return
        gestureInFlight = false
        val next = pendingGesture
        pendingGesture = null
        if (next != null) dispatchGesture(next)
    }

    private fun key(input: InputKey, generation: Long) {
        val action = when (input.action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        if (!accepts(generation)) return
        performGlobalAction(action)
    }

    private fun text(input: InputText, generation: Long) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: windows.firstNotNullOfOrNull { window ->
                window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }
            ?: return
        if (!focused.isEditable) return

        val current = focused.text?.toString().orEmpty()
        val rawStart = focused.textSelectionStart
        val rawEnd = focused.textSelectionEnd
        val selection = normalizedSelection(current, rawStart, rawEnd)
        val editStart: Int
        val editEnd: Int
        if (selection.first != selection.last) {
            editStart = selection.first
            editEnd = selection.last
        } else {
            val caret = selection.first
            val availableCodePoints = current.codePointCount(0, caret)
            val deleteCodePoints = input.deleteCount.coerceIn(0, availableCodePoints)
            editStart = current.offsetByCodePoints(caret, -deleteCodePoints)
            editEnd = caret
        }
        if (editStart == editEnd && input.text.isEmpty()) return

        val updated = buildString(current.length - (editEnd - editStart) + input.text.length) {
            append(current, 0, editStart)
            append(input.text)
            append(current, editEnd, current.length)
        }
        val newCaret = editStart + input.text.length
        if (!accepts(generation)) return
        val textSet = focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated,
                )
            },
        )
        if (!textSet) return
        if (!accepts(generation)) return
        val selectionSet = focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCaret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCaret)
            },
        )
        if (!selectionSet) return
    }

    private fun normalizedSelection(text: String, rawStart: Int, rawEnd: Int): IntRange {
        if (rawStart !in 0..text.length || rawEnd !in 0..text.length) {
            return text.length..text.length
        }
        if (rawStart == rawEnd) {
            val caret = codePointBoundaryAfter(text, rawStart)
            return caret..caret
        }
        val start = codePointBoundaryBefore(text, minOf(rawStart, rawEnd))
        val end = codePointBoundaryAfter(text, maxOf(rawStart, rawEnd))
        return start..end
    }

    private fun codePointBoundaryBefore(text: String, offset: Int): Int =
        if (
            offset > 0 &&
            offset < text.length &&
            text[offset - 1].isHighSurrogate() &&
            text[offset].isLowSurrogate()
        ) {
            offset - 1
        } else {
            offset
        }

    private fun codePointBoundaryAfter(text: String, offset: Int): Int =
        if (
            offset > 0 &&
            offset < text.length &&
            text[offset - 1].isHighSurrogate() &&
            text[offset].isLowSurrogate()
        ) {
            offset + 1
        } else {
            offset
        }

    private fun updateMirroringState(state: MirroringState, force: Boolean = false) {
        if (!attached && !force) return
        val previous = inputState
        if (!force && state.generation < previous.generation) return
        inputState = state
        if (force || state.generation != previous.generation || !state.active) {
            mainHandler.removeCallbacksAndMessages(null)
        }
        mainHandler.postAtFrontOfQueue {
            if (inputState == state && attached) {
                pendingGesture = null
                gestureInFlight = false
            }
        }
    }

    private fun invalidate() {
        attached = false
        inputState = inputState.copy(active = false)
        mainHandler.removeCallbacksAndMessages(null)
        pendingGesture = null
        gestureInFlight = false
    }

    private fun accepts(generation: Long): Boolean =
        attached && inputState.active && inputState.generation == generation

    private fun displayPoint(normalizedX: Float, normalizedY: Float): Pair<Float, Float>? {
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return null
        val size = displaySize() ?: return null
        val maxX = (size.x - 1).coerceAtLeast(0)
        val maxY = (size.y - 1).coerceAtLeast(0)
        return normalizedX.coerceIn(0f, 1f) * maxX to
            normalizedY.coerceIn(0f, 1f) * maxY
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Point? {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return Point(bounds.width(), bounds.height())
            }
        }
        return Point().also(windowManager.defaultDisplay::getRealSize)
            .takeIf { it.x > 0 && it.y > 0 }
    }

    companion object Bridge {
        private const val TAP_DURATION_MS = 60L
        private const val MIN_SWIPE_DURATION_MS = 50
        private const val MAX_SWIPE_DURATION_MS = 2_000

        private val bridgeLock = Any()
        private var activeService: OpentomacControlService? = null
        private var mirroringState = MirroringState(generation = 0, active = false)

        /** Returns false when Android control access is not currently enabled/bound. */
        fun dispatch(message: Message, generation: Long): Boolean {
            val service = synchronized(bridgeLock) { activeService } ?: return false
            service.enqueue(message, generation)
            return true
        }

        fun updateMirroringState(generation: Long, active: Boolean) {
            synchronized(bridgeLock) {
                if (generation < mirroringState.generation) return
                val state = MirroringState(generation, active)
                mirroringState = state
                activeService?.updateMirroringState(state)
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == OpentomacControlService::class.java.name
                }
        }

        private fun attach(service: OpentomacControlService) {
            synchronized(bridgeLock) {
                activeService = service
                service.attached = true
                service.updateMirroringState(mirroringState, force = true)
            }
        }

        private fun detach(service: OpentomacControlService) {
            synchronized(bridgeLock) {
                service.invalidate()
                if (activeService === service) activeService = null
            }
        }
    }

    private data class MirroringState(
        val generation: Long,
        val active: Boolean,
    )

    private data class QueuedGesture(
        val generation: Long,
        val gesture: GestureDescription,
    )
}
