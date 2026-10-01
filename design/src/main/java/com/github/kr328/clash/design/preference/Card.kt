package com.github.kr328.clash.design.preference

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import com.google.android.material.R as MaterialR

/**
 * A preference that is laid out as part of a list of cards rather than as a
 * flat full-width strip.
 */
interface CardPreference {
    var card: Boolean
}

/**
 * Draws this row as a rounded card inset from the page edge, with a gap to its
 * neighbours, and a ripple clipped to the same rounded shape so the tap
 * feedback stays inside the card instead of bleeding across the gap.
 *
 * The fill is the theme's own surface color, not a tint of the text color. The
 * page behind it is `colorBackground`, and in both of this app's themes the
 * surface is the lighter of the two - white on #FAFAFA in light, #202020 on
 * #121212 in dark - so a card always reads as a sheet laid on the page. A
 * translucent text tint instead produced a muddy near-invisible patch that the
 * rows then had to fight with.
 *
 * Call this after the view has been added to a screen, because it also sets the
 * margins that `addView(view, params)` would otherwise replace.
 */
fun View.applyCardStyle() {
    val radius = resources.getDimension(R.dimen.preference_card_radius)

    val content = GradientDrawable().apply {
        cornerRadius = radius
        setColor(themeColor(MaterialR.attr.colorSurface))
    }

    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }

    background = RippleDrawable(
        // colorControlHighlight already carries the alpha the framework intends
        // for a pressed state; scaling it down again makes the tap invisible.
        ColorStateList.valueOf(themeColor(MaterialR.attr.colorControlHighlight)),
        content,
        mask,
    )

    val horizontal = resources.getDimensionPixelSize(R.dimen.preference_card_margin_horizontal)
    val vertical = resources.getDimensionPixelSize(R.dimen.preference_card_margin_vertical)

    (layoutParams as? LinearLayout.LayoutParams)?.setMargins(horizontal, vertical, horizontal, vertical)
}

/**
 * Gives a section heading the rhythm it needs between two stacks of cards.
 *
 * A heading keeps no background of its own - once it is a card it stops being a
 * heading - so the space is what has to carry it: a wide gap above, to say a new
 * group starts here, and a tight one below, so the heading belongs to the rows
 * it introduces rather than floating between the two groups.
 */
fun View.applySectionSpacing() {
    val params = layoutParams as? LinearLayout.LayoutParams ?: return

    params.topMargin = resources.getDimensionPixelSize(R.dimen.preference_section_margin_top)

    setPadding(
        paddingLeft,
        0,
        paddingRight,
        resources.getDimensionPixelSize(R.dimen.preference_section_padding_bottom),
    )
}

/** Resolves a theme color attribute, optionally scaling its alpha. */
@ColorInt
fun View.themeColor(@AttrRes attr: Int, alpha: Float = 1f): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    val color = if (value.resourceId != 0) {
        ContextCompat.getColor(context, value.resourceId)
    } else {
        value.data
    }
    return Color.argb(
        (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
