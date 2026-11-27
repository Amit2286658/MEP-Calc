package com.amitutilitysoft.mepcalc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Shared frosted-glass helper used by every modal layer in the app:
 * the File-Manager drawer backdrop, the About/Info window, and the
 * hold-tooltips (each at its own opacity). Renders a view tree into a
 * downsampled bitmap and runs the app's signature two-pass box blur so all
 * frosted layers share the exact same texture.
 */
object Frost {

    /**
     * Runs a two-pass separable box blur of [radius] pixels in-place on [bmp].
     */
    fun blurBitmapInPlace(bmp: Bitmap, radius: Int = 8) {
        val sw = bmp.width
        val sh = bmp.height
        if (sw <= 1 || sh <= 1) return
        val pixels = IntArray(sw * sh)
        val temp = IntArray(sw * sh)
        bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val r = radius.coerceIn(1, 16)
        val div = 2 * r + 1
        repeat(2) {
            for (y in 0 until sh) {
                val rowOffset = y * sw
                for (x in 0 until sw) {
                    var sumA = 0
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    for (k in -r..r) {
                        val px = (x + k).coerceIn(0, sw - 1)
                        val c = pixels[rowOffset + px]
                        sumA += (c ushr 24) and 0xFF
                        sumR += (c ushr 16) and 0xFF
                        sumG += (c ushr 8) and 0xFF
                        sumB += c and 0xFF
                    }
                    temp[rowOffset + x] =
                        ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                }
            }
            for (x in 0 until sw) {
                for (y in 0 until sh) {
                    var sumA = 0
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    for (k in -r..r) {
                        val py = (y + k).coerceIn(0, sh - 1)
                        val c = temp[py * sw + x]
                        sumA += (c ushr 24) and 0xFF
                        sumR += (c ushr 16) and 0xFF
                        sumG += (c ushr 8) and 0xFF
                        sumB += c and 0xFF
                    }
                    pixels[y * sw + x] =
                        ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                }
            }
        }
        bmp.setPixels(pixels, 0, sw, 0, 0, sw, sh)
    }

