package com.github.kr328.clash.design.dialog

import android.content.Context
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogTextFieldBinding
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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