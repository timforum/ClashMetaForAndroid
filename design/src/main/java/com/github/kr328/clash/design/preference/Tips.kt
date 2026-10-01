package com.github.kr328.clash.design.preference

import android.content.res.ColorStateList
import android.view.View
import androidx.annotation.StringRes
import com.github.kr328.clash.design.databinding.PreferenceTipsBinding
import com.github.kr328.clash.design.util.getHtml
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.R as MaterialR

interface TipsPreference : Preference, CardPreference {
    var text: CharSequence?
}

fun PreferenceScreen.tips(
    @StringRes text: Int,
    card: Boolean = false,
    configure: TipsPreference.() -> Unit = {},
): TipsPreference {
    val binding = PreferenceTipsBinding
        .inflate(context.layoutInflater, context.root, false)
    val impl = object : TipsPreference {
        override var card: Boolean = card

        override var text: CharSequence?
            get() = binding.tips.text
            set(value) {
                binding.tips.text = value
            }
        override val view: View
            get() = binding.root
        override var enabled: Boolean
            get() = binding.root.isEnabled
            set(value) {
                binding.root.isEnabled = value
            }
    }

    binding.tips.text = context.getHtml(text)

    // Muted to match the note it introduces: at full strength the icon reads as
    // a warning marker rather than as decoration.
    binding.icon.backgroundTintList = ColorStateList.valueOf(
        binding.icon.themeColor(MaterialR.attr.colorOnSurface, TIPS_ICON_ALPHA),
    )

    impl.configure()

    addElement(impl)

    return impl
}

private const val TIPS_ICON_ALPHA = 0.45f
