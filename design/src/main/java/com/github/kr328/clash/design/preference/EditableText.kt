package com.github.kr328.clash.design.preference

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.dialog.requestModelTextInputWithRecent
import com.github.kr328.clash.design.util.Validator
import com.github.kr328.clash.design.util.ValidatorAcceptAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

interface EditableTextPreference : ClickablePreference {
    var placeholder: CharSequence?
    var empty: CharSequence?
    var text: String?

    /**
     * Keeps the stored value out of sight: the row shows a fixed-length mask
     * instead of the value, and the editor masks what is typed. The value is
     * still loaded into the editor, so a saved token can be changed rather than
     * retyped. The mask is a fixed length so it does not disclose how long the
     * secret is.
     */
    var password: Boolean
}

fun <T> PreferenceScreen.editableText(
    value: KMutableProperty0<T>,
    adapter: NullableTextAdapter<T>,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    @StringRes placeholder: Int? = null,
    @StringRes empty: Int? = null,
    card: Boolean = false,
    configure: EditableTextPreference.() -> Unit = {},
): EditableTextPreference {
    val impl = object : EditableTextPreference, ClickablePreference by clickable(title, icon, card = card) {
        override var placeholder: CharSequence? = null
        override var empty: CharSequence? = null

        override var password: Boolean = false

        override var text: String? = null
            set(value) {
                field = value

                when {
                    value == null -> {
                        this.summary = this.placeholder
                    }
                    value.isEmpty() -> {
                        this.summary = this.empty
                    }
                    // A secret is reported as "set", never as itself: the row
                    // is readable over someone's shoulder.
                    password -> {
                        this.summary = MASK
                    }
                    else -> {
                        this.summary = value
                    }
                }
            }
    }

    if (placeholder != null) {
        impl.placeholder = context.getText(placeholder)
    }

    if (empty != null) {
        impl.empty = context.getText(empty)
    }

    impl.configure()

    launch(Dispatchers.Main) {
        impl.text = withContext(Dispatchers.IO) {
            adapter.from(value.get())
        }

        impl.clicked {
            this@editableText.launch(Dispatchers.Main) {
                val text = context.requestModelTextInput(
                    initial = impl.text,
                    title = impl.title,
                    reset = context.getText(R.string.reset),
                    hint = impl.title,
                    password = impl.password,
                )

                val newValue = withContext(Dispatchers.IO) {
                    adapter.to(text).apply(value::set)
                }

                impl.text = adapter.from(newValue)
            }
        }
    }

    return impl
}

/**
 * An editable text row whose editor also offers the values entered before.
 *
 * The values are not part of the row: the caller supplies them through
 * [recent] on the editor call, so a field that has never been used opens as
 * the plain input rather than onto an empty history.
 */
fun <T> PreferenceScreen.editableTextWithRecent(
    value: KMutableProperty0<T>,
    adapter: NullableTextAdapter<T>,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    @StringRes placeholder: Int? = null,
    @StringRes empty: Int? = null,
    card: Boolean = false,
    recent: suspend () -> List<String> = { emptyList() },
    @StringRes recentLabel: Int? = null,
    validator: Validator = ValidatorAcceptAll,
    @StringRes error: Int? = null,
): EditableTextPreference {
    val impl = editableText(
        value = value,
        adapter = adapter,
        title = title,
        icon = icon,
        placeholder = placeholder,
        empty = empty,
        card = card,
    )

    launch(Dispatchers.Main) {
        val errorText = error?.let { context.getText(it) }
        val recentText = recentLabel?.let { context.getText(it) }

        // Swallowed rather than left hanging: a history that fails to read is
        // only a convenience, and the field stays editable either way.
        val recentValues = runCatching {
            withContext(Dispatchers.IO) { recent() }
        }.getOrDefault(emptyList())

        impl.clicked {
            this@editableTextWithRecent.launch(Dispatchers.Main) {
                val text = context.requestModelTextInputWithRecent(
                    initial = impl.text,
                    title = impl.title,
                    reset = context.getText(R.string.reset),
                    hint = impl.title,
                    error = errorText,
                    validator = validator,
                    recent = recentValues,
                    recentLabel = recentText,
                )

                val newValue = withContext(Dispatchers.IO) {
                    adapter.to(text).apply(value::set)
                }

                impl.text = adapter.from(newValue)
            }
        }
    }

    return impl
}

// A fixed length on purpose: the row must not disclose how long the secret is.
private const val MASK = "••••••••"