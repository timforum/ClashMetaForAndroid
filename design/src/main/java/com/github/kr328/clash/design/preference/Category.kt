package com.github.kr328.clash.design.preference

import android.view.View
import androidx.annotation.StringRes
import com.github.kr328.clash.design.databinding.PreferenceCategoryBinding
import com.github.kr328.clash.design.util.layoutInflater

/**
 * A heading that introduces the group of rows below it.
 *
 * [card] says the heading sits in a list of cards, which changes its spacing
 * but not its appearance: a heading drawn as a card stops reading as a heading.
 */
fun PreferenceScreen.category(
    @StringRes text: Int,
    card: Boolean = false,
) {
    val binding = PreferenceCategoryBinding
        .inflate(context.layoutInflater, root, false)

    binding.textView.text = context.getString(text)

    addElement(object : Preference {
        override val view: View
            get() = binding.root
        override var enabled: Boolean
            get() = binding.root.isEnabled
            set(value) {
                binding.root.isEnabled = value
            }
    })

    if (card) {
        binding.root.applySectionSpacing()
    }
}
