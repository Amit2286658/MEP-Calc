package com.amitutilitysoft.mepcalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.hypot

/**
 * Tab 5 (6th tab): Full-Engine Currency Converter (`From` -> `To`).
 *
 * Exposes all 55+ currencies supported by `CurrencyConverter` and the C11 Math Engine's
 * `@currency_convert` / `into` patch matrix.
 */
class CurrencyFragment : Fragment() {

    private var rootContainer: FrameLayout? = null
    private var mainContent: LinearLayout? = null
    private var tvTitle: TextView? = null
    private var tvRateStatus: TextView? = null
    private var tvCallInfo: TextView? = null

    private var fromCard: LinearLayout? = null
    private var tvFromLabel: TextView? = null
    private var tvFromCode: TextView? = null
    private var tvFromName: TextView? = null

    private var btnSwap: NeumorphicKeyView? = null

    private var toCard: LinearLayout? = null
    private var tvToLabel: TextView? = null
    private var tvToCode: TextView? = null
    private var tvToName: TextView? = null

    private var fromCode: String = "USD"
    private var toCode: String = "INR"

    private val quickKeys = mutableListOf<NeumorphicKeyView>()
    private var pickerOverlay: FrameLayout? = null
    private var pickerModal: View? = null
    private var pickerList: RecyclerView? = null
    private var pickerAnimator: ValueAnimator? = null
    /** True between [dismissPicker] and the end of the shrink animation. */
    private var pickerClosing = false
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity
        val prefs = ctx.getSharedPreferences("mep_calc_prefs", Context.MODE_PRIVATE)
        fromCode = prefs.getString("ccy_from", "USD") ?: "USD"
        toCode = prefs.getString("ccy_to", "INR") ?: "INR"

        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = root

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainContent = content

