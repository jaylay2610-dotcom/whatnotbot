package com.whatnotbot.giveaway

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GiveawayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GiveawayBot"
        private const val WHATNOT_PACKAGE = "com.whatnot.whatnot"

        // Timing constants
        private const val SCAN_INTERVAL_MS = 1000L  // Check every second
        private const val TAP_DELAY_MS = 500L       // Delay between taps
        private const val COOLDOWN_MS = 3000L       // Cooldown after entering

        // How long to keep trying to tap the enter button before giving up and
        // going back to watching. Without this the service used to sit in
        // ENTERING forever whenever the enter tap failed, and the only way out
        // was for the user to pause and restart the bot.
        private const val ENTERING_TIMEOUT_MS = 6000L

        // The giveaway badge sits in the upper part of the stream. Expressed as
        // a fraction of screen height so it holds on any screen size/density —
        // the old hard-coded pixel cutoffs only worked on small, low-density
        // screens and silently rejected the real controls everywhere else.
        private const val BADGE_MAX_TOP_FRACTION = 0.45

        // Whatnot's actual button labels, most specific first. A bare "Enter"
        // is only a last resort.
        private val ENTER_LABELS = listOf("Enter Giveaway", "Follow and Enter", "Enter")

        // Text that means we are already in the giveaway. Both the straight and
        // typographic apostrophe appear in the wild.
        private val ENTERED_LABELS = listOf(
            "You're in the Giveaway", "You’re in the Giveaway",
            "You're in", "You’re in"
        )

        // Labels that are status text, never something to tap.
        private val NOT_A_BUTTON_LABELS = ENTERED_LABELS + listOf("Entered")

        // Singleton instance for overlay to communicate
        var instance: GiveawayAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastActionTime = 0L
    private var currentState = BotState.IDLE
    private var enteringSinceMs = 0L

    enum class BotState {
        IDLE,           // Not doing anything
        WATCHING,       // Watching for giveaway button
        ENTERING,       // In process of entering
        ENTERED,        // Successfully entered, waiting
        COOLDOWN        // Just entered, waiting before next action
    }

    /** Guaranteed heartbeat: always re-posts itself while scanning. */
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (MainActivity.isBotActive && isWhatnotApp()) {
                scanForGiveaway()
            }
            if (isScanning) {
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * One-off extra scan in response to a screen change. Kept separate from the
     * heartbeat so debouncing it can never delay or cancel the heartbeat.
     */
    private val nudgeRunnable = Runnable {
        if (isScanning && MainActivity.isBotActive && isWhatnotApp()) {
            scanForGiveaway()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopScanning()
        Log.d(TAG, "Accessibility Service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        startScanning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We handle scanning in our runnable, but we can also respond to events
        if (event == null) return

        // Only process if bot is active and we're in Whatnot
        if (!MainActivity.isBotActive) return
        if (event.packageName != WHATNOT_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Screen changed, so look sooner than the next heartbeat.
                //
                // This debounces its OWN runnable and deliberately leaves the
                // heartbeat alone. It used to cancel and re-post the heartbeat
                // itself: with notificationTimeout=100ms, a live stream emits
                // content-changed events faster than the 200ms delay, so every
                // event pushed the scan further out and it could never run.
                if (currentState == BotState.WATCHING) {
                    handler.removeCallbacks(nudgeRunnable)
                    handler.postDelayed(nudgeRunnable, 200)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    fun startScanning() {
        if (!isScanning) {
            isScanning = true
            currentState = BotState.WATCHING
            // Clear the per-run timers so a fresh start never begins rate-limited
            // or holding a stale ENTERING deadline.
            enteringSinceMs = 0L
            lastActionTime = 0L
            handler.post(scanRunnable)
            Log.d(TAG, "Started scanning for giveaways")
        }
    }

    fun stopScanning() {
        isScanning = false
        currentState = BotState.IDLE
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(nudgeRunnable)
        Log.d(TAG, "Stopped scanning")
    }

    private fun isWhatnotApp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.packageName == WHATNOT_PACKAGE
    }

    private fun scanForGiveaway() {
        if (!MainActivity.isBotActive) return

        val rootNode = rootInActiveWindow ?: return

        try {
            when (currentState) {
                BotState.WATCHING, BotState.IDLE -> {
                    // Order matters. Check "already entered" FIRST: the badge
                    // stays on screen after entering, and tapping it again was
                    // what made the bot re-open and mis-tap the open card.
                    if (isAlreadyEntered(rootNode)) {
                        currentState = BotState.ENTERED
                        Log.d(TAG, "Already in giveaway, watching for next one")
                    } else if (findEnterGiveawayButton(rootNode)) {
                        // Card is already expanded — go straight to tapping enter
                        // rather than tapping the badge and collapsing it again.
                        beginEntering("card already open")
                    } else if (findAndTapGiveawayButton(rootNode)) {
                        beginEntering("tapped badge")
                    }
                }
                BotState.ENTERING -> {
                    // Tap the enter button
                    if (tapEnterButton(rootNode)) {
                        currentState = BotState.COOLDOWN
                        incrementGiveawayCount()
                        handler.postDelayed({
                            currentState = BotState.WATCHING
                        }, COOLDOWN_MS)
                    } else if (isAlreadyEntered(rootNode)) {
                        // The entry landed even though we did not see the tap
                        // succeed (or the card entered us directly).
                        Log.d(TAG, "Entry confirmed while entering")
                        currentState = BotState.ENTERED
                    } else if (System.currentTimeMillis() - enteringSinceMs > ENTERING_TIMEOUT_MS) {
                        // Never get stuck here: this is the state the bot used to
                        // die in, forcing a manual pause/restart.
                        Log.w(TAG, "Could not tap an enter button in time, back to watching")
                        currentState = BotState.WATCHING
                    }
                }
                BotState.ENTERED -> {
                    // Check if giveaway ended or new one started
                    if (!isAlreadyEntered(rootNode)) {
                        currentState = BotState.WATCHING
                    }
                }
                BotState.COOLDOWN -> {
                    // Wait for cooldown
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun beginEntering(reason: String) {
        currentState = BotState.ENTERING
        enteringSinceMs = System.currentTimeMillis()
        Log.d(TAG, "Entering giveaway ($reason)")
    }

    private fun findAndTapGiveawayButton(rootNode: AccessibilityNodeInfo): Boolean {
        // Look for the collapsed "Giveaway" badge, which typically shows
        // "Giveaway" plus an entries count.
        val giveawayNodes = mutableListOf<AccessibilityNodeInfo>()

        // findNodesByText already ignores case, so one pass is enough — the old
        // second "giveaway" pass just added every node to the list twice.
        findNodesByText(rootNode, "Giveaway", giveawayNodes)

        val screenHeight = resources.displayMetrics.heightPixels

        for (node in giveawayNodes) {
            val text = nodeText(node)

            // Skip the expanded card's own controls and status text, so we tap
            // the badge itself rather than something inside an open card.
            if (ENTER_LABELS.any { text.contains(it, ignoreCase = true) }) continue
            if (NOT_A_BUTTON_LABELS.any { text.contains(it, ignoreCase = true) }) continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue

            // Keep to the upper area so chat messages mentioning a giveaway are
            // not mistaken for the badge, but use a fraction of screen height
            // instead of a fixed pixel count.
            if (bounds.top > screenHeight * BADGE_MAX_TOP_FRACTION) continue

            if (clickNode(node)) {
                Log.d(TAG, "Tapped giveaway badge: \"$text\" at $bounds")
                return true
            }
        }

        return false
    }

    private fun findEnterGiveawayButton(rootNode: AccessibilityNodeInfo): Boolean {
        // Is an expanded card with an enter button on screen?
        val enterNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Enter Giveaway", enterNodes)
        findNodesByText(rootNode, "Follow and Enter", enterNodes)

        return enterNodes.isNotEmpty()
    }

    private fun tapEnterButton(rootNode: AccessibilityNodeInfo): Boolean {
        // Find and tap "Enter Giveaway" / "Follow and Enter" / "Enter".
        for (buttonText in ENTER_LABELS) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, buttonText, nodes)

            for (node in nodes) {
                val text = nodeText(node)

                // "Entered" contains "enter", and status text is not tappable.
                if (NOT_A_BUTTON_LABELS.any { text.contains(it, ignoreCase = true) }) continue

                // No position filter here: the old `bounds.top < 600` check
                // rejected the real button on ordinary phone screens, which left
                // the service stuck in ENTERING with nothing to tap.
                if (clickNode(node)) {
                    Log.d(TAG, "Tapped enter button: \"$buttonText\"")
                    return true
                }
            }
        }

        return false
    }

    private fun isAlreadyEntered(rootNode: AccessibilityNodeInfo): Boolean {
        val enteredNodes = mutableListOf<AccessibilityNodeInfo>()
        for (label in ENTERED_LABELS) {
            findNodesByText(rootNode, label, enteredNodes)
            if (enteredNodes.isNotEmpty()) return true
        }
        return false
    }

    /**
     * Click a node the reliable way: ask the nearest clickable ancestor to
     * perform ACTION_CLICK. Only fall back to a coordinate gesture when nothing
     * in the chain is clickable, because tapping a container's centre can land
     * on whatever happens to sit in the middle of it.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        var depth = 0
        while (candidate != null && depth < 8) {
            if (candidate.isClickable && candidate.isEnabled) {
                if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastActionTime = System.currentTimeMillis()
                    return true
                }
            }
            candidate = candidate.parent
            depth++
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return performTap(bounds.centerX(), bounds.centerY())
    }

    /** Visible text of a node, including its content description. */
    private fun nodeText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun findNodesByText(
        node: AccessibilityNodeInfo,
        text: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        // Check current node
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        if (nodeText.contains(text, ignoreCase = true) ||
            contentDesc.contains(text, ignoreCase = true)) {
            results.add(node)
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByText(child, text, results)
        }
    }

    private fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastActionTime < TAP_DELAY_MS) {
            return false // Too soon after last tap
        }

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap cancelled")
            }
        }, null)

        if (result) {
            lastActionTime = now
        }

        return result
    }

    private fun incrementGiveawayCount() {
        MainActivity.giveawaysEntered++
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(MainActivity.KEY_GIVEAWAY_COUNT, MainActivity.giveawaysEntered).apply()
        Log.d(TAG, "Giveaway entered! Total: ${MainActivity.giveawaysEntered}")

        // Notify overlay to update
        OverlayService.instance?.updateStatus("Entered! Total: ${MainActivity.giveawaysEntered}")
    }

    fun getCurrentState(): BotState = currentState
}
