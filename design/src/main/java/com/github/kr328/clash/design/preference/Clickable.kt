package com.github.kr328.clash.design.preference

import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.databinding.PreferenceClickableBinding
import com.github.kr328.clash.design.util.layoutInflater

interface ClickablePreference : Preference, CardPreference {
    var title: CharSequence

    var icon: Drawable?

    /**
     * A small marker shown at the trailing edge of the row, e.g. the green
     * tick left behind by a successful connection test. Null hides it.
     */
    var endIcon: Drawable?

    /**
     * Draws the row as a light rounded card with a gap to its neighbours
     * instead of a flat full-width strip. Set by the [card] parameter of
     * [clickable], or directly in a `configure` block.
     */
    override var card: Boolean

    var summary: CharSequence?

    fun clicked(clicked: () -> Unit)
}

fun PreferenceScreen.clickable(
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    @StringRes summary: Int? = null,
    card: Boolean = false,
    configure: ClickablePreference.() -> Unit = {},
): ClickablePreference {
    val binding = PreferenceClickableBinding
        .inflate(context.layoutInflater, root, false)

    val impl = object : ClickablePreference {
        override var card: Boolean = card

        override var icon: Drawable?
            get() = binding.iconView.background
            set(value) {
                binding.iconView.background = value
                binding.iconView.visibility = if (value == null) View.GONE else View.VISIBLE
            }
        override var endIcon: Drawable?
            get() = binding.endView.drawable
            set(value) {
                binding.endView.setImageDrawable(value)
                binding.endView.visibility = if (value == null) View.GONE else View.VISIBLE
            }
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

        override fun clicked(clicked: () -> Unit) {
            binding.root.setOnClickListener {
                clicked()
            }
        }
    }

    impl.title = context.getText(title)

    if (icon != null) {
        impl.icon = context.getDrawableCompat(icon)
    }

    if (summary != null) {
        impl.summary = context.getText(summary)
    } else {
        impl.summary = null
    }

    impl.configure()

    addElement(impl)

    return impl
}