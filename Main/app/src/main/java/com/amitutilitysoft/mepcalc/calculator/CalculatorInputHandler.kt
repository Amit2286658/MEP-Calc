package com.amitutilitysoft.mepcalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

fun MainActivity.onKeyClicked(label: String) {
    fun insertAtCursor(text: String) {
        val p = cursorPos.coerceIn(0, expressionText.length)
        expressionText = expressionText.substring(0, p) + text + expressionText.substring(p)
        cursorPos = p + text.length
    }

    fun clearIfDefaultZero() {
        if (expressionText == "0.0" || expressionText == "0") {
            expressionText = ""
            cursorPos = 0
        }
    }

    fun needsImplicitMul(before: String): Boolean {
        val last = before.trimEnd().lastOrNull() ?: return false
        return last !in "+-×÷/*^,( "
    }

    /**
     * The value M+/M− should absorb. Since `=` now keeps the expression, an
     * unchanged post-evaluation expression uses the answer line rather than its
     * trailing number token.
     */
    fun displayValueForMemory(): Double {
        if (lastEvaluatedExpression.isNotBlank() && expressionText.trim() == lastEvaluatedExpression) {
            lastAnswer.toDoubleOrNull()?.let { return it }
        }
        return expressionText.toDoubleOrNull()
            ?: Regex("[-+]?\\d+(?:\\.\\d+)?$").find(expressionText)?.value?.toDoubleOrNull()
            ?: 0.0
    }

    fun insertOperator(opDisplay: String) {
        if (expressionText == "0.0") {
            expressionText = if (opDisplay == "-") "-" else "0$opDisplay"
            cursorPos = expressionText.length
            return
        }
        val p = cursorPos.coerceIn(0, expressionText.length)
        if (p == 0 && expressionText.isEmpty()) {
            expressionText = if (opDisplay == "-") "-" else "0$opDisplay"
            cursorPos = expressionText.length
            return
        }
        val before = expressionText.substring(0, p)
        val after = expressionText.substring(p)
        val trimmedBefore = before.trimEnd()
        val prev = trimmedBefore.lastOrNull()
        val newBefore = if (prev != null && prev in "+-×÷/^" && !(opDisplay == "-" && prev in "×÷/^(")) {
            trimmedBefore.dropLast(1) + opDisplay
        } else {
            trimmedBefore + opDisplay
        }
        expressionText = newBefore + after
        cursorPos = newBefore.length
    }

    fun currentNumberTokenRange(): IntRange {
        val p = cursorPos.coerceIn(0, expressionText.length)
        var start = p
        while (start > 0 && (expressionText[start - 1].isDigit() || expressionText[start - 1] == '.')) {
            start--
        }
        var end = p
        while (end < expressionText.length && (expressionText[end].isDigit() || expressionText[end] == '.')) {
            end++
        }
        return start until end
    }

    when (label) {
        "AC" -> {
            expressionText = ""
            cursorPos = 0
            currentSecondaryFormula = "0.0"
            binding.tvSecondaryDisplay.text = "0.0"
            refreshPrimaryDisplay(pulse = true)
            displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
            return
        }
        "⌫" -> {
            if (expressionText == "0.0" || expressionText.isEmpty()) {
                expressionText = ""
                cursorPos = 0
            } else {
                val p = cursorPos.coerceIn(0, expressionText.length)
                if (p > 0) {
                    val before = expressionText.substring(0, p)
                    val after = expressionText.substring(p)
                    val newBefore = when {
                        before.endsWith("ans") -> before.dropLast(3)
                        before.endsWith("(") -> {
                            val withoutParen = before.dropLast(1)
                            val trailIdent = Regex("[a-zA-Z_][a-zA-Z0-9_]*$").find(withoutParen)
                            if (trailIdent != null) {
                                withoutParen.substring(0, trailIdent.range.first)
                            } else {
                                val heads = listOf("ⁿ√", "√", "log₂", "1/", "10^")
                                var res = withoutParen
                                for (h in heads) {
                                    if (res.endsWith(h)) {
                                        res = res.removeSuffix(h)
                                        break
                                    }
                                }
                                res
                            }
                        }
                        else -> before.dropLast(1)
                    }
                    expressionText = newBefore + after
                    cursorPos = newBefore.length
                }
            }
        }
        "M+" -> {
            val value = displayValueForMemory()
            memoryValue += value
            binding.tvMrValue.text = formatValue(memoryValue)
            displaySpringPulse(binding.tvMrValue, 0.05f)
            return
        }
        "M-" -> {
            val value = displayValueForMemory()
            memoryValue -= value
            binding.tvMrValue.text = formatValue(memoryValue)
            displaySpringPulse(binding.tvMrValue, 0.05f)
            return
        }
        "=" -> {
            evaluateCurrent()
            return
        }
        "Ans" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val before = expressionText.substring(0, p)
            val answer = lastAnswer.trim().ifEmpty { "0" }
            val numeric = answer.toDoubleOrNull()
            val token = when {
                numeric == null || numeric.isNaN() || numeric.isInfinite() -> "0"
                answer.startsWith("-") -> "($answer)"
                else -> answer
            }
            val ins = if (needsImplicitMul(before)) "×$token" else token
            insertAtCursor(ins)
        }
        "π", "e" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val before = expressionText.substring(0, p)
            val ins = if (needsImplicitMul(before)) "×$label" else label
            insertAtCursor(ins)
        }
        "rand" -> {
            clearIfDefaultZero()
            val r = formatValue(Math.random())
            val p = cursorPos.coerceIn(0, expressionText.length)
            val before = expressionText.substring(0, p)
            val ins = if (needsImplicitMul(before)) "×$r" else r
            insertAtCursor(ins)
        }
        "ⁿ√" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val before = expressionText.substring(0, p)
            val prev = before.trimEnd().lastOrNull()
            val hasActiveOperand = prev != null && (prev.isDigit() || prev in ")πe!%")
            insertAtCursor(if (hasActiveOperand) "√(" else "ⁿ√(")
        }
        "!" -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val prev = expressionText.substring(0, p).trimEnd().lastOrNull()
            if (prev != null && (prev.isDigit() || prev in ")πes")) {
                insertAtCursor("!")
            }
        }
        "x²" -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val prev = expressionText.substring(0, p).trimEnd().lastOrNull()
            if (prev != null && (prev.isDigit() || prev in ")πes!%")) {
                insertAtCursor("^2")
            }
        }
        "x³" -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val prev = expressionText.substring(0, p).trimEnd().lastOrNull()
            if (prev != null && (prev.isDigit() || prev in ")πes!%")) {
                insertAtCursor("^3")
            }
        }
        "xʸ" -> insertOperator("^")
        "10ˣ" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val ins = if (needsImplicitMul(expressionText.substring(0, p))) "×10^(" else "10^("
            insertAtCursor(ins)
        }
        "eˣ" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val ins = if (needsImplicitMul(expressionText.substring(0, p))) "×exp(" else "exp("
            insertAtCursor(ins)
        }
        "1/x" -> {
            clearIfDefaultZero()
            val p = cursorPos.coerceIn(0, expressionText.length)
            val ins = if (needsImplicitMul(expressionText.substring(0, p))) "×1/(" else "1/("
            insertAtCursor(ins)
        }
        "%" -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val prev = expressionText.substring(0, p).trimEnd().lastOrNull()
            if (prev != null && (prev.isDigit() || prev in ")πes") && prev != '%') {
                insertAtCursor("%")
            }
        }
        "+" -> insertOperator("+")
        "-" -> insertOperator("-")
        "x" -> insertOperator("×")
        "÷" -> insertOperator("÷")
        "^" -> insertOperator("^")
        "." -> {
            if (expressionText.isEmpty() || expressionText == "0.0" || expressionText == "0") {
                expressionText = "0."
                cursorPos = 2
            } else {
                val range = currentNumberTokenRange()
                val token = expressionText.substring(range.first, range.last + 1)
                if (!token.contains('.')) {
                    val ins = if (range.isEmpty()) "0." else "."
                    insertAtCursor(ins)
                }
            }
        }
        "EE" -> {
            if (expressionText.isEmpty() || expressionText == "0.0" || expressionText == "0") {
                expressionText = "1EE"
                cursorPos = 3
            } else {
                val p = cursorPos.coerceIn(0, expressionText.length)
                val before = expressionText.substring(0, p)
                val prev = before.lastOrNull()
                val ins = if (prev != null && prev.isDigit()) "EE" else "1EE"
                insertAtCursor(ins)
            }
        }
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> {
            if (expressionText.isEmpty() || expressionText == "0.0" || expressionText == "0") {
                expressionText = label
                cursorPos = label.length
            } else {
                insertAtCursor(label)
            }
        }
        "," -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val prev = expressionText.substring(0, p).trimEnd().lastOrNull()
            if (prev != null && prev !in "(,+-×÷/*^") {
                insertAtCursor(",")
            }
        }
        "(" -> {
            clearIfDefaultZero()
            insertAtCursor("(")
        }
        ")" -> {
            val p = cursorPos.coerceIn(0, expressionText.length)
            val before = expressionText.substring(0, p)
            val openParens = before.count { it == '(' } - before.count { it == ')' }
            if (openParens > 0) {
                val lastOpen = before.lastIndexOf('(')
                val prefix = if (lastOpen >= 0) before.substring(0, lastOpen) else ""
                val inside = if (lastOpen >= 0) before.substring(lastOpen + 1) else ""
                val twoArgHeads = listOf("npr", "ncr", "gcd", "lcm", "root", "ⁿ√")
                if (twoArgHeads.any { prefix.endsWith(it) } && !inside.contains(',')) {
                    insertAtCursor(",")
                } else {
                    insertAtCursor(")")
                }
            }
        }
        else -> {
            if (label.isNotEmpty() && (label[0].isLetterOrDigit() || label[0] == '√')) {
                clearIfDefaultZero()
                val p = cursorPos.coerceIn(0, expressionText.length)
                val before = expressionText.substring(0, p)
                val fn = when (label) {
                    "nPr" -> "npr"
                    "nCr" -> "ncr"
                    "log₂" -> "log2"
                    else -> label
                }
                val ins = if (needsImplicitMul(before)) "×$fn(" else "$fn("
                insertAtCursor(ins)
            }
        }
    }

    refreshPrimaryDisplay(pulse = true)
}

internal fun MainActivity.onSmartBracketClicked() {
    val p = cursorPos.coerceIn(0, expressionText.length)
    val before = expressionText.substring(0, p).trimEnd()
    val lastChar = before.lastOrNull()
    val balance = before.count { it == '(' } - before.count { it == ')' }
    val operandExpected = lastChar == null || lastChar in "(,+×÷/*^-"
    val shouldClose = balance > 0 && !operandExpected
    if (shouldClose) {
        onKeyClicked(")")
    } else {
        onKeyClicked("(")
    }
}

internal fun MainActivity.evaluateCurrent() {
    val fullDisplay = expressionText.trim()
    // "=" with nothing typed still runs the full engine round-trip on a zero expression so the
    // spring + result reveal always fire, instead of silently early-returning.
    val evalExpr = fullDisplay.ifBlank { "0" }
    val blankEval = fullDisplay.isBlank()

    binding.tvSecondaryDisplay.text = "$evalExpr ="

    displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
    displaySpringPulse(binding.tvSecondaryDisplay, 0.04f)

    MathEngineManager.evaluate(evalExpr, isDegreeMode, lastAnswer) { outcome ->
        runOnUiThread {
            if (outcome.ok) {
                lastAnswer = outcome.value
                lastEvaluatedExpression = fullDisplay
                val unitTag = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                val resultLine = "$evalExpr = ${outcome.value}$unitTag"
                if (!blankEval) {
                    calculationHistory.add(0, resultLine)
                    refreshHistoryUI()
                }
                currentSecondaryFormula = resultLine
                // Keep the expression on the primary display; the evaluated answer now
                // lives on the answer line (and in lastAnswer for the Ans key).
                refreshPrimaryDisplay(pulse = true)
                binding.tvSecondaryDisplay.text = "${outcome.value}$unitTag"
                displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
            } else {
                val msg = outcome.error ?: "Unknown error"
                val errLine = if (msg.length > 40) "⚠ ${msg.take(37)}…" else "Error: $msg"
                currentSecondaryFormula = errLine
                binding.tvSecondaryDisplay.text = errLine
                displaySpringPulse(binding.tvSecondaryDisplay, 0.04f)
            }
        }
    }
}

