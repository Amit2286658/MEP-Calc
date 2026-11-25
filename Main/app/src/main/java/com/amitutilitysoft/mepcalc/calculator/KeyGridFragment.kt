package com.amitutilitysoft.mepcalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.fragment.app.Fragment
import kotlin.math.hypot

data class KeySpec(val label: String, val role: KeyRole)

class KeyGridFragment : Fragment() {

    companion object {
        private const val ARG_KEY_SET = "key_set"

        fun newInstance(keySet: Int): KeyGridFragment = KeyGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KEY_SET, keySet) }
        }

        private val BASIC_KEYS = listOf(
            KeySpec("AC", KeyRole.UTILITY), KeySpec("⌫", KeyRole.UTILITY), KeySpec("M+", KeyRole.UTILITY), KeySpec("M-", KeyRole.UTILITY),
            KeySpec("7", KeyRole.DIGIT), KeySpec("8", KeyRole.DIGIT), KeySpec("9", KeyRole.DIGIT), KeySpec("x", KeyRole.OPERATOR),
            KeySpec("4", KeyRole.DIGIT), KeySpec("5", KeyRole.DIGIT), KeySpec("6", KeyRole.DIGIT), KeySpec("÷", KeyRole.OPERATOR),
            KeySpec("1", KeyRole.DIGIT), KeySpec("2", KeyRole.DIGIT), KeySpec("3", KeyRole.DIGIT), KeySpec("+", KeyRole.OPERATOR),
            KeySpec("0", KeyRole.DIGIT), KeySpec(".", KeyRole.DIGIT), KeySpec("-", KeyRole.OPERATOR), KeySpec("=", KeyRole.PRIMARY_EQUALS)
        )

        private val EXTENDED_KEYS = listOf(
            KeySpec("(", KeyRole.UTILITY), KeySpec(")", KeyRole.UTILITY), KeySpec("%", KeyRole.OPERATOR), KeySpec("^", KeyRole.OPERATOR),
            KeySpec("7", KeyRole.DIGIT), KeySpec("8", KeyRole.DIGIT), KeySpec("9", KeyRole.DIGIT), KeySpec("√", KeyRole.OPERATOR),
            KeySpec("4", KeyRole.DIGIT), KeySpec("5", KeyRole.DIGIT), KeySpec("6", KeyRole.DIGIT), KeySpec("ⁿ√", KeyRole.OPERATOR),
            KeySpec("1", KeyRole.DIGIT), KeySpec("2", KeyRole.DIGIT), KeySpec("3", KeyRole.DIGIT), KeySpec("!", KeyRole.OPERATOR),
            KeySpec("0", KeyRole.DIGIT), KeySpec(".", KeyRole.DIGIT), KeySpec("EE", KeyRole.UTILITY), KeySpec("=", KeyRole.PRIMARY_EQUALS)
        )

        private val TRIG_KEYS = listOf(
            KeySpec("sin", KeyRole.DIGIT), KeySpec("cos", KeyRole.DIGIT), KeySpec("tan", KeyRole.DIGIT), KeySpec("π", KeyRole.OPERATOR),
            KeySpec("asin", KeyRole.DIGIT), KeySpec("acos", KeyRole.DIGIT), KeySpec("atan", KeyRole.DIGIT), KeySpec("e", KeyRole.OPERATOR),
            KeySpec("sec", KeyRole.DIGIT), KeySpec("cosec", KeyRole.DIGIT), KeySpec("cot", KeyRole.DIGIT), KeySpec("ln", KeyRole.UTILITY),
            KeySpec("asec", KeyRole.DIGIT), KeySpec("acosec", KeyRole.DIGIT), KeySpec("acot", KeyRole.DIGIT), KeySpec("1/x", KeyRole.UTILITY),
            KeySpec("sinh", KeyRole.DIGIT), KeySpec("cosh", KeyRole.DIGIT), KeySpec("tanh", KeyRole.DIGIT), KeySpec("=", KeyRole.PRIMARY_EQUALS)
        )

        private val FX_KEYS = listOf(
            KeySpec("abs", KeyRole.DIGIT), KeySpec("log", KeyRole.DIGIT), KeySpec("log₂", KeyRole.DIGIT), KeySpec("round", KeyRole.UTILITY),
            KeySpec("x²", KeyRole.DIGIT), KeySpec("x³", KeyRole.DIGIT), KeySpec("xʸ", KeyRole.DIGIT), KeySpec("10ˣ", KeyRole.DIGIT),
            KeySpec("eˣ", KeyRole.DIGIT), KeySpec("nPr", KeyRole.DIGIT), KeySpec("nCr", KeyRole.DIGIT), KeySpec("cbrt", KeyRole.DIGIT),
            KeySpec("ceil", KeyRole.UTILITY), KeySpec("floor", KeyRole.UTILITY), KeySpec("rand", KeyRole.UTILITY), KeySpec(",", KeyRole.UTILITY),
            KeySpec("gcd", KeyRole.DIGIT), KeySpec("lcm", KeyRole.DIGIT), KeySpec("Ans", KeyRole.OPERATOR), KeySpec("=", KeyRole.PRIMARY_EQUALS)
        )
    }

    private val keyViews = mutableListOf<NeumorphicKeyView>()
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val specs = when (requireArguments().getInt(ARG_KEY_SET)) {
            1 -> BASIC_KEYS
            2 -> EXTENDED_KEYS
            3 -> TRIG_KEYS
            else -> FX_KEYS          // position 4 → fx (units removed)
        }
        val activity = requireActivity() as MainActivity

        // Column count is a resource (values/integers.xml) so landscape/tablet
        // shells can reshape the grid without touching the key definitions.
        val cols = resources.getInteger(R.integer.key_grid_columns).coerceAtLeast(1)
        val rows = (specs.size + cols - 1) / cols

        val root = FrameLayout(requireContext())

        // Tablet shells cap the key size and centre the pad (values-sw600dp sets
        // 132 x 104dp; phones leave both at 0 and fill the pane). Without this a
        // 4 x 5 grid stretched over a 1280dp-tall pane gives 190 x 120dp keys.
        //
        // The cap is applied in onMeasure, from the measure spec the parent
        // supplies. A layout listener is not reliable here: the grid's first
        // measured width can differ from the pane it finally sits in, and a
        // cap of 132dp does not fit a tablet's portrait pane (~412dp for four
        // columns), so the pad used to overflow and clip its outer columns.
        // The measure spec always describes the actual pane, and taking the
        // smaller of pane and cap keeps portrait snug and landscape capped.
        val maxKeyWidthDp = resources.getInteger(R.integer.key_max_width_dp)
        val maxKeyHeightDp = resources.getInteger(R.integer.key_max_height_dp)
        val density = resources.displayMetrics.density
        val capWidthPx = (maxKeyWidthDp * cols * density).toInt()
        val capHeightPx = (maxKeyHeightDp * rows * density).toInt()
        val capped = capWidthPx > 0 && capHeightPx > 0

        val grid = object : GridLayout(requireContext()) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (!capped) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
                val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    capWidthPx
                } else {
                    minOf(MeasureSpec.getSize(widthMeasureSpec), capWidthPx)
                }
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    capHeightPx
                } else {
                    minOf(MeasureSpec.getSize(heightMeasureSpec), capHeightPx)
                }
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
            }
        }.apply {
            columnCount = cols; rowCount = rows
            layoutParams = if (capped) {
                // Wrap, so the centred grid keeps the smaller capped size instead
                // of being stretched back to the pane by MATCH_PARENT.
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            } else {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        }
        root.addView(grid)

        keyViews.clear()
        specs.forEachIndexed { index, spec ->
            val keyView = NeumorphicKeyView(requireContext()).apply {
                text = spec.label; contentDescription = spec.label
                keyRole = spec.role; isDarkThemeMode = activity.isDarkMode
                cornerRadiusDp = 8f
                setOnClickListener {
                    activity.onKeyClicked(spec.label)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, activity.keyDescriptionFor(spec.label))
                    else activity.dismissButtonPopup()
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / cols, 1f), GridLayout.spec(index % cols, 1f)
            ).apply { width = 0; height = 0; setGravity(Gravity.FILL) }
            grid.addView(keyView, params); keyViews.add(keyView)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    fun applyTheme(dark: Boolean) {
        keyViews.forEach { it.isDarkThemeMode = dark }
    }

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        if (view == null) return
        wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear()
        val speed = maxRadius / revealDurationMs
        val density = resources.displayMetrics.density
        keyViews.forEach { view ->
            if (view.width == 0 || view.height == 0) return@forEach
            val loc = IntArray(2); view.getLocationInWindow(loc)
            val vx = loc[0] + view.width/2f; val vy = loc[1] + view.height/2f
            val dx = vx - cx; val dy = vy - cy; val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val proximity = 1f - (dist/maxRadius).coerceIn(0f, 1f)
            val bump = (0.015f + 0.035f*proximity)*1.9f; val rotAmp = 6f*proximity
            val delay = (dist/speed).toLong().coerceAtMost(revealDurationMs-40)
            val invLen = if (dist > 0.5f) 1f/dist else 0f
            val pushX = dx*invLen; val pushY = dy*invLen
            val pushDist = 7f*density*(0.5f+proximity)*1.9f

            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay; duration = 480L; interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float; val t = f*0.48
                    val decay = Math.exp(-7.5*t); val pulse = decay*Math.sin(t*22.0)
                    view.scaleX = (1.0+bump*pulse).toFloat(); view.scaleY = (1.0+bump*pulse).toFloat()
                    view.translationX = (pushDist*pulse).toFloat()*pushX; view.translationY = (pushDist*pulse).toFloat()*pushY
                    view.rotation = (rotAmp*decay*Math.sin(t*22.0+0.6)).toFloat()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { view.scaleX=1f;view.scaleY=1f;view.translationX=0f;view.translationY=0f;view.rotation=0f }
                })
                start(); wobbleAnimators.add(this)
            }
        }
    }

    /**
     * Distance-dependent damped-spring entrance animation when switching tabs.
     * Clicking an adjacent tab (`distance == 1`) applies a gentle force, while
     * clicking farther tabs (`distance == 2..4`) scales up the spring impulse,
     * horizontal/vertical wave displacement, scale bounce, and rotational kick.
     */
    fun springSettle(fromDirection: Int, distance: Int = 1) {
        val rootView = view ?: return
        if (keyViews.isEmpty() || keyViews.first().width == 0 || keyViews.first().height == 0) {
            rootView.post {
                if (isAdded && view != null) {
                    springSettle(fromDirection, distance)
                }
            }
            return
        }
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val cols = resources.getInteger(R.integer.key_grid_columns).coerceAtLeast(1)

        // Force scales proportionally with tab distance (1 = small force, 4 = high force)
        val slidePx = (16f + (d - 1f) * 18f) * density * fromDirection
        val vertPx = (3f + (d - 1f) * 3.5f) * density
        val scaleAmp = 0.035f + (d - 1f) * 0.04f
        val rotAmp = (1.8f + (d - 1f) * 2.1f) * fromDirection
        val settleDuration = 460L + (clampedDist - 1) * 60L

        keyViews.forEachIndexed { idx, kv ->
            val row = idx / cols
            val col = idx % cols
            val leadCol = if (fromDirection >= 0) col else (cols - 1 - col)
            val delay = ((row * 12) + (leadCol * 11)).toLong()
            val rowSign = if (row % 2 == 0) 1f else -1f

            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay
                duration = settleDuration
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    val t = f * 0.48
                    val decay = Math.exp(-7.5 * t)
                    val pulse = decay * Math.sin(t * 22.0)
                    val springX = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                    kv.translationX = slidePx * springX * 0.55f
                    kv.translationY = (vertPx * pulse).toFloat() * rowSign
                    kv.scaleX = (1.0 + scaleAmp * pulse).toFloat()
                    kv.scaleY = (1.0 + scaleAmp * pulse).toFloat()
                    kv.rotation = (rotAmp * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        kv.scaleX = 1f
                        kv.scaleY = 1f
                        kv.translationX = 0f
                        kv.translationY = 0f
                        kv.rotation = 0f
                    }
                })
                start()
                wobbleAnimators.add(this)
            }
        }
    }

    override fun onDestroyView() { wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear(); keyViews.clear(); super.onDestroyView() }
}
