package com.github.kr328.clash.design.preference

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.CoroutineScope

interface PreferenceScreen : CoroutineScope {
    val context: Context
    val root: ViewGroup
}

fun CoroutineScope.preferenceScreen(
    context: Context,
    configure: PreferenceScreen.() -> Unit
): PreferenceScreen {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val impl = object : PreferenceScreen, CoroutineScope by this {
        override val context: Context
            get() = context
        override val root: ViewGroup
            get() = root
    }

    impl.configure()

    return impl
}

fun PreferenceScreen.addElement(preference: Preference) {
    root.addView(preference.view, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/**
 * Lowers every item title and summary one text-appearance level
 * (Body1 -> Body2, Body2 -> Caption) while leaving group headers alone, so a
 * dense settings page reads a size smaller without losing its sectioning.
 */
fun PreferenceScreen.shrinkText() {
    applySmallerText(root)
}

private fun applySmallerText(view: View) {
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            applySmallerText(view.getChildAt(i))
        }
    }

    if (view is TextView) {
        @Suppress("DEPRECATION")
        when (view.id) {
            R.id.title_view -> view.setTextAppearance(
                view.context,
                MaterialR.style.TextAppearance_MaterialComponents_Body2,
            )
            R.id.summary_view -> view.setTextAppearance(
                view.context,
                MaterialR.style.TextAppearance_MaterialComponents_Caption,
            )
        }
    }
}