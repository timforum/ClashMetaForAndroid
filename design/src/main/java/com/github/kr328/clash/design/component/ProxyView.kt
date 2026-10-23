package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.store.UiStore

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                paint.setShadowLayer(
                    state.config.cardRadius,
                    state.config.cardOffset,
                    state.config.cardOffset,
                    state.config.shadow
                )

                drawPath(path, paint)

                clipPath(path)
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.textSize = state.config.textSize

        // measure delay text bounds
        val delayCount = paint.breakText(
            state.delayText,
            false,
            (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                .coerceAtLeast(0f),
            null
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width()

        // A node the last round gave a figure to carries a green tick in the
        // corner of its row. The text moves right to leave room for it: drawn
        // over the title instead, the tick would sit on the first letters of
        // the name, which is the one part of the row a reader looks for.
        val badgeSize = if (state.hasSpeed) state.config.textSize else 0f
        val badgeGap = if (state.hasSpeed) state.config.textSize * 0.25f else 0f
        val textLeft = state.config.layoutPadding + state.config.contentPadding
        val indentedLeft = textLeft + badgeSize + badgeGap

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2 -
                badgeSize -
                badgeGap
                )
            .coerceAtLeast(0f)

        // measure title text bounds
        val titleCount = paint.breakText(
            state.title,
            false,
            mainTextWidth,
            null,
        )

        // measure subtitle text bounds
        val subtitleCount = paint.breakText(
            state.subtitle,
            false,
            mainTextWidth,
            null,
        )

        // text draw measure
        val textOffset = (paint.descent() + paint.ascent()) / 2

        val titleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f - textOffset
        val subtitleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset

        paint.reset()

        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        paint.color = state.controls

        // draw delay
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset

            drawText(state.delayText, 0, delayCount, x, y, paint)
        }

        // draw title
        canvas.drawText(state.title, 0, titleCount, indentedLeft, titleY, paint)

        // draw subtitle
        canvas.drawText(state.subtitle, 0, subtitleCount, indentedLeft, subtitleY, paint)

        if (state.hasSpeed) {
            drawSpeedBadge(canvas, state, textLeft, titleY)
        }

        if (!isFocused)
            return

        // The stock ripple background only answers presses, so a remote that
        // merely moves focus would leave the screen looking untouched - the
        // click of the focus move is then the only sign anything happened.
        drawFocusRing(canvas, state)
    }

    /**
     * Draws the green tick that marks a node the last round measured.
     *
     * A tick rather than a dot or a filled circle: the row already uses colour
     * for the selection and prints the delay on the right, and a shape reads at
     * television distance where a small dot disappears into the background.
     * The stroke is proportional to the text so the mark scales with the
     * layout instead of being sized for one of them.
     */
    private fun drawSpeedBadge(
        canvas: Canvas,
        state: ProxyViewState,
        left: Float,
        baseline: Float,
    ) {
        val size = state.config.textSize * 0.9f

        val paint = state.paint

        paint.reset()

        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.17f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = SPEED_BADGE

        val x = left + size * 0.12f
        val y = baseline - size * 0.78f

        canvas.drawLine(x, y + size * 0.36f, x + size * 0.26f, y + size * 0.62f, paint)
        canvas.drawLine(x + size * 0.26f, y + size * 0.62f, x + size * 0.74f, y + size * 0.14f, paint)
    }

    private fun drawFocusRing(canvas: Canvas, state: ProxyViewState) {
        val config = state.config
        val inset = config.layoutPadding + config.focusStrokeWidth / 2f
        val right = width - inset
        val bottom = height - inset

        if (right <= inset || bottom <= inset)
            return

        val paint = state.paint

        paint.reset()

        paint.isAntiAlias = true
        paint.strokeWidth = config.focusStrokeWidth

        val radius = if (config.proxyLine == 1) 0f else config.cardRadius

        // controls tracks the selection immediately, unlike background which
        // animates towards it, so the ring is legible even mid-transition.
        paint.color = Color.argb(
            config.focusFillAlpha,
            Color.red(state.controls),
            Color.green(state.controls),
            Color.blue(state.controls),
        )

        canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.color = state.controls

        canvas.drawRoundRect(inset, inset, right, bottom, radius, radius, paint)
    }

    private companion object {
        // Green enough to read as "works" on both the light and the dark
        // theme, and dark enough not to glare on a television.
        const val SPEED_BADGE = 0xFF22C55E.toInt()
    }
}