package com.github.kr328.clash.design.preference

import android.view.View
import androidx.annotation.StringRes
import com.github.kr328.clash.design.databinding.PreferenceProgressBinding
import com.github.kr328.clash.design.util.layoutInflater

/**
 * A read-only row that shows how far a long running task has come.
 *
 * The bar is indeterminate unless a total is known, because a long operation
 * that cannot report a denominator still has to look alive rather than pinned
 * at zero.
 */
interface ProgressPreference : Preference, CardPreference {
    var title: CharSequence
    var summary: CharSequence?

    /** Sets a determinate bar, clamped into the total. */
    fun setProgress(current: Int, total: Int)

    /** Switches to a bar that only spins. */
    fun setIndeterminate()

    /**
     * Stops the bar and leaves a line of text behind.
     *
     * A run that fails in the first hundred milliseconds would otherwise make
     * the bar flash and vanish, which reads as a dead button rather than as an
     * error worth reading.
     */
    fun setMessage(message: CharSequence?)
}

fun PreferenceScreen.progress(
    @StringRes title: Int,
    card: Boolean = false,
    configure: ProgressPreference.() -> Unit = {},
): ProgressPreference {
    val binding = PreferenceProgressBinding
        .inflate(context.layoutInflater, root, false)

    val impl = object : ProgressPreference {
        override var card: Boolean = card

        override var title: CharSequence
            get() = binding.titleView.text
            set(value) {
                binding.titleView.text = value
            }

        override var summary: CharSequence?
            get() = binding.summaryView.text
            set(value) {
                binding.summaryView.text = value
                binding.summaryView.visibility = if (value == null) View.GONE else View.VISIBLE
            }

        override val view: View
            get() = binding.root

        override var enabled: Boolean
            get() = binding.root.isEnabled
            set(value) {
                binding.root.isEnabled = value
                binding.progressView.isEnabled = value
            }

        override fun setProgress(current: Int, total: Int) {
            // Brings the bar back after setMessage() took it away, so a round
            // started after a previous result does not update an invisible
            // bar.
            binding.progressView.visibility = View.VISIBLE
            binding.progressView.isIndeterminate = false
            binding.progressView.max = total.coerceAtLeast(1)
            binding.progressView.progress = current.coerceIn(0, binding.progressView.max)
        }

        override fun setIndeterminate() {
            binding.progressView.visibility = View.VISIBLE
            binding.progressView.isIndeterminate = true
        }

        override fun setMessage(message: CharSequence?) {
            binding.progressView.visibility = View.GONE
            binding.progressView.isIndeterminate = false

            summary = message
        }
    }

    impl.title = context.getText(title)
    impl.summary = null
    impl.setIndeterminate()

    impl.configure()

    addElement(impl)

    return impl
}
