package com.ecalc

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
 * Tab 6 (7th tab): Full-Engine Currency Converter (`From` -> `To`).
 *
 * Exposes all 55+ currencies supported by `CurrencyConverter` and the C11 Math Engine's
 * `@currency_convert` / `into` patch matrix, bridging the gap where the Functions catalog
 * only lists 6 `real -> real` currency helpers.
 */
class CurrencyFragment : Fragment() {

    private var rootContainer: FrameLayout? = null
    private var mainContent: LinearLayout? = null
    private var tvTitle: TextView? = null
    private var tvRateStatus: TextView? = null

    private var fromCard: LinearLayout? = null
    private var tvFromLabel: TextView? = null
    private var tvFromCode: TextView? = null
    private var tvFromName: TextView? = null

    private var btnSwap: NeumorphicKeyView? = null

    private var toCard: LinearLayout? = null
    private var tvToLabel: TextView? = null
    private var tvToCode: TextView? = null
    private var tvToName: TextView? = null

    private var exprCard: LinearLayout? = null
    private var tvExpr: TextView? = null
    private var tvResult: TextView? = null

    private val quickKeys = mutableListOf<NeumorphicKeyView>()
    private var btnEquals: NeumorphicKeyView? = null

    private var pickerOverlay: FrameLayout? = null
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    private var fromCode: String = "USD"
    private var toCode: String = "INR"
    private var amountBuffer: String = ""

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
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
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
            ).apply { bottomMargin = dp(8f) }
        }
        tvTitle = TextView(ctx).apply {
            text = "Currency"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvRateStatus = TextView(ctx).apply {
            textSize = 11.5f
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
            ).apply { bottomMargin = dp(8f) }
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

        // 3. Expression Display Card (pseudo convert_fx(from, to, amount))
        exprCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        tvExpr = TextView(ctx).apply {
            textSize = 14.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            maxLines = 2
        }
        tvResult = TextView(ctx).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
        }
        exprCard!!.addView(tvExpr!!)
        exprCard!!.addView(tvResult!!)
        content.addView(exprCard!!)

        // 4. Micro Keypad: insert helpers into the main expression text
        val keyGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        }
        quickKeys.clear()
        val microLabels = listOf("(", ")", ",", "insert")
        for (label in microLabels) {
            val role = if (label == "insert") KeyRole.PRIMARY_EQUALS else KeyRole.UTILITY
            val btn = NeumorphicKeyView(ctx).apply {
                text = label
                textSize = if (label == "insert") 13f else 17f
                keyRole = role
                cornerRadiusDp = 10f
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                    setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
                }
                setOnClickListener {
                    when (label) {
                        "insert" -> {
                            val a = activity
                            val current = if (a.currentPrimaryInput == "0.0") a.exprStatement else a.exprStatement + a.currentPrimaryInput
                            val amount = current.trim().ifBlank { "0" }
                            val formula = "convert_fx($fromCode, $toCode, $amount)"
                            a.exprStatement = ""
                            a.currentPrimaryInput = formula
                            a.binding.tvPrimaryDisplay.text = formula
                            a.displaySpringPulse(a.binding.tvPrimaryDisplay, 0.05f)
                        }
                        else -> {
                            val a = activity
                            if (a.currentPrimaryInput == "0.0" || a.currentPrimaryInput.isBlank()) {
                                a.currentPrimaryInput = label
                            } else {
                                a.currentPrimaryInput += label
                            }
                            a.binding.tvPrimaryDisplay.text =
                                if (a.exprStatement.isBlank()) a.currentPrimaryInput else a.exprStatement + a.currentPrimaryInput
                        }
                    }
                    activity.displaySpringPulse(this, 0.06f)
                }
                if (label == "insert") {
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Insert - wraps the current main expression into convert_fx(from, to, amount) using the selected currencies and records nothing until you evaluate."
                        ) else activity.dismissButtonPopup()
                    }
                }
            }
            quickKeys.add(btn)
            keyGrid.addView(btn)
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
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(ctx).apply {
                text = labelText
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.08f
            })
            addView(TextView(ctx).apply {
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2f) }
            })
            addView(TextView(ctx).apply {
                textSize = 11.5f
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

    private fun activeAmountExpression(): String {
        return amountBuffer.trim().ifBlank { "1" }
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
        val unitRate = CurrencyConverter.convert(1.0, fromCode, toCode)
        val rateStr = if (unitRate.isNaN()) "—" else formatCompact(unitRate)
        tvRateStatus?.text = "1 $fromCode = $rateStr $toCode • ${CurrencyConverter.lastProviderName}"

        val act = activity as? MainActivity
        val current = if (act == null) "" else if (act.currentPrimaryInput == "0.0") act.exprStatement else act.exprStatement + act.currentPrimaryInput
        val amt = current.trim().ifBlank { "1" }
        val numericAmt = amt.toDoubleOrNull()
        tvExpr?.text = "convert_fx($fromCode, $toCode, $amt)"
        if (numericAmt != null && !unitRate.isNaN()) {
            val converted = CurrencyConverter.convert(numericAmt, fromCode, toCode)
            tvResult?.text = "= ${formatCompact(converted)} $toCode"
        } else {
            tvResult?.text = "Tap insert to push into expression"
        }
    }

    private fun runEngineConversion(activity: MainActivity) {
        val amountExpr = activeAmountExpression()
        activity.evaluateCurrencyConversion(amountExpr, fromCode, toCode)
    }

    private fun formatCompact(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        return if (kotlin.math.abs(v) >= 1000.0 || kotlin.math.abs(v) < 0.01) {
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

        exprCard!!.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(pillBg)
            setStroke(strokePx, cardStroke)
        }
        tvExpr?.setTextColor(secondaryText)
        tvResult?.setTextColor(primaryText)
        tvRateStatus?.setTextColor(secondaryText)

        btnSwap?.isDarkThemeMode = dark
        quickKeys.forEach { it.isDarkThemeMode = dark }
    }

    private fun openCurrencyPicker(isFrom: Boolean) {
        dismissPicker()
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
    }

    fun handleBackPressed(): Boolean {
        if (pickerOverlay != null) {
            dismissPicker()
            return true
        }
        return false
    }

    private fun dismissPicker() {
        pickerOverlay?.let { overlay ->
            (overlay.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(overlay.windowToken, 0)
            (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
        pickerOverlay = null
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
        dismissPicker()
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        super.onDestroyView()
    }
}
