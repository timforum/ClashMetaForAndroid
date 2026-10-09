package com.github.kr328.clash.design.dialog

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogTextFieldBinding
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Context.requestModelTextInput(
    initial: String,
    title: CharSequence,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
): String {
    return this.requestModelTextInput(initial, title, null, hint, error, validator = validator)!!
}

suspend fun Context.requestModelTextInput(
    initial: String?,
    title: CharSequence,
    reset: CharSequence?,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    password: Boolean = false,
    validator: Validator = ValidatorAcceptAll,
): String? {
    return suspendCancellableCoroutine {
        val binding = DialogTextFieldBinding
            .inflate(layoutInflater, this.root, false)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(binding.root)
            .setCancelable(true)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = binding.textField.text?.toString() ?: ""

                if (validator(text))
                    it.resume(text)
                else
                    it.resume(initial)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setOnDismissListener { _ ->
                if (!it.isCompleted)
                    it.resume(initial)
            }

        if (reset != null) {
            builder.setNeutralButton(reset) { _, _ ->
                it.resume(null)
            }
        }

        val dialog = builder.create()

        it.invokeOnCancellation {
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            if (hint != null)
                binding.textLayout.hint = hint

            binding.textField.apply {
                binding.textLayout.isErrorEnabled = error != null

                // Mask what is typed for a secret. The password toggle stays
                // available so a mistyped token can still be checked, and the
                // field starts hidden because that is how it was left before.
                if (password) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setSelection(text?.length ?: 0)
                }

                doOnTextChanged { text, _, _, _ ->
                    if (!validator(text?.toString() ?: "")) {
                        if (error != null)
                            binding.textLayout.error = error

                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    } else {
                        if (error != null)
                            binding.textLayout.error = null

                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }

                setText(initial)

                setSelection(0, initial?.length ?: 0)

                requestTextInput()
            }
        }

        dialog.show()
    }
}

/**
 * A text field that also offers the values used before, listed under it.
 *
 * Typing a URL by hand is where an error hides, and the cost of a wrong probe
 * target is a whole round of measurements thrown away. Offering what already
 * worked lets a target be picked rather than retyped, while the field stays
 * editable so a new one can still be entered.
 *
 * [recent] is shown newest first and may be empty, in which case the dialog is
 * the plain text input. Choosing an entry fills the field instead of accepting,
 * so it can still be corrected before it is kept.
 */
suspend fun Context.requestModelTextInputWithRecent(
    initial: String?,
    title: CharSequence,
    reset: CharSequence?,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
    recent: List<String> = emptyList(),
    recentLabel: CharSequence? = null,
): String? {
    if (recent.isEmpty()) {
        return this.requestModelTextInput(initial, title, reset, hint, error, validator = validator)
    }

    return suspendCancellableCoroutine {
        val view = layoutInflater.inflate(R.layout.dialog_text_field_recent, root, false)

        val textLayout = view.findViewById<TextInputLayout>(R.id.text_layout)
        val textField = view.findViewById<TextInputEditText>(R.id.text_field)
        val recentTitle = view.findViewById<TextView>(R.id.recent_label)
        val recentContainer = view.findViewById<LinearLayout>(R.id.recent_container)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(true)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = textField.text?.toString() ?: ""

                if (validator(text))
                    it.resume(text)
                else
                    it.resume(initial)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setOnDismissListener { _ ->
                if (!it.isCompleted)
                    it.resume(initial)
            }

        if (reset != null) {
            builder.setNeutralButton(reset) { _, _ ->
                it.resume(null)
            }
        }

        val dialog = builder.create()

        it.invokeOnCancellation {
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            if (hint != null)
                textLayout.hint = hint

            if (recentLabel != null)
                recentTitle.text = recentLabel

            textField.apply {
                textLayout.isErrorEnabled = error != null

                doOnTextChanged { text, _, _, _ ->
                    if (!validator(text?.toString() ?: "")) {
                        if (error != null)
                            textLayout.error = error

                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    } else {
                        if (error != null)
                            textLayout.error = null

                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }

                setText(initial)

                setSelection(0, initial?.length ?: 0)

                requestTextInput()
            }

            for (value in recent) {
                val item = layoutInflater.inflate(
                    R.layout.item_recent_value,
                    recentContainer,
                    false,
                ) as TextView

                item.text = value

                item.setOnClickListener {
                    textField.setText(value)
                    textField.setSelection(value.length)
                }

                recentContainer.addView(item)
            }
        }

        dialog.show()
    }
}