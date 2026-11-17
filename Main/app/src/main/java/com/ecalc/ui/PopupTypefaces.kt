package com.ecalc.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.core.content.res.ResourcesCompat
import com.ecalc.R

/**
 * Shared popup typography (bundled Inter family) for the function-docs popups
 * (`showButtonPopup` hold-popups on the Functions tab and the editor's suggestion
 * doc popup). Inter is a clean, sharper geometric sans that stays legible at the
 * small sizes these popups render at.
 *
 * Exact `Typeface` instances are resolved once per process and attached through
 * [ExactTypefaceSpan] so that bold / italic emphasis uses Inter's real designed
 * styles instead of the platform's synthetic (faux) bold/italic.
 */
object PopupTypefaces {

    /** The " — " (em dash) separator that splits a "label — description" doc line. */
    const val DASH_SEP: String = " — "

    @Volatile private var cached: Array<Typeface?> = arrayOfNulls(5)

    fun regular(ctx: Context): Typeface = font(ctx, 0)
    fun medium(ctx: Context): Typeface = font(ctx, 1)
    fun semibold(ctx: Context): Typeface = font(ctx, 2)
    fun italic(ctx: Context): Typeface = font(ctx, 3)
    fun semiboldItalic(ctx: Context): Typeface = font(ctx, 4)

    private fun font(ctx: Context, slot: Int): Typeface {
        cached[slot]?.let { return it }
        val res = when (slot) {
            0 -> R.font.inter_regular
            1 -> R.font.inter_medium
            2 -> R.font.inter_semibold
            3 -> R.font.inter_italic
            else -> R.font.inter_semibolditalic
        }
        val tf = ResourcesCompat.getFont(ctx.applicationContext, res)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        cached[slot] = tf
        return tf
    }
}

/**
 * A [MetricAffectingSpan] that applies one exact bundled
 * `Typeface` instance (real designed weight/slant, never synthesized).
 */
internal class ExactTypefaceSpan(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = tf
    }

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.typeface = tf
    }
}

/**
 * Builds a styled "label — description" sequence: everything up to (and including)
 * the first " — " separator is rendered bold-italic in [labelTf], and everything
 * after the dash is rendered non-bold italic in [bodyTf]. When [full] contains no
 * dash separator, the whole text is rendered non-bold italic.
 *
 * [prefix] (e.g. "1. " or "• ") is prepended in the label style so numbering and
 * bullets align with the label they introduce.
 */
internal fun styledLabelDesc(
    context: Context,
    full: String,
    prefix: String = "",
    labelTf: Typeface = PopupTypefaces.semiboldItalic(context),
    bodyTf: Typeface = PopupTypefaces.italic(context)
): SpannableString {
    val out = SpannableString(prefix + full)
    val start = prefix.length
    val len = out.length
    if (len > start) {
        val dashIdx = full.indexOf(PopupTypefaces.DASH_SEP)
        if (dashIdx >= 0) {
            val labelEnd = start + dashIdx + PopupTypefaces.DASH_SEP.length
            out.setSpan(ExactTypefaceSpan(labelTf), start, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (labelEnd < len) {
                out.setSpan(ExactTypefaceSpan(bodyTf), labelEnd, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            out.setSpan(ExactTypefaceSpan(bodyTf), start, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return out
}
