package com.sernheise.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sernheise.app.R

private const val TAG = "ExportProgressDialog"

/**
 * A non-cancelable, determinate export progress dialog styled to match the app (rounded
 * corners, dynamic color where available), independent of the app's overall base theme.
 *
 * One instance covers the whole export lifecycle: [show], repeated [updateProgress]
 * calls, then exactly one of [showSuccess] or [showError].
 *
 * Every public method guards its own body: this dialog is driven from background-thread
 * callbacks posted to the main thread, and an uncaught exception in any of them would
 * otherwise crash the whole app, not just fail the export.
 */
class ExportProgressDialog(activityContext: Context) {

    // Material You dynamic color if the device supports it (Android 12+); a transparent
    // no-op wrapper otherwise -- this is the real, official mechanism, not a fake one.
    private val dialogContext = DynamicColors.wrapContextIfAvailable(activityContext)

    private val view = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_export_progress, null)
    private val iconFrame: View = view.findViewById(R.id.exportIconFrame)
    private val titleText: TextView = view.findViewById(R.id.exportTitle)
    private val statusText: TextView = view.findViewById(R.id.exportStatus)
    private val progressBar: LinearProgressIndicator = view.findViewById(R.id.exportProgressBar)
    private val percentText: TextView = view.findViewById(R.id.exportPercent)
    private val etaText: TextView = view.findViewById(R.id.exportEta)
    private val cancelButton: TextView = view.findViewById(R.id.exportCancel)

    /**
     * Set by the caller to make the Cancel button appear and wire it up. Left null (button
     * stays gone) for exports with no cancellable long-running phase, e.g. image export.
     */
    var onCancelRequested: (() -> Unit)? = null
        set(value) {
            field = value
            cancelButton.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    // Uses a MaterialComponents (M2) dialog overlay -- matches the app's actual base theme.
    // See the comment on ThemeOverlay.Sernheise.ExportDialog in themes.xml for why this
    // must not be a Material3 overlay.
    private val dialog = MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_Sernheise_ExportDialog)
        .setView(view)
        .setCancelable(false)
        .create()

    private var startTimeMillis = 0L
    private var lastDisplayedPercent = 0

    fun show() {
        try {
            startTimeMillis = System.currentTimeMillis()
            dialog.setCanceledOnTouchOutside(false)
            cancelButton.setOnClickListener {
                // Disable immediately so a double-tap can't fire the handler twice while the
                // background thread is still unwinding.
                cancelButton.isEnabled = false
                onCancelRequested?.invoke()
            }
            dialog.show()
            dimAndBlurBehind()
        } catch (t: Throwable) {
            Log.e(TAG, "show() failed", t)
        }
    }

    /** Called right after the user taps Cancel, before the export has actually torn itself down. */
    fun showCancelling() {
        try {
            if (!dialog.isShowing) return
            statusText.setText(R.string.export_cancelling)
            etaText.visibility = View.INVISIBLE
        } catch (t: Throwable) {
            Log.e(TAG, "showCancelling() failed", t)
        }
    }

    /** Closes the dialog with no success/error flourish, once a cancel has fully taken effect. */
    fun dismissImmediately() {
        try {
            dialog.dismiss()
        } catch (t: Throwable) {
            Log.e(TAG, "dismissImmediately() failed", t)
        }
    }

    /** Stronger dim than the platform default, plus real backdrop blur on Android 12+. */
    private fun dimAndBlurBehind() {
        try {
            val window = dialog.window ?: return
            window.setDimAmount(0.72f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = 48 }
            }
        } catch (t: Throwable) {
            // Purely cosmetic -- never worth failing the dialog (or the export) over.
            Log.w(TAG, "dimAndBlurBehind() failed, continuing without it", t)
        }
    }

    /** [percent] is 0..100. Estimates remaining time from elapsed wall-clock time vs progress so far. */
    fun updateProgress(percent: Int, statusRes: Int) {
        try {
            if (!dialog.isShowing) return
            val clamped = percent.coerceIn(0, 100)
            progressBar.setProgressCompat(clamped, true) // Material's own smooth fill animation
            animatePercentText(lastDisplayedPercent, clamped)
            lastDisplayedPercent = clamped
            statusText.setText(statusRes)

            val elapsedMillis = System.currentTimeMillis() - startTimeMillis
            if (clamped in 5..98 && elapsedMillis > 500) {
                val totalEstimateMillis = elapsedMillis * 100 / clamped
                val remainingSeconds = ((totalEstimateMillis - elapsedMillis) / 1000).coerceAtLeast(0)
                etaText.text = view.context.getString(R.string.export_eta_seconds, remainingSeconds)
                etaText.visibility = View.VISIBLE
            } else {
                etaText.visibility = View.INVISIBLE
            }
        } catch (t: Throwable) {
            Log.e(TAG, "updateProgress() failed", t)
        }
    }

    private fun animatePercentText(from: Int, to: Int) {
        if (from == to) {
            percentText.text = "$to%"
            return
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = 220
            addUpdateListener { percentText.text = "${it.animatedValue}%" }
            start()
        }
    }

    fun showSuccess(onDismissed: () -> Unit) {
        try {
            if (!dialog.isShowing) { onDismissed(); return }
            titleText.setText(R.string.export_done_title)
            statusText.setText(R.string.export_done)
            etaText.visibility = View.INVISIBLE

            progressBar.animate().alpha(0f).setDuration(120).withEndAction {
                try {
                    progressBar.visibility = View.INVISIBLE
                    percentText.animate().alpha(0f).setDuration(120).start()

                    iconFrame.visibility = View.VISIBLE
                    iconFrame.scaleX = 0f
                    iconFrame.scaleY = 0f
                    iconFrame.alpha = 0f
                    iconFrame.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(320)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                } catch (t: Throwable) {
                    Log.w(TAG, "Success animation failed, dialog will still dismiss on schedule", t)
                }
            }.start()

            view.postDelayed({
                try {
                    dialog.setOnDismissListener { onDismissed() }
                    dialog.dismiss()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to dismiss success dialog", t)
                    onDismissed()
                }
            }, 900)
        } catch (t: Throwable) {
            Log.e(TAG, "showSuccess() failed", t)
            onDismissed()
        }
    }

    fun showError(onDismissed: () -> Unit) {
        try {
            dialog.dismiss()
            MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_Sernheise_ExportDialog)
                .setTitle(R.string.export_failed_title)
                .setMessage(R.string.export_failed_message)
                .setPositiveButton(R.string.dialog_ok) { d, _ -> d.dismiss() }
                .setOnDismissListener { onDismissed() }
                .setCancelable(true)
                .show()
        } catch (t: Throwable) {
            Log.e(TAG, "showError() failed", t)
            onDismissed()
        }
    }
}