internal fun MainActivity.displaySpringPulse(view: View, bump: Float) {
    displayPulseAnimators.removeAll { (v, a) -> if (v === view) { a.cancel(); true } else false }
    if (view.width > 0) {
        view.pivotX = view.width.toFloat(); view.pivotY = view.height / 2f
    }
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480L; interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val f = a.animatedValue as Float
            val t = f * 0.48
            val decay = Math.exp(-7.5 * t)
            val pulse = decay * Math.sin(t * 22.0)
            view.scaleX = (1.0 + bump * pulse).toFloat()
            view.scaleY = (1.0 + bump * pulse).toFloat()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.scaleX = 1f; view.scaleY = 1f
            }
        })
        start()
    }
    displayPulseAnimators.add(view to anim)
}

internal fun MainActivity.formatValue(v: Double): String {
    if (v % 1.0 == 0.0) return "${v.toLong()}.0"
    var s = String.format("%.4f", v)
    val dotIdx = s.indexOf('.')
    if (dotIdx >= 0) {
        s = s.take(dotIdx + 1) + s.substring(dotIdx + 1).trimEnd('0')
        if (s.endsWith('.')) s = s.dropLast(1)
    }
    return s
}

fun MainActivity.refreshHistoryUI(fadeInPlaceholder: Boolean = false) {
    (fragmentAt(0) as? HistoryFragment)?.refresh(calculationHistory, isDarkMode, fadeInPlaceholder)
}