        // 1. Header + Live Rate Pill
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
                bottomMargin = dp(6f)
            }
        }
        tvTitle = TextView(ctx).apply {
            text = "Currency"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvRateStatus = TextView(ctx).apply {
            textSize = 11.5f
            maxLines = 1
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                text = "Refreshing rates…"
                CurrencyConverter.refreshRatesAsync { _, _ ->
                    view?.post { updatePreviewAndRate() }
                }
            }
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvRateStatus)
        content.addView(headerRow)

        // 2. From <-> To Selector Row
        val pairRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
                bottomMargin = dp(6f)
            }
        }

        fromCard = buildCurrencySelectorCard(ctx, dp, "FROM") { openCurrencyPicker(isFrom = true) }.also { card ->
            tvFromLabel = card.getChildAt(0) as TextView
            tvFromCode = card.getChildAt(1) as TextView
            tvFromName = card.getChildAt(2) as TextView
        }
        btnSwap = NeumorphicKeyView(ctx).apply {
            text = "⇄"
            textSize = 18f
            keyRole = KeyRole.OPERATOR
            cornerRadiusDp = 12f
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(54f)).apply {
                marginStart = dp(6f)
                marginEnd = dp(6f)
            }
            setOnClickListener {
                val tmp = fromCode
                fromCode = toCode
                toCode = tmp
                savePairPrefs(ctx)
                updatePairCards()
                updatePreviewAndRate()
                activity.displaySpringPulse(this, 0.08f)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(this, "Swap currencies — reverses From and To currencies.")
                else activity.dismissButtonPopup()
            }
        }
        toCard = buildCurrencySelectorCard(ctx, dp, "TO") { openCurrencyPicker(isFrom = false) }.also { card ->
            tvToLabel = card.getChildAt(0) as TextView
            tvToCode = card.getChildAt(1) as TextView
            tvToName = card.getChildAt(2) as TextView
        }

        pairRow.addView(fromCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pairRow.addView(btnSwap)
        pairRow.addView(toCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(pairRow)

        // 3. Small info strip: which engine call `=` will make with the current From/To.
        tvCallInfo = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            maxLines = 1
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
                bottomMargin = dp(6f)
            }
        }
        content.addView(tvCallInfo)

        // 4. Full-height 4x4 Currency Keypad filling all remaining vertical real estate.
        //    Every key writes plainly into the main expression; `=` converts it.
        val keyGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        quickKeys.clear()
        val rows = listOf(
            listOf("7", "8", "9", "\u232B"),
            listOf("4", "5", "6", "()"),
            listOf("1", "2", "3", ","),
            listOf("0", ".", "AC", "=")
        )
        for (row in rows) {
            val rowLl = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            for (label in row) {
                val role = when (label) {
                    "=" -> KeyRole.PRIMARY_EQUALS
                    "AC", "\u232B", "()" -> KeyRole.UTILITY
                    else -> KeyRole.DIGIT
                }
                val btn = NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = when (label) {
                        "AC", "()" -> 14f
                        else -> 16.5f
                    }
                    keyRole = role
                    cornerRadiusDp = 9f
                    isDarkThemeMode = activity.isDarkMode
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener {
                        val a = activity
                        when (label) {
                            "=" -> {
                                val expr = a.expressionText.trim().ifBlank { "0" }
                                a.evaluateCurrencyConversion(expr, fromCode, toCode)
                            }
                            "()" -> a.onSmartBracketClicked()
                            else -> a.onKeyClicked(label)
                        }
                        updatePreviewAndRate()
                    }
                    val tooltip = when (label) {
                        "=" -> "= — converts the expression in the display from $fromCode to $toCode and records it to History."
                        "()" -> "( ) — smart bracket: opens or closes parentheses at the cursor."
                        else -> activity.keyDescriptionFor(label)
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(this, tooltip)
                        else activity.dismissButtonPopup()
                    }
                }
                quickKeys.add(btn)
                rowLl.addView(btn)
            }
            keyGrid.addView(rowLl)
        }
        content.addView(keyGrid)

        root.addView(content)

        updatePairCards()
        applyTheme(activity.isDarkMode)
        updatePreviewAndRate()
        return root
    }

    override fun onResume() {
        super.onResume()
        updatePreviewAndRate()
    }

    private fun buildCurrencySelectorCard(
        ctx: Context,
        dp: (Float) -> Int,
        labelText: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11f), dp(6f), dp(11f), dp(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(ctx).apply {
                text = labelText
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.08f
            })
            addView(TextView(ctx).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1f) }
            })
            addView(TextView(ctx).apply {
                textSize = 11f
                maxLines = 1
            })
        }
    }

    private fun savePairPrefs(ctx: Context) {
        ctx.getSharedPreferences("mep_calc_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("ccy_from", fromCode)
            .putString("ccy_to", toCode)
            .apply()
    }

    private fun updatePairCards() {
        val all = CurrencyConverter.getSupportedCurrencies().associateBy { it.code }
        val fromInfo = all[fromCode]
        val toInfo = all[toCode]
        tvFromCode?.text = if (fromInfo?.symbol != null) "$fromCode  ${fromInfo.symbol}" else fromCode
        tvFromName?.text = fromInfo?.name ?: fromCode
        tvToCode?.text = if (toInfo?.symbol != null) "$toCode  ${toInfo.symbol}" else toCode
        tvToName?.text = toInfo?.name ?: toCode
    }

    fun updatePreviewAndRate() {
        val act = activity as? MainActivity
        val expr = act?.expressionText?.trim().orEmpty()
        val typedVal = expr.toDoubleOrNull()
        if (typedVal != null && typedVal != 0.0) {
            val converted = CurrencyConverter.convert(typedVal, fromCode, toCode)
            val convStr = if (converted.isNaN()) "—" else formatCompact(converted)
            tvRateStatus?.text = "${formatCompact(typedVal)} $fromCode ≈ $convStr $toCode • ${CurrencyConverter.lastProviderName}"
        } else {
            val unitRate = CurrencyConverter.convert(1.0, fromCode, toCode)
            val rateStr = if (unitRate.isNaN()) "—" else formatCompact(unitRate)
            tvRateStatus?.text = "1 $fromCode = $rateStr $toCode • ${CurrencyConverter.lastProviderName}"
        }
        tvCallInfo?.text = "currency_convert(${expr.ifBlank { "0" }}, \"$fromCode\", \"$toCode\")"
    }

    private fun formatCompact(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        return if (kotlin.math.abs(v) >= 1000.0 || (v != 0.0 && kotlin.math.abs(v) < 0.01)) {
            String.format(Locale.US, "%.4g", v)
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    fun applyTheme(dark: Boolean) {
        val density = resources.displayMetrics.density
        val primaryText = Color.parseColor(if (dark) "#E6EBF0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8B95A0" else "#666666")
        val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
        val cardBg = Color.parseColor(if (dark) "#2A2E32" else "#EAEAEA")
        val cardStroke = Color.parseColor(if (dark) "#34393F" else "#DCDCDC")
        val pillBg = Color.parseColor(if (dark) "#181A1C" else "#EFEFEF")
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        tvTitle?.setTextColor(primaryText)
        tvRateStatus?.setTextColor(accentColor)
        tvRateStatus?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(pillBg)
            setStroke(strokePx, cardStroke)
        }

        listOf(fromCard, toCard).forEach { card ->
            card?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(cardBg)
                setStroke(strokePx, cardStroke)
            }
        }
        tvFromLabel?.setTextColor(accentColor)
        tvToLabel?.setTextColor(accentColor)
        tvFromCode?.setTextColor(primaryText)
        tvToCode?.setTextColor(primaryText)
        tvFromName?.setTextColor(secondaryText)
        tvToName?.setTextColor(secondaryText)

        tvCallInfo?.setTextColor(secondaryText)
        tvCallInfo?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(pillBg)
            setStroke(strokePx, cardStroke)
        }

        btnSwap?.isDarkThemeMode = dark
        quickKeys.forEach { it.isDarkThemeMode = dark }
    }

    private fun openCurrencyPicker(isFrom: Boolean) {
        // Drop any previous overlay instantly: re-opening while a close is still
        // shrinking must not leave two stacked overlays behind.
        removePickerImmediately()
        val act = activity as? MainActivity ?: return
        val root = act.binding.rootFrame
        val dark = act.isDarkMode
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val cardBg = Color.parseColor(if (dark) "#222528" else "#FFFFFF")
        val itemBg = Color.parseColor(if (dark) "#2A2E32" else "#F3F3F3")
        val strokeColor = Color.parseColor(if (dark) "#34393F" else "#DCDCDC")
        val primaryText = Color.parseColor(if (dark) "#E6EBF0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8B95A0" else "#666666")
        val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        val frostBmp = Frost.frozenBackdrop(root, downscale = 6, radius = 8)
        val overlay = FrameLayout(act).apply {
            elevation = 40f * density
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissPicker() }
        }
        if (frostBmp != null) {
            overlay.addView(ImageView(act).apply {
                setImageBitmap(frostBmp)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0.92f
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        overlay.addView(View(act).apply {
            setBackgroundColor(Color.parseColor(if (dark) "#88101214" else "#77EAEAEA"))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val modal = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(cardBg)
                setStroke(dp(1.2f), accentColor)
            }
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        val title = TextView(act).apply {
            text = if (isFrom) "Select Source Currency (From)" else "Select Target Currency (To)"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        modal.addView(title)

        val searchBox = EditText(act).apply {
            hint = "Search code, name, or symbol (e.g. INR, Euro, ₹)…"
            setHintTextColor(secondaryText)
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(itemBg)
                setStroke(dp(1f), strokeColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        modal.addView(searchBox)

        val allCurrencies = CurrencyConverter.getSupportedCurrencies()
        var filtered = allCurrencies

        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val pickerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = filtered.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6f) }
                }
                val tvCode = TextView(parent.context).apply {
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(74f), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val tvName = TextView(parent.context).apply {
                    textSize = 13.5f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvSym = TextView(parent.context).apply {
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                row.addView(tvCode)
                row.addView(tvName)
                row.addView(tvSym)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = filtered[position]
                val row = holder.itemView as LinearLayout
                val tvC = row.getChildAt(0) as TextView
                val tvN = row.getChildAt(1) as TextView
                val tvS = row.getChildAt(2) as TextView
                val selectedCode = if (isFrom) fromCode else toCode
                val isSel = item.code == selectedCode

                row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(itemBg)
                    setStroke(dp(1f), if (isSel) accentColor else strokeColor)
                }
                tvC.text = item.code
                tvC.setTextColor(if (isSel) accentColor else primaryText)
                tvN.text = item.name
                tvN.setTextColor(secondaryText)
                tvS.text = item.symbol ?: ""
                tvS.setTextColor(accentColor)

                // Rows are animated in/out; a recycled row must start from a clean
                // slate or it can appear already faded.
                row.animate().cancel()
                row.alpha = 1f
                row.translationX = 0f
                row.scaleX = 1f
                row.scaleY = 1f

                row.setOnClickListener {
                    if (isFrom) fromCode = item.code else toCode = item.code
                    savePairPrefs(act)
                    updatePairCards()
                    updatePreviewAndRate()
                    dismissPicker()
                }
            }
        }
        rv.adapter = pickerAdapter
        modal.addView(rv)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                filtered = if (q.isEmpty()) allCurrencies else allCurrencies.filter {
                    it.code.lowercase(Locale.US).contains(q) ||
                        it.name.lowercase(Locale.US).contains(q) ||
                        (it.symbol?.lowercase(Locale.US)?.contains(q) == true)
                }
                pickerAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val modalW = minOf(root.width - dp(32f), dp(420f)).coerceAtLeast(dp(240f))
        val modalH = (root.height * 0.72f).toInt().coerceAtLeast(dp(340f))
        overlay.addView(modal, FrameLayout.LayoutParams(modalW, modalH, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pickerOverlay = overlay
        pickerModal = modal
        pickerList = rv
        pickerClosing = false

        // Entrance. The backdrop fades while the card itself springs up from
        // 84% scale on the app's damped-spring curve, then the rows cascade in
        // below it — so opening the picker reads as an expansion rather than a
        // frame swap.
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180L)
            .setInterpolator(DecelerateInterpolator()).start()
        modal.scaleX = 0.84f
        modal.scaleY = 0.84f
        modal.alpha = 0f
        modal.translationY = dp(14f).toFloat()
        pickerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val step = springStep(f)
                val scale = 0.84f + 0.16f * step
                modal.scaleX = scale
                modal.scaleY = scale
                // Reach full opacity early; the visible motion after that is the
                // spring overshoot, not a fading card.
                modal.alpha = (f * 3f).coerceAtMost(1f)
                modal.translationY = dp(14f) * (1f - step)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation === pickerAnimator) pickerAnimator = null
                    // A close cancels this mid-flight; leave the card where the
                    // shrink animation expects to find it instead of snapping.
                    if (pickerClosing) return
                    modal.scaleX = 1f
                    modal.scaleY = 1f
                    modal.alpha = 1f
                    modal.translationY = 0f
                }
            })
            start()
        }

        rv.post {
            if (!isAdded || pickerOverlay !== overlay) return@post
            val visible = minOf(rv.childCount, 14)
            for (i in 0 until visible) {
                val row = rv.getChildAt(i) ?: continue
                row.alpha = 0f
                row.translationX = dp(18f).toFloat()
                row.scaleX = 0.97f
                row.scaleY = 0.97f
                row.animate()
                    .alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(120L + i * 24L)
                    .setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    fun handleBackPressed(): Boolean {
        if (pickerOverlay != null) {
            dismissPicker()
            return true
        }
        return false
    }

    /**
     * Underdamped spring step, 0 -> 1 with ~34% overshoot: the same curve the
     * keypad/tab physics use (e^-7.5t · sin 22t), rewritten as a settle shape
     * so it can drive scale directly.
     */
    private fun springStep(f: Float): Float {
        val t = f.toDouble() * 0.48
        val decay = Math.exp(-7.5 * t)
        return (1.0 - decay * (Math.cos(22.0 * t) + (7.5 / 22.0) * Math.sin(22.0 * t))).toFloat()
    }

    private fun hidePickerKeyboard(overlay: View) {
        (overlay.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(overlay.windowToken, 0)
    }

    /**
     * Closes the picker with the reverse spring: rows peel away, the card
     * shrinks back and the backdrop fades. Repeat taps while the shrink is
     * running are ignored (the close is not queued), and the overlay is removed
     * from the hierarchy only when the animation ends.
     */
    private fun dismissPicker() {
        val overlay = pickerOverlay ?: return
        if (pickerClosing) return
        hidePickerKeyboard(overlay)
        val modal = pickerModal
        if (modal == null || !overlay.isAttachedToWindow || view == null) {
            removePickerImmediately()
            return
        }
        pickerClosing = true
        pickerAnimator?.cancel()
        val density = resources.displayMetrics.density
        pickerList?.let { list ->
            for (i in 0 until minOf(list.childCount, 14)) {
                val row = list.getChildAt(i) ?: continue
                row.animate()
                    .alpha(0f)
                    .translationX(16f * density)
                    .setStartDelay((i * 9L).coerceAtMost(110L))
                    .setDuration(150L)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            }
        }
        pickerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val step = springStep(f)
                val scale = 1f - 0.14f * step
                modal.scaleX = scale
                modal.scaleY = scale
                modal.translationY = 6f * density * step
                overlay.alpha = 1f - f * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation === pickerAnimator) pickerAnimator = null
                    removePickerImmediately()
                }
            })
            start()
        }
    }

    /** Drops the picker without animating. Safe to call at any time, repeatedly. */
    private fun removePickerImmediately() {
        pickerAnimator?.cancel()
        pickerAnimator = null
        pickerOverlay?.let { overlay ->
            hidePickerKeyboard(overlay)
            overlay.animate().cancel()
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
        pickerModal?.let { modal ->
            modal.animate().cancel()
            modal.scaleX = 1f
            modal.scaleY = 1f
            modal.alpha = 1f
            modal.translationY = 0f
        }
        pickerList = null
        pickerModal = null
        pickerOverlay = null
        pickerClosing = false
    }

    fun springSettle(fromDirection: Int, distance: Int = 1) {
        val v = mainContent ?: view ?: return
        if (v.width == 0 || v.height == 0) {
            v.post {
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
        val slidePx = (14f + (d - 1f) * 14f) * density * fromDirection
        val scaleAmp = 0.025f + (d - 1f) * 0.025f
        val settleDuration = 440L + (clampedDist - 1) * 50L

        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = settleDuration
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val springX = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                v.translationX = slidePx * springX * 0.45f
                v.scaleX = 1f + scaleAmp * pulse
                v.scaleY = 1f + scaleAmp * pulse
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.translationX = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        val view = view ?: return
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val speed = maxRadius / revealDurationMs
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val vx = loc[0] + view.width / 2f
        val vy = loc[1] + view.height / 2f
        val dx = vx - cx
        val dy = vy - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = 0.015f + 0.035f * proximity
        val delay = (dist / speed).toLong().coerceAtMost(revealDurationMs - 40)

        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                view.scaleX = (1.0 + bump * pulse).toFloat()
                view.scaleY = (1.0 + bump * pulse).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    override fun onDestroyView() {
        // No animation here: the fragment view is going away, and a close that is
        // already mid-shrink would otherwise leave the overlay attached to the
        // activity's root frame.
        removePickerImmediately()
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        super.onDestroyView()
    }
}
