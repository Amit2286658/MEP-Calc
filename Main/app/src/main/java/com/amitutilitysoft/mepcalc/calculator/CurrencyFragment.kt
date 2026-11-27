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
import android.widget.ScrollView
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

    private var mainContent: LinearLayout? = null
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

    private var fromYearCard: LinearLayout? = null
    private var toYearCard: LinearLayout? = null
    private var tvFromYear: TextView? = null
    private var tvToYear: TextView? = null
    private var tvYearArrow: TextView? = null

    private var resultsCard: LinearLayout? = null
    private var tvRealHeadline: TextView? = null
    private var tvRealCaption: TextView? = null
    private var tvInflationDetail: TextView? = null
    private var tvDataStatus: TextView? = null

    private var btnKeypadToggle: NeumorphicKeyView? = null
    private var keypadOverlay: FrameLayout? = null
    private val keypadAnimators = mutableListOf<ValueAnimator>()

    private var btnInsert: NeumorphicKeyView? = null
    private var tvInsertLabel: TextView? = null

    /**
     * Span of the `convert_real(...)` call placed by the Insert button. While the span
     * still points at a complete call, changing the From/To currency or year pickers
     * rewrites its arguments in place; the amount the user types is preserved.
     */
    private var insertedRealRange: IntRange? = null
    private var insertedRealSelectionKey: String? = null
    private var syncingInsertedCall = false

    private var fromCode: String = "USD"
    private var toCode: String = "INR"

    private var fromYear: Int = 2015
    private var toYear: Int = 2025

    /** Guards against stale async CPI/engine results after a fast pair or year change. */
    private var realTermsGeneration = 0
    private var lastRealTermsKey: String? = null

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

        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val defaultTo = (thisYear - 1).coerceAtLeast(1961)
        val defaultFrom = (defaultTo - 10).coerceAtLeast(1961)
        fromYear = prefs.getInt("ccy_year_from", defaultFrom).coerceIn(1960, thisYear)
        toYear = prefs.getInt("ccy_year_to", defaultTo).coerceIn(1960, thisYear)
        if (toYear < fromYear) toYear = fromYear

        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        mainContent = content

        // 1. Header: live rate/provider pill on the LEFT, compact keypad dropdown on the RIGHT.
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
        tvRateStatus = TextView(ctx).apply {
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                text = "Refreshing rates…"
                CurrencyConverter.refreshRatesAsync { _, _ ->
                    view?.post { updatePreviewAndRate() }
                }
            }
        }
        btnKeypadToggle = NeumorphicKeyView(ctx).apply {
            text = "\u2328"
            textSize = 15f
            cornerRadiusDp = 10.5f
            shadowPadDp = 2.2f
            isAccentKey = false
            isDarkThemeMode = activity.isDarkMode
            contentDescription = "Show numeric keypad"
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(32f)).apply {
                marginStart = dp(6f)
            }
            setOnClickListener {
                activity.displaySpringPulse(this, 0.08f)
                toggleKeypadDropdown(this)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(this, "Keypad — drops down the numeric pad for the conversion amount.")
                else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(tvRateStatus)
        headerRow.addView(btnKeypadToggle)
        content.addView(headerRow)

        // 2. From <-> To selector row (unchanged).
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
            text = "\u21C4"
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
                syncInsertedRealCall()
                updatePairCards()
                updatePreviewAndRate()
                scheduleRealTermsRefresh(force = true)
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

        // 3. Year range selectors: real-terms appreciation/depreciation window.
        val yearRow = LinearLayout(ctx).apply {
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
        fromYearCard = buildYearSelectorCard(ctx, dp, "FROM YEAR") { openYearPicker(isFrom = true) }.also { card ->
            tvFromYear = card.getChildAt(1) as TextView
        }
        toYearCard = buildYearSelectorCard(ctx, dp, "TO YEAR") { openYearPicker(isFrom = false) }.also { card ->
            tvToYear = card.getChildAt(1) as TextView
        }
        tvYearArrow = TextView(ctx).apply {
            text = "\u2192"
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        yearRow.addView(fromYearCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        yearRow.addView(tvYearArrow)
        yearRow.addView(toYearCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(yearRow)

        // 4. Real-terms result card.
        resultsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13f), dp(10f), dp(13f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
                bottomMargin = dp(6f)
            }
        }
        tvRealHeadline = TextView(ctx).apply {
            text = "—"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        tvRealCaption = TextView(ctx).apply {
            text = "Real terms vs the selected currency"
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvInflationDetail = TextView(ctx).apply {
            text = ""
            textSize = 11f
            maxLines = 2
            setPadding(0, dp(3f), 0, 0)
        }
        tvDataStatus = TextView(ctx).apply {
            text = ""
            textSize = 10f
            maxLines = 2
            setPadding(0, dp(3f), 0, 0)
        }
        resultsCard?.addView(tvRealHeadline)
        resultsCard?.addView(tvRealCaption)
        resultsCard?.addView(tvInflationDetail)
        resultsCard?.addView(tvDataStatus)
        resultsCard?.let { content.addView(it) }

        // 5. Small info strip: the exact engine conversion call the `=` key makes.
        tvCallInfo = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            }
        }
        content.addView(tvCallInfo)

        // Absorb the space the old always-on keypad used to fill; the pad now lives
        // behind the header dropdown.
        content.addView(View(ctx), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // 6. Insert: drop a ready-made convert_real(...) call at the cursor. It sits
        // below everything else and uses the accent fill so it reads as the tab's
        // primary action rather than another selector.
        tvInsertLabel = TextView(ctx).apply {
            text = "INFLATION-ADJUSTED CONVERSION"
            textSize = 9.5f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(8f), 0, dp(8f), dp(4f))
        }
        content.addView(tvInsertLabel)

        btnInsert = NeumorphicKeyView(ctx).apply {
            text = "Insert convert_real(...)"
            textSize = 14f
            keyRole = KeyRole.PRIMARY_EQUALS
            cornerRadiusDp = 12f
            shadowPadDp = 3f
            isDarkThemeMode = activity.isDarkMode
            contentDescription = "Insert an inflation-adjusted conversion at the cursor"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48f)
            ).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                activity.displaySpringPulse(this, 0.07f)
                insertRealConversion()
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this,
                    "Insert — places convert_real(amount, from, fromYear, to, toYear) at the cursor " +
                        "with your selected currencies and years filled in. Changing a currency or " +
                        "year afterwards updates the inserted call automatically."
                ) else activity.dismissButtonPopup()
            }
        }
        content.addView(btnInsert)

        // Scroll in compact viewports (phone landscape) so the results card is never clipped.
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scroll)

        updatePairCards()
        updateYearCards()
        applyTheme(activity.isDarkMode)
        updatePreviewAndRate()
        scheduleRealTermsRefresh(force = true)
        return root
    }

    override fun onResume() {
        super.onResume()
        updatePreviewAndRate()
        InflationProvider.refreshIfStaleAsync()
        // Data may have arrived while another tab was visible.
        if (lastRealTermsKey != null) scheduleRealTermsRefresh(force = true)
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

    private fun buildYearSelectorCard(
        ctx: Context,
        dp: (Float) -> Int,
        labelText: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(ctx).apply {
                text = labelText
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.08f
            })
            addView(TextView(ctx).apply {
                text = "—"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1f) }
            })
        }
    }

    private fun saveYearPrefs(ctx: Context) {
        ctx.getSharedPreferences("mep_calc_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("ccy_year_from", fromYear)
            .putInt("ccy_year_to", toYear)
            .apply()
    }

    private fun updateYearCards() {
        tvFromYear?.text = fromYear.toString()
        tvToYear?.text = toYear.toString()
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
        // Ordinary typing can shift the tracked call; re-anchor it (without rewriting
        // its arguments) so the next selector change still finds it.
        reanchorInsertedRealCall()
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
        tvCallInfo?.text = if (containsEngineCurrencyCall(expr)) {
            expr.ifBlank { "0" }
        } else {
            "currency_convert(${expr.ifBlank { "0" }}, \"$fromCode\", \"$toCode\")"
        }
    }

    private fun formatCompact(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        return if (kotlin.math.abs(v) >= 1000.0 || (v != 0.0 && kotlin.math.abs(v) < 0.01)) {
            String.format(Locale.US, "%.4g", v)
        } else {
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    // =========================================================================
    // Insert: ready-made convert_real(...) call with selection-aware arguments
    // =========================================================================

    private fun currentSelectionKey(): String = "$fromCode|$fromYear|$toCode|$toYear"

    /** True when the expression already contains an engine currency/inflation call. */
    private fun containsEngineCurrencyCall(expr: String): Boolean {
        val names = listOf(
            "convert_real(", "convert_fx(", "currency_convert(", "fx_rate(",
            "real_value(", "inflation_pct(", "real_change_pct("
        )
        return names.any { expr.contains(it, ignoreCase = true) }
    }

    /** Index of the `)` matching the `(` at [openIndex], or null if unbalanced. */
    private fun matchingParen(text: String, openIndex: Int): Int? {
        if (openIndex !in text.indices || text[openIndex] != '(') return null
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun insertRealConversion() {
        val act = activity as? MainActivity ?: return
        if (act.expressionText == "0.0" || act.expressionText == "0") {
            act.expressionText = ""
            act.cursorPos = 0
        }
        val p = act.cursorPos.coerceIn(0, act.expressionText.length)
        val before = act.expressionText.substring(0, p)
        val after = act.expressionText.substring(p)
        val prev = before.trimEnd().lastOrNull()
        val needsMul = prev != null && prev !in "+-×÷/*^,("
        val prefix = "convert_real("
        val call = prefix + ", \"$fromCode\", $fromYear, \"$toCode\", $toYear)"
        val insertion = (if (needsMul) "×" else "") + call

        act.expressionText = before + insertion + after
        val callStart = p + (if (needsMul) 1 else 0)
        act.cursorPos = callStart + prefix.length
        insertedRealRange = callStart until (callStart + call.length)
        insertedRealSelectionKey = currentSelectionKey()
        act.refreshPrimaryDisplay(pulse = true)
    }

    /**
     * Re-anchors [insertedRealRange] after ordinary typing shifted the text. If the
     * tracked span no longer starts a balanced call, the nearest remaining
     * `convert_real(...)` is adopted (there is normally only one).
     */
    private fun reanchorInsertedRealCall() {
        val act = activity as? MainActivity ?: return
        val tracked = insertedRealRange ?: return
        val text = act.expressionText
        val prefix = "convert_real("

        fun closeOf(start: Int): Int? {
            if (start < 0 || start + prefix.length > text.length) return null
            if (!text.startsWith(prefix, start)) return null
            return matchingParen(text, start + prefix.length - 1)
        }

        val trackedClose = closeOf(tracked.first)
        if (trackedClose != null) {
            insertedRealRange = tracked.first until (trackedClose + 1)
            return
        }

        var search = text.indexOf(prefix)
        var bestStart = -1
        var bestDist = Int.MAX_VALUE
        while (search >= 0) {
            val close = closeOf(search)
            if (close != null) {
                val dist = kotlin.math.abs(search - tracked.first)
                if (dist < bestDist) {
                    bestDist = dist
                    bestStart = search
                }
            }
            search = text.indexOf(prefix, search + 1)
        }
        if (bestStart < 0) {
            insertedRealRange = null
            insertedRealSelectionKey = null
            return
        }
        insertedRealRange = bestStart until (closeOf(bestStart)!! + 1)
    }

    /**
     * Rewrites the tracked call's currency/year arguments when the From/To selectors
     * change, keeping whatever amount the user has typed between the parentheses.
     */
    private fun syncInsertedRealCall() {
        if (syncingInsertedCall) return
        val act = activity as? MainActivity ?: return
        reanchorInsertedRealCall()
        val range = insertedRealRange ?: return
        val key = currentSelectionKey()
        if (key == insertedRealSelectionKey) return

        val text = act.expressionText
        val prefix = "convert_real("
        val start = range.first
        val open = start + prefix.length
        val close = matchingParen(text, open - 1) ?: run {
            insertedRealRange = null
            return
        }

        // The amount is everything before the first comma at paren depth 1, so nested
        // calls and comma-taking functions in the amount survive untouched.
        var depth = 1
        var comma = -1
        var i = open
        while (i < close) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 1) {
                    comma = i
                    i = close
                }
            }
            i++
        }
        val amount = if (comma >= 0) text.substring(open, comma) else text.substring(open, close)
        val newCall = "convert_real($amount, \"$fromCode\", $fromYear, \"$toCode\", $toYear)"
        val oldLen = close + 1 - start
        val oldCursor = act.cursorPos

        act.expressionText = text.substring(0, start) + newCall + text.substring(close + 1)
        insertedRealRange = start until (start + newCall.length)
        insertedRealSelectionKey = key
        act.cursorPos = when {
            oldCursor <= start -> oldCursor
            oldCursor >= close + 1 -> oldCursor + (newCall.length - oldLen)
            oldCursor <= open -> oldCursor
            comma >= 0 && oldCursor <= comma -> oldCursor
            else -> start + prefix.length + amount.length
        }

        syncingInsertedCall = true
        try {
            act.refreshPrimaryDisplay(pulse = true)
        } finally {
            syncingInsertedCall = false
        }
    }

    // =========================================================================
    // Real-terms (inflation / relative PPP) panel
    // =========================================================================

    /** Recomputes the real-terms card when the currency pair or year window changes. */
    fun scheduleRealTermsRefresh(force: Boolean = false) {
        val key = "$fromCode|$toCode|$fromYear|$toYear"
        if (!force && key == lastRealTermsKey) return
        lastRealTermsKey = key
        val gen = ++realTermsGeneration

        if (fromYear >= toYear) {
            renderRealTerms(0.0, fromYear, fromYear, 0.0, 0.0)
            return
        }

        tvRealHeadline?.text = "…"
        tvRealCaption?.text = "Calculating real change…"
        tvInflationDetail?.text = ""
        tvDataStatus?.text = "${InflationProvider.SOURCE_LABEL} • fetching…"

        InflationProvider.ensureDataAsync(listOf(fromCode, toCode), fromYear, toYear) { ok ->
            view?.post {
                if (gen != realTermsGeneration || view == null) return@post
                if (ok) computeRealTerms(gen) else renderInflationUnavailable()
            }
        }
    }

    private fun computeRealTerms(gen: Int) {
        val range = InflationProvider.resolvedRange(listOf(fromCode, toCode), fromYear, toYear)
        if (range == null) {
            renderInflationUnavailable()
            return
        }
        val (ry0, ry1) = range
        if (ry1 <= ry0) {
            renderRealTerms(0.0, ry0, ry1, 0.0, 0.0)
            return
        }
        val infFrom = InflationProvider.inflationPercent(fromCode, ry0, ry1)
        val infTo = InflationProvider.inflationPercent(toCode, ry0, ry1)

        // Headline comes from the engine patch (`real_change_pct`), which calls the
        // `@cpi_ratio` extern for both economies. Fall back to the identical local
        // formula only when the patch is not linked yet (e.g. first launch offline).
        MathEngineManager.evaluateRealChangePct(fromCode, toCode, ry0, ry1) { pct ->
            view?.post {
                if (gen != realTermsGeneration || view == null) return@post
                val value = pct ?: InflationProvider.realChangePercent(fromCode, toCode, ry0, ry1)
                if (value.isNaN()) renderInflationUnavailable()
                else renderRealTerms(value, ry0, ry1, infFrom, infTo)
            }
        }
    }

    private fun renderRealTerms(
        pct: Double,
        fromY: Int,
        toY: Int,
        infFromPct: Double,
        infToPct: Double
    ) {
        val dark = (activity as? MainActivity)?.isDarkMode ?: false
        val positive = Color.parseColor(if (dark) "#13959B" else "#2E8B57")
        val negative = Color.parseColor(if (dark) "#EF7B72" else "#C75146")

        tvRealHeadline?.text = String.format(Locale.US, "%+.2f%%", pct)
        tvRealHeadline?.setTextColor(if (pct < -0.005) negative else positive)

        val verb = when {
            pct > 0.005 -> "appreciated"
            pct < -0.005 -> "depreciated"
            else -> "held steady"
        }
        tvRealCaption?.text = "$fromCode $verb vs $toCode in real terms"

        val period = if (fromY == toY) "$fromY" else "$fromY→$toY"
        tvInflationDetail?.text =
            "Inflation $period • $fromCode ${fmtPct(infFromPct)} • $toCode ${fmtPct(infToPct)}"
        tvDataStatus?.text = "${InflationProvider.SOURCE_LABEL} • relative PPP"
    }

    private fun renderInflationUnavailable() {
        val dark = (activity as? MainActivity)?.isDarkMode ?: false
        val secondary = Color.parseColor(if (dark) "#8B95A0" else "#666666")
        tvRealHeadline?.text = "—"
        tvRealHeadline?.setTextColor(secondary)
        tvRealCaption?.text = "Inflation data unavailable"
        tvInflationDetail?.text = "No CPI series for $fromCode/$toCode in this period."
        tvDataStatus?.text = "Connect to the internet and try again."
    }

    private fun fmtPct(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "—" else String.format(Locale.US, "%+.1f%%", v)

    /** Called after OTA patches finish loading so the headline can switch to the engine patch. */
    fun onPatchesReloaded() {
        lastRealTermsKey = null
        if (isAdded && view != null) scheduleRealTermsRefresh(force = true)
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

        tvRateStatus?.setTextColor(accentColor)
        tvRateStatus?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(pillBg)
            setStroke(strokePx, cardStroke)
        }

        listOf(fromCard, toCard, fromYearCard, toYearCard).forEach { card ->
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

        // Year selector labels + values (child 0 = label, child 1 = value).
        listOf(fromYearCard, toYearCard).forEach { card ->
            (card?.getChildAt(0) as? TextView)?.setTextColor(accentColor)
            (card?.getChildAt(1) as? TextView)?.setTextColor(primaryText)
        }
        tvYearArrow?.setTextColor(secondaryText)

        resultsCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(cardBg)
            setStroke(strokePx, cardStroke)
        }
        tvRealCaption?.setTextColor(primaryText)
        tvInflationDetail?.setTextColor(secondaryText)
        tvDataStatus?.setTextColor(secondaryText)

        tvCallInfo?.setTextColor(secondaryText)
        tvCallInfo?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(pillBg)
            setStroke(strokePx, cardStroke)
        }

        tvInsertLabel?.setTextColor(accentColor)
        btnInsert?.isDarkThemeMode = dark

        btnSwap?.isDarkThemeMode = dark
        btnKeypadToggle?.isDarkThemeMode = dark

        // Re-colour the real-terms headline (positive/negative palette depends on theme).
        if (lastRealTermsKey != null && isAdded) {
            scheduleRealTermsRefresh(force = true)
        }
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
                    syncInsertedRealCall()
                    updatePairCards()
                    updatePreviewAndRate()
                    scheduleRealTermsRefresh(force = true)
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
        // Landscape phones are only ~345dp tall; a fixed 72% would show roughly
        // three rows, so values-land raises the share (see res/values*/fractions).
        val heightFraction = resources.getFraction(R.fraction.currency_picker_height, 1, 1)
        val modalH = (root.height * heightFraction).toInt()
            .coerceIn(dp(280f), (root.height - dp(24f)).coerceAtLeast(dp(280f)))
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

    private fun openYearPicker(isFrom: Boolean) {
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

        modal.addView(TextView(act).apply {
            text = if (isFrom) "Real terms — From year" else "Real terms — To year"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        modal.addView(TextView(act).apply {
            text = if (isFrom) "Start of the inflation comparison window"
            else "End of the inflation comparison window"
            textSize = 11.5f
            setTextColor(secondaryText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2f)
                bottomMargin = dp(10f)
            }
        })

        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = (thisYear downTo 1960).toList()
        val selected = if (isFrom) fromYear else toYear

        val rv = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = years.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6f) }
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val year = years[position]
                val tv = holder.itemView as TextView
                val isSel = year == selected
                tv.text = if (isSel) "$year   ✓" else year.toString()
                tv.setTypeface(tv.typeface, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                tv.setTextColor(if (isSel) accentColor else primaryText)
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(itemBg)
                    setStroke(dp(1f), if (isSel) accentColor else strokeColor)
                }
                tv.setOnClickListener {
                    onYearSelected(isFrom, year)
                    dismissPicker()
                }
            }
        }
        modal.addView(rv)

        val modalW = minOf(root.width - dp(32f), dp(340f)).coerceAtLeast(dp(240f))
        val modalH = (root.height * 0.62f).toInt()
            .coerceIn(dp(260f), (root.height - dp(24f)).coerceAtLeast(dp(260f)))
        overlay.addView(modal, FrameLayout.LayoutParams(modalW, modalH, Gravity.CENTER))
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        pickerOverlay = overlay
        pickerModal = modal
        pickerList = rv
        pickerClosing = false

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
                modal.alpha = (f * 3f).coerceAtMost(1f)
                modal.translationY = dp(14f) * (1f - step)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation === pickerAnimator) pickerAnimator = null
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
                    .setStartDelay(120L + i * 20L)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun onYearSelected(isFrom: Boolean, year: Int) {
        if (isFrom) {
            fromYear = year
            if (toYear < fromYear) toYear = fromYear
        } else {
            toYear = year
            if (fromYear > toYear) fromYear = toYear
        }
        context?.let { saveYearPrefs(it) }
        syncInsertedRealCall()
        updateYearCards()
        updatePreviewAndRate()
        scheduleRealTermsRefresh(force = true)
    }

    // =========================================================================
    // Keypad dropdown (the always-on 4x4 pad moved behind the header button)
    // =========================================================================

    private fun toggleKeypadDropdown(anchor: View) {
        if (keypadOverlay != null) dismissKeypadDropdown() else showKeypadDropdown(anchor)
    }

    private fun showKeypadDropdown(anchor: View) {
        val act = activity as? MainActivity ?: return
        val root = act.binding.rootFrame
        act.dismissButtonPopup()
        removeKeypadImmediately()

        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val dark = act.isDarkMode
        val panelBg = Color.parseColor(if (dark) "#222528" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(panelBg)
                setStroke(dp(1.2f), strokeColor)
            }
            elevation = 12f * density
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            isClickable = true
            isFocusable = true
            visibility = View.INVISIBLE
        }

        fun key(label: String, role: KeyRole, widthDp: Float): NeumorphicKeyView {
            return NeumorphicKeyView(act).apply {
                text = label
                textSize = if (label.length > 1) 13f else 15f
                keyRole = role
                cornerRadiusDp = 8.5f
                shadowPadDp = 2.0f
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(40f)).apply {
                    marginStart = dp(2f)
                    marginEnd = dp(2f)
                    topMargin = dp(2f)
                    bottomMargin = dp(2f)
                }
                setOnClickListener {
                    act.displaySpringPulse(this, 0.07f)
                    when (label) {
                        "=" -> {
                            val expr = act.expressionText.trim().ifBlank { "0" }
                            if (containsEngineCurrencyCall(expr)) {
                                // A ready-made conversion (e.g. convert_real(...)) must be
                                // evaluated as-is; wrapping it in currency_convert again
                                // would convert an already-converted amount.
                                act.onKeyClicked("=")
                            } else {
                                act.evaluateCurrencyConversion(expr, fromCode, toCode)
                            }
                            dismissKeypadDropdown()
                        }
                        "()" -> act.onSmartBracketClicked()
                        else -> act.onKeyClicked(label)
                    }
                    updatePreviewAndRate()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) act.showButtonPopup(this, when (label) {
                        "=" -> if (containsEngineCurrencyCall(act.expressionText)) {
                            "= — evaluates the full expression (including convert_real) and records it to History."
                        } else {
                            "= — converts the expression from $fromCode to $toCode and records it to History."
                        }
                        "()" -> "( ) — smart bracket: opens or closes parentheses at the cursor."
                        else -> act.keyDescriptionFor(label)
                    }) else act.dismissButtonPopup()
                }
            }
        }

        val rows = listOf(
            listOf("7", "8", "9", "\u232B"),
            listOf("4", "5", "6", "()"),
            listOf("1", "2", "3", ","),
            listOf("0", ".", "AC", "=")
        )
        for (row in rows) {
            val rowLl = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (label in row) {
                val role = when (label) {
                    "=" -> KeyRole.PRIMARY_EQUALS
                    "AC", "\u232B", "()" -> KeyRole.UTILITY
                    else -> KeyRole.DIGIT
                }
                rowLl.addView(key(label, role, 50f))
            }
            panel.addView(rowLl)
        }

        val overlay = FrameLayout(act).apply {
            elevation = 48f * density
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissKeypadDropdown() }
        }
        // Frost the whole window (not just this fragment) with the display kept sharp,
        // matching the Function Catalog's mini-keypad backdrop.
        val frostView = Frost.frostedBackdropWithDisplayCutout(act, root, dp)
        if (frostView != null) {
            overlay.addView(frostView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        keypadOverlay = overlay
        btnKeypadToggle?.isAccentKey = true

        fun positionAndAnimate() {
            if (keypadOverlay !== overlay) return
            val rootLoc = IntArray(2)
            root.getLocationInWindow(rootLoc)
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val layerPad = dp(10f)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (root.width - 2 * layerPad).coerceAtLeast(1),
                    View.MeasureSpec.AT_MOST
                ),
                View.MeasureSpec.makeMeasureSpec(
                    (root.height - 2 * layerPad).coerceAtLeast(1),
                    View.MeasureSpec.AT_MOST
                )
            )
            val bw = panel.measuredWidth
            val bh = panel.measuredHeight
            val anchorRight = anchorLoc[0] - rootLoc[0] + anchor.width
            val anchorTop = anchorLoc[1] - rootLoc[1]
            val anchorBottom = anchorTop + anchor.height
            val showBelow = anchorBottom + bh + layerPad <= root.height - layerPad
            val rawY = if (showBelow) anchorBottom + dp(4f) else anchorTop - bh - dp(4f)
            val maxTop = (root.height - bh - layerPad).coerceAtLeast(layerPad)
            val y = rawY.coerceIn(layerPad, maxTop)
            val maxLeft = (root.width - bw - layerPad).coerceAtLeast(layerPad)
            val x = (anchorRight - bw).coerceIn(layerPad, maxLeft)
            (panel.layoutParams as FrameLayout.LayoutParams).apply {
                width = bw
                leftMargin = x
                topMargin = y
            }
            panel.requestLayout()
            panel.visibility = View.VISIBLE
            panel.pivotX = bw.toFloat()
            panel.pivotY = if (showBelow) 0f else bh.toFloat()
            panel.translationY = if (showBelow) -10f * density else 10f * density
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    val decay = Math.exp(-4.0 * f)
                    val scale = (1.0 - 0.42 * decay * Math.cos(f * 3.0 * Math.PI)).toFloat()
                    panel.scaleX = scale
                    panel.scaleY = scale
                    panel.alpha = (f / 0.22f).coerceIn(0f, 1f)
                    panel.translationY =
                        (if (showBelow) -10f * density else 10f * density) * (1f - f)
                    frostView?.alpha = 0.92f * (f / 0.3f).coerceIn(0f, 1f)
                }
                start()
                keypadAnimators.add(this)
            }
        }
        if (root.width > 0 && anchor.width > 0) positionAndAnimate() else overlay.post { positionAndAnimate() }
    }

    private fun dismissKeypadDropdown() {
        keypadAnimators.forEach { it.cancel() }
        keypadAnimators.clear()
        btnKeypadToggle?.isAccentKey = false
        val overlay = keypadOverlay ?: return
        keypadOverlay = null
        for (i in 0 until overlay.childCount - 1) {
            overlay.getChildAt(i).animate().alpha(0f).setDuration(170L).start()
        }
        val panel = overlay.getChildAt(overlay.childCount - 1)
        if (panel != null) {
            panel.animate()
                .scaleX(0.62f).scaleY(0.62f).alpha(0f)
                .setDuration(170L)
                .setInterpolator(AccelerateInterpolator(0.8f))
                .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }
                .start()
        } else {
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
    }

    private fun removeKeypadImmediately() {
        keypadAnimators.forEach { it.cancel() }
        keypadAnimators.clear()
        btnKeypadToggle?.isAccentKey = false
        keypadOverlay?.let { overlay ->
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
        keypadOverlay = null
    }

    fun handleBackPressed(): Boolean {
        if (keypadOverlay != null) {
            dismissKeypadDropdown()
            return true
        }
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
        removeKeypadImmediately()
        insertedRealRange = null
        insertedRealSelectionKey = null
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        super.onDestroyView()
    }
}