    /**
     * Renders [sourceView] at 1/[downscale] resolution, blurs it with a
     * two-pass box ([radius] px at that low resolution — plenty strong after
     * the 6× upscale with bilinear filtering) and returns the SMALL bitmap.
     * Display it in an `ImageView` with `ScaleType.FIT_XY`.
     *
     * NOTE: children that are `View.INVISIBLE`/`GONE` are skipped by
     * [View.draw], so overlays whose visibility is managed around the call
     * are naturally excluded from the snapshot.
     */
    fun frozenBackdrop(sourceView: View, downscale: Int = 6, radius: Int = 8): Bitmap? {
        val w = sourceView.width
        val h = sourceView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val sw = (w / downscale).coerceAtLeast(16)
            val sh = (h / downscale).coerceAtLeast(16)
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(1f / downscale, 1f / downscale)
            sourceView.draw(canvas)
            blurBitmapInPlace(bmp, radius.coerceIn(2, 14))
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Un-blurred full-resolution snapshot of [sourceView] (same render pipeline as
     * [frozenBackdrop], but without the blur pass, `downscale = 1`). Callers crop a sharp
     * region out of it — for example the calculator's expression/answer display — and pin
     * that crop on top of a frosted overlay so that region stays clearly readable while
     * everything else is blurred.
     */
    fun rawBackdrop(sourceView: View): Bitmap? {
        val w = sourceView.width
        val h = sourceView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            sourceView.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * LIVE frosted blur for Android 12+ (API 31): applies a GPU
     * [RenderEffect] blur to [target] so everything behind the overlay stays
     * live-animating through the frost. Returns `false` on older versions
     * (and on any GPU failure) so the caller can fall back to the static
     * [frozenBackdrop] snapshot.
     */
    fun tryLiveBlur(target: View?, radiusPx: Float): Boolean {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val effect = RenderEffect.createBlurEffect(
                radiusPx, radiusPx, Shader.TileMode.CLAMP
            )
            target.setRenderEffect(effect)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Removes a blur applied by [tryLiveBlur] (no-op where unsupported). */
    fun clearLiveBlur(target: View?) {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            target.setRenderEffect(null)
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Builds the frosted backdrop used by every drop-down keypad: a blurred snapshot of
     * the activity's [rootFrame] with a live, unblurred cutout over the calculator's
     * expression and answer displays. Using the activity root (rather than the current
     * fragment) makes the frost span the whole window instead of stopping at the page
     * bounds, which is what made the Currency tab's mini-keypad blur look clipped.
     *
     * The display text views are hidden for the synchronous snapshot so their glyphs are
     * never baked into the blur (that produced a pale halo around the cutout), then
     * restored immediately.
     */
    fun frostedBackdropWithDisplayCutout(
        act: MainActivity,
        rootFrame: FrameLayout,
        dp: (Float) -> Int
    ): ImageView? {
        val dispPri = act.binding.tvPrimaryDisplay
        val dispSec = act.binding.tvSecondaryDisplay
        val dispAngle = act.binding.tvAngleMode
        val dispMr = act.binding.mrDisplayCluster

        val origPriVis = dispPri.visibility
        val origSecVis = dispSec.visibility
        val origAngleVis = dispAngle.visibility
        val origMrVis = dispMr.visibility

        dispPri.visibility = View.INVISIBLE
        dispSec.visibility = View.INVISIBLE
        dispAngle.visibility = View.INVISIBLE
        if (origMrVis == View.VISIBLE) dispMr.visibility = View.INVISIBLE
        val frostBmp = try {
            frozenBackdrop(rootFrame, downscale = 6, radius = 6)
        } finally {
            dispPri.visibility = origPriVis
            dispSec.visibility = origSecVis
            dispAngle.visibility = origAngleVis
            dispMr.visibility = origMrVis
        } ?: return null

        val outerPath = Path()
        val holePath = Path()
        val cutoutRect = RectF()
        return object : ImageView(act) {
            override fun onDraw(canvas: Canvas) {
                if (dispPri.width > 0 && dispSec.width > 0 && width > 0 && height > 0) {
                    val priLoc = IntArray(2)
                    val secLoc = IntArray(2)
                    val selfLoc = IntArray(2)
                    dispPri.getLocationInWindow(priLoc)
                    dispSec.getLocationInWindow(secLoc)
                    getLocationInWindow(selfLoc)
                    val leftB = (minOf(priLoc[0], secLoc[0]) - selfLoc[0] - dp(12f)).toFloat()
                    val topB = (minOf(priLoc[1], secLoc[1]) - selfLoc[1] - dp(10f)).toFloat()
                    val rightB = (maxOf(priLoc[0] + dispPri.width, secLoc[0] + dispSec.width) - selfLoc[0] + dp(12f)).toFloat()
                    val bottomB = (maxOf(priLoc[1] + dispPri.height, secLoc[1] + dispSec.height) - selfLoc[1] + dp(10f)).toFloat()
                    cutoutRect.set(
                        leftB.coerceIn(0f, width.toFloat()),
                        topB.coerceIn(0f, height.toFloat()),
                        rightB.coerceIn(0f, width.toFloat()),
                        bottomB.coerceIn(0f, height.toFloat())
                    )
                    outerPath.reset()
                    outerPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                    holePath.reset()
                    val r = dp(10f).toFloat()
                    holePath.addRoundRect(cutoutRect, r, r, Path.Direction.CW)
                    outerPath.op(holePath, Path.Op.DIFFERENCE)

                    val saveCount = canvas.save()
                    canvas.clipPath(outerPath)
                    super.onDraw(canvas)
                    canvas.restoreToCount(saveCount)
                } else {
                    super.onDraw(canvas)
                }
            }
        }.apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
        }
    }
}
