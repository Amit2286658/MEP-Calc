package com.amitutilitysoft.mepcalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * IMF monthly CPI provider & host extern backing for the C11 Math Engine `[inflation]` patch.
 *
 * The engine patch (`assets/patches/inflation.math`) owns all of the real-terms maths:
 * cumulative inflation, CPI factors, relative-PPP appreciation/depreciation, and
 * inflation-adjusted cross-currency conversion. It calls the single host extern
 * `@cpi_ratio(currency, fromPeriod, toPeriod)`; this object supplies that ratio from
 * the IMF SDMX 3.0 Consumer Price Index dataset (`IMF.STA/CPI`, monthly index `IX`),
 * falling back per-economy to the World Bank annual series (`FP.CPI.TOTL.ZG`) only
 * when an economy is absent from the IMF monthly feed.
 *
 * Period encoding:
 * - 6-digit packed `YYYYMM` (`year * 100 + month`, e.g. `202610` = Oct 2026) for monthly lookups.
 * - 4-digit calendar year (`2015`, or sentinel `9999` for latest) is still accepted for
 *   backward compatibility and maps to December of that year (`year * 100 + 12`, snapped
 *   via `floorKey` to the newest available observation at or before that month).
 *
 * Design notes:
 * - **No hardcoded inflation.** Monthly CPI index levels come from the IMF SDMX 3.0 API and
 *   are cached on disk (`filesDir/inflation_imf_cpi_cache.json`) for offline use.
 * - **Synchronous extern, asynchronous fetch.** The JNI callback runs on the engine worker and
 *   must never block, so [cpiRatio] only reads the in-memory table and returns `-1.0` when the
 *   requested currency/period is not cached yet. The UI calls [ensureDataAsync] first and only
 *   evaluates the patch once the data is present.
 * - **Coherent index series per economy.** Different index types (`CPI` vs `HICP`) and
 *   frequencies (`M` vs `Q`) are parsed into separate buckets and ranked (`CPI/M` > `HICP/M` >
 *   `CPI/Q` > `HICP/Q`) so base years are never mixed within one economy's series.
 */
object InflationProvider {

    private const val TAG = "InflationProvider"
    private const val CACHE_FILE_NAME = "inflation_imf_cpi_cache.json"
    private const val CACHE_VERSION = 2
    private const val CACHE_TTL_MS = 3L * 24L * 60L * 60L * 1000L // 3 days
    private const val RETRY_BACKOFF_MS = 6L * 60L * 60L * 1000L // 6 hours after a failed fetch
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val READ_TIMEOUT_MS = 18_000L
    private const val FIRST_YEAR = 1960

    /** Host extern name consumed by `patches/inflation.math`. */
    const val EXTERN_NAME = "cpi_ratio"

    /** Default human-readable attribution shown in the Currency tab. */
    const val SOURCE_LABEL = "IMF CPI"

    private const val SOURCE_IMF = "IMF"
    private const val SOURCE_WB = "WB"

    /**
     * IMF SDMX 3.0 public REST endpoint for Consumer Price Index (`IMF.STA/CPI`).
     * Dimensions: `COUNTRY.INDEX_TYPE.COICOP_1999.TYPE_OF_TRANSFORMATION.FREQUENCY`
     * - `~` selects the latest published dataflow version.
     * - `CPI+HICP` covers national CPI and Eurostat HICP (e.g. Euro Area).
     * - `_T` = All items, `IX` = Index level, `M+Q` = Monthly (with Quarterly fallback).
     */
    private const val IMF_CPI_URL =
        "https://api.imf.org/external/sdmx/3.0/data/dataflow/IMF.STA/CPI/~/" +
            "%s.CPI+HICP._T.IX.M+Q?c[TIME_PERIOD]=ge:%d-M01"

    /** Per-economy fallback if an economy has no IMF monthly/quarterly CPI series. */
    private const val WORLDBANK_URL =
        "https://api.worldbank.org/v2/country/%s/indicator/FP.CPI.TOTL.ZG" +
            "?format=json&date=%d:%d&per_page=20000"

    private val MONTH_ABBR = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val fetchLock = Any()

    @Volatile
    private var appContext: Context? = null

    /** ISO 4217 currency -> IMF SDMX 3.0 ISO3 / aggregate economy code. */
    private val imfCountryByCurrency = mapOf(
        "USD" to "USA", "INR" to "IND", "EUR" to "G163", "GBP" to "GBR", "JPY" to "JPN",
        "CNY" to "CHN", "AUD" to "AUS", "CAD" to "CAN", "CHF" to "CHE", "SGD" to "SGP",
        "HKD" to "HKG", "NZD" to "NZL", "KRW" to "KOR", "SEK" to "SWE", "NOK" to "NOR",
        "DKK" to "DNK", "MXN" to "MEX", "BRL" to "BRA", "ZAR" to "ZAF", "RUB" to "RUS",
        "TRY" to "TUR", "AED" to "ARE", "SAR" to "SAU", "THB" to "THA", "MYR" to "MYS",
        "IDR" to "IDN", "PHP" to "PHL", "PLN" to "POL", "TWD" to "TWN", "VND" to "VNM",
        "EGP" to "EGY", "PKR" to "PAK", "BDT" to "BGD", "LKR" to "LKA", "NPR" to "NPL",
        "NGN" to "NGA", "KES" to "KEN", "GHS" to "GHA", "ARS" to "ARG", "CLP" to "CHL",
        "COP" to "COL", "PEN" to "PER", "ILS" to "ISR", "QAR" to "QAT", "KWD" to "KWT",
        "BHD" to "BHR", "OMR" to "OMN", "CZK" to "CZE", "HUF" to "HUN", "RON" to "ROU",
        "BGN" to "BGR", "ISK" to "ISL", "UAH" to "UKR", "KZT" to "KAZ", "MAD" to "MAR"
    )

    /** Fallback World Bank economy code keyed by the IMF code above. */
    private val wbCodeByImfCountry = mapOf(
        "USA" to "US", "IND" to "IN", "G163" to "EMU", "GBR" to "GB", "JPN" to "JP",
        "CHN" to "CN", "AUS" to "AU", "CAN" to "CA", "CHE" to "CH", "SGP" to "SG",
        "HKG" to "HK", "NZL" to "NZ", "KOR" to "KR", "SWE" to "SE", "NOR" to "NO",
        "DNK" to "DK", "MEX" to "MX", "BRA" to "BR", "ZAF" to "ZA", "RUS" to "RU",
        "TUR" to "TR", "ARE" to "AE", "SAU" to "SA", "THA" to "TH", "MYS" to "MY",
        "IDN" to "ID", "PHL" to "PH", "POL" to "PL", "TWN" to "TW", "VND" to "VN",
        "EGY" to "EG", "PAK" to "PK", "BGD" to "BD", "LKA" to "LK", "NPL" to "NP",
        "NGA" to "NG", "KEN" to "KE", "GHA" to "GH", "ARG" to "AR", "CHL" to "CL",
        "COL" to "CO", "PER" to "PE", "ISR" to "IL", "QAT" to "QA", "KWT" to "KW",
        "BHR" to "BH", "OMN" to "OM", "CZE" to "CZ", "HUN" to "HU", "ROU" to "RO",
        "BGR" to "BG", "ISL" to "IS", "UKR" to "UA", "KAZ" to "KZ", "MAR" to "MA"
    )

    private data class CountryCpi(
        /** CPI index level keyed by packed `YYYYMM` (`year * 100 + month`). */
        val monthlyIndex: TreeMap<Int, Double>,
        val fetchedAtMs: Long,
        val source: String = SOURCE_IMF
    )

    /** IMF economy code -> monthly CPI index series. */
    private val seriesByCountry = ConcurrentHashMap<String, CountryCpi>()

    /** Last failed fetch attempt per economy, to avoid hammering an unreachable API. */
    private val lastFailureMs = ConcurrentHashMap<String, Long>()

    // =========================================================================
    // Period helpers
    // =========================================================================

    fun packYearMonth(year: Int, month: Int): Int = year * 100 + month.coerceIn(1, 12)

    fun unpackYear(ym: Int): Int = if (ym >= 100_000) ym / 100 else ym

    fun unpackMonth(ym: Int): Int = if (ym >= 100_000) (ym % 100).coerceIn(1, 12) else 12

    fun monthAbbrev(month: Int): String = MONTH_ABBR[(month - 1).coerceIn(0, 11)]

    fun formatYearMonth(ym: Int): String {
        val y = unpackYear(ym)
        val m = unpackMonth(ym)
        return "${monthAbbrev(m)} $y"
    }

    /** Normalizes either a 4-digit year (`2015`, `9999`) or 6-digit `YYYYMM` into `YYYYMM`. */
    private fun normalizePeriod(period: Int): Int {
        if (period >= 100_000) return period
        return period * 100 + 12
    }

    fun countryCodeFor(currency: String): String? =
        imfCountryByCurrency[currency.trim().uppercase(Locale.US)]

    fun isSupported(currency: String): Boolean = countryCodeFor(currency) != null

    /** Returns an accurate attribution label for the currently displayed currencies. */
    fun sourceLabelFor(currencies: Collection<String>): String {
        val sources = currencies.mapNotNull { countryCodeFor(it) }
            .mapNotNull { seriesByCountry[it]?.source }
            .toSet()
        return when {
            sources.isEmpty() || sources == setOf(SOURCE_IMF) -> SOURCE_LABEL
            sources == setOf(SOURCE_WB) -> "World Bank CPI (annual fallback)"
            else -> "IMF / World Bank CPI"
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Loads the disk cache without touching the network (splash / offline boot). */
    fun initializeOfflineOnly(context: Context) {
        val app = context.applicationContext
        appContext = app
        loadCacheFromDisk(app)
    }

    /** Loads the disk cache, then refreshes any stale cached economies in the background. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        loadCacheFromDisk(app)
        val stale = seriesByCountry.entries
            .filter { isStale(it.value) }
            .map { it.key }
        if (stale.isNotEmpty()) {
            networkExecutor.execute { fetchCountries(stale, null) }
        }
    }

    /** Registers the `@cpi_ratio(...)` extern on a native engine handle. */
    fun registerWithEngine(handle: Long): Boolean {
        if (handle == 0L) return false
        return runCatching {
            MathEngineBridge.nativeRegisterExtern(handle, EXTERN_NAME, externCallback)
        }.getOrDefault(false)
    }

    val externCallback = MathEngineBridge.ExternCallback { name, numbers, strings ->
        if (name != EXTERN_NAME) {
            Double.NaN
        } else {
            val currency = strings?.firstOrNull()?.trim()?.uppercase(Locale.US).orEmpty()
            val nums = numbers ?: DoubleArray(0)
            if (currency.isEmpty() || nums.size < 2) {
                -1.0
            } else {
                cpiRatio(currency, nums[0].toInt(), nums[1].toInt())
            }
        }
    }

    // =========================================================================
    // Synchronous lookups (engine worker / UI thread safe, never block)
    // =========================================================================

    /**
     * Price-level ratio `CPI(toPeriod) / CPI(fromPeriod)` for [currency], or `-1.0` when
     * the economy/period is not cached yet. Both arguments may be 4-digit years (`2015`)
     * or packed 6-digit `YYYYMM` (`201501`).
     */
    fun cpiRatio(currency: String, fromPeriod: Int, toPeriod: Int): Double {
        val country = countryCodeFor(currency) ?: return -1.0
        val data = seriesByCountry[country] ?: return -1.0
        val fromYm = normalizePeriod(fromPeriod)
        val toYm = normalizePeriod(toPeriod)
        if (toYm < fromYm) return -1.0
        if (toYm == fromYm) return 1.0

        // Snap to the latest reported observation at or before each endpoint so a
        // not-yet-published current month still resolves cleanly to the latest release.
        val p0 = data.monthlyIndex.floorKey(fromYm) ?: return -1.0
        val p1 = data.monthlyIndex.floorKey(toYm) ?: return -1.0
        if (p1 <= p0) return if (p1 == p0) 1.0 else -1.0

        val i0 = data.monthlyIndex[p0] ?: return -1.0
        val i1 = data.monthlyIndex[p1] ?: return -1.0
        if (i0 <= 0.0 || i1 <= 0.0 || i0.isNaN() || i1.isNaN() || i0.isInfinite() || i1.isInfinite()) {
            return -1.0
        }
        return i1 / i0
    }

    fun cpiRatio(
        currency: String,
        fromYear: Int,
        fromMonth: Int,
        toYear: Int,
        toMonth: Int
    ): Double = cpiRatio(currency, packYearMonth(fromYear, fromMonth), packYearMonth(toYear, toMonth))

    /** Cumulative inflation in percent, or `NaN` when unavailable. */
    fun inflationPercent(currency: String, fromPeriod: Int, toPeriod: Int): Double {
        val ratio = cpiRatio(currency, fromPeriod, toPeriod)
        return if (ratio > 0.0) (ratio - 1.0) * 100.0 else Double.NaN
    }

    fun inflationPercent(
        currency: String,
        fromYear: Int,
        fromMonth: Int,
        toYear: Int,
        toMonth: Int
    ): Double = inflationPercent(currency, packYearMonth(fromYear, fromMonth), packYearMonth(toYear, toMonth))

    /** Relative-PPP real change of [base] against [quote] in percent, or `NaN`. */
    fun realChangePercent(base: String, quote: String, fromPeriod: Int, toPeriod: Int): Double {
        val fb = cpiRatio(base, fromPeriod, toPeriod)
        val fq = cpiRatio(quote, fromPeriod, toPeriod)
        if (fb <= 0.0 || fq <= 0.0) return Double.NaN
        return (fq / fb - 1.0) * 100.0
    }

    fun realChangePercent(
        base: String,
        quote: String,
        fromYear: Int,
        fromMonth: Int,
        toYear: Int,
        toMonth: Int
    ): Double = realChangePercent(base, quote, packYearMonth(fromYear, fromMonth), packYearMonth(toYear, toMonth))

    /** Latest packed `YYYYMM` at or before [period] that has a reported CPI index, or `null`. */
    fun resolvedPeriod(currency: String, period: Int): Int? {
        val country = countryCodeFor(currency) ?: return null
        val data = seriesByCountry[country] ?: return null
        val ym = normalizePeriod(period)
        return data.monthlyIndex.floorKey(ym) ?: data.monthlyIndex.ceilingKey(ym)
    }

    /**
     * Common comparison window for [currencies] as packed `(fromYm, toYm)`: the latest start
     * month and the earliest end month that every economy reports, so the relative-PPP
     * comparison covers the exact same period on both sides.
     */
    fun resolvedRange(
        currencies: Collection<String>,
        fromPeriod: Int,
        toPeriod: Int
    ): Pair<Int, Int>? {
        val fromYm = normalizePeriod(fromPeriod)
        val toYm = normalizePeriod(toPeriod)
        var start = Int.MIN_VALUE
        var end = Int.MAX_VALUE
        for (currency in currencies) {
            val country = countryCodeFor(currency) ?: return null
            val data = seriesByCountry[country] ?: return null
            val s = data.monthlyIndex.floorKey(fromYm) ?: return null
            val e = data.monthlyIndex.floorKey(toYm) ?: return null
            start = maxOf(start, s)
            end = minOf(end, e)
        }
        return if (start != Int.MIN_VALUE && end != Int.MAX_VALUE && end >= start) {
            start to end
        } else {
            null
        }
    }

    fun hasData(currency: String, fromPeriod: Int, toPeriod: Int): Boolean =
        cpiRatio(currency, fromPeriod, toPeriod) > 0.0

    fun lastUpdatedMs(currency: String): Long {
        val country = countryCodeFor(currency) ?: return 0L
        return seriesByCountry[country]?.fetchedAtMs ?: 0L
    }

    // =========================================================================
    // Asynchronous data loading
    // =========================================================================

    /**
     * Ensures [currencies] have CPI data covering [fromPeriod]..[toPeriod] (packed `YYYYMM`
     * or 4-digit calendar years). [onDone] runs on the network thread with `true` when at
     * least one requested economy now has usable data.
     */
    fun ensureDataAsync(
        currencies: Collection<String>,
        fromPeriod: Int,
        toPeriod: Int,
        onDone: (Boolean) -> Unit
    ) {
        val countries = currencies.mapNotNull { countryCodeFor(it) }.distinct()
        if (countries.isEmpty()) {
            onDone(false)
            return
        }
        val targetYm = normalizePeriod(toPeriod)
        val now = System.currentTimeMillis()
        val needed = countries.filter { country ->
            val data = seriesByCountry[country]
            val stale = data == null || isStale(data)
            val missing = data == null || data.monthlyIndex.floorKey(targetYm) == null
            val recentlyFailed = (now - (lastFailureMs[country] ?: 0L)) < RETRY_BACKOFF_MS
            (stale || missing) && !recentlyFailed
        }
        if (needed.isEmpty()) {
            onDone(countries.any { country -> cpiRatio(currencyForCountry(country), fromPeriod, toPeriod) > 0.0 })
            return
        }
        networkExecutor.execute { fetchCountries(needed, onDone) }
    }

    /** Refreshes any stale cached economies in the background. */
    fun refreshIfStaleAsync() {
        val stale = seriesByCountry.entries.filter { isStale(it.value) }.map { it.key }
        if (stale.isEmpty()) return
        networkExecutor.execute { fetchCountries(stale, null) }
    }

    private fun isStale(data: CountryCpi): Boolean =
        System.currentTimeMillis() - data.fetchedAtMs > CACHE_TTL_MS

    /**
     * Fetches the requested economies from IMF SDMX 3.0 (`IMF.STA/CPI`), falling back to
     * World Bank annual CPI only for any economy absent from the IMF response.
     */
    private fun fetchCountries(countries: List<String>, onDone: ((Boolean) -> Unit)?) {
        if (countries.isEmpty()) {
            onDone?.invoke(false)
            return
        }
        try {
            val now = System.currentTimeMillis()
            val toFetch = synchronized(fetchLock) {
                countries.filter { country ->
                    val data = seriesByCountry[country]
                    data == null || isStale(data)
                }
            }
            if (toFetch.isEmpty()) {
                onDone?.invoke(countries.any { seriesByCountry[it] != null })
                return
            }

            val imfParsed = fetchImfMonthlyCpi(toFetch)
            imfParsed.forEach { (country, series) ->
                if (series.isNotEmpty()) {
                    seriesByCountry[country] = CountryCpi(TreeMap(series), now, SOURCE_IMF)
                    lastFailureMs.remove(country)
                }
            }

            // Per-economy fallback to World Bank annual CPI if IMF returned no rows for a code.
            val missingAfterImf = toFetch.filter { imfParsed[it].isNullOrEmpty() }
            if (missingAfterImf.isNotEmpty()) {
                val wbParsed = fetchWorldBankFallback(missingAfterImf)
                wbParsed.forEach { (imfCountry, series) ->
                    if (series.isNotEmpty()) {
                        seriesByCountry[imfCountry] = CountryCpi(TreeMap(series), now, SOURCE_WB)
                        lastFailureMs.remove(imfCountry)
                    }
                }
            }

            val stillMissing = toFetch.filter { seriesByCountry[it]?.monthlyIndex.isNullOrEmpty() }
            stillMissing.forEach { lastFailureMs[it] = now }
            if (stillMissing.isNotEmpty()) {
                Log.w(TAG, "No CPI series returned for $stillMissing")
            }

            if (seriesByCountry.isNotEmpty()) {
                persistCache()
            }
            onDone?.invoke(countries.any { seriesByCountry[it]?.monthlyIndex?.isNotEmpty() == true })
        } catch (t: Throwable) {
            Log.w(TAG, "IMF CPI fetch failed", t)
            val now = System.currentTimeMillis()
            countries.forEach { lastFailureMs[it] = now }
            onDone?.invoke(false)
        }
    }

    private fun fetchImfMonthlyCpi(countries: List<String>): Map<String, TreeMap<Int, Double>> {
        val url = String.format(
            Locale.US,
            IMF_CPI_URL,
            countries.joinToString("+"),
            FIRST_YEAR
        )
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/csv")
            .header("User-Agent", "MEPCalc/1.0 (Android; +https://github.com/Amit2286658/MEP-Calc)")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "IMF SDMX HTTP ${response.code} for $countries")
                    return@use emptyMap()
                }
                val body = response.body?.string().orEmpty()
                parseImfSdmxCsv(body)
            }
        }.getOrElse {
            Log.w(TAG, "IMF SDMX request error for $countries", it)
            emptyMap()
        }
    }

    /**
     * Parses an IMF SDMX 3.0 CSV response (`Accept: text/csv`) into `country -> (YYYYMM -> index)`.
     * Keeps `(indexType, frequency)` buckets separate so base years are never mixed, then selects
     * the highest-priority coherent series per country (`CPI/M` > `HICP/M` > `CPI/Q` > `HICP/Q`).
     */
    internal fun parseImfSdmxCsv(body: String): Map<String, TreeMap<Int, Double>> {
        if (body.isBlank()) return emptyMap()
        val lines = body.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyMap()

        val header = parseCsvLine(lines.next()).map { it.trim().uppercase(Locale.US) }
        val colCountry = header.indexOfFirst { it == "COUNTRY" || it == "REF_AREA" }
        val colTime = header.indexOfFirst { it == "TIME_PERIOD" }
        val colValue = header.indexOfFirst { it == "OBS_VALUE" }
        if (colCountry < 0 || colTime < 0 || colValue < 0) return emptyMap()

        val colIndexType = header.indexOfFirst { it == "INDEX_TYPE" }
        val colFreq = header.indexOfFirst { it == "FREQUENCY" || it == "FREQ" }

        // country -> bucketKey ("CPI|M", "HICP|M", "CPI|Q", "HICP|Q") -> (YYYYMM -> index)
        val buckets = HashMap<String, HashMap<String, TreeMap<Int, Double>>>()

        while (lines.hasNext()) {
            val cols = parseCsvLine(lines.next())
            if (cols.size <= maxOf(colCountry, colTime, colValue)) continue
            val country = cols[colCountry].trim().uppercase(Locale.US)
            if (country.isEmpty()) continue
            val value = cols[colValue].trim().toDoubleOrNull() ?: continue
            if (value <= 0.0 || value.isNaN() || value.isInfinite()) continue

            val timeRaw = cols[colTime].trim().uppercase(Locale.US)
            val ym = parseImfTimePeriod(timeRaw) ?: continue

            val idxType = if (colIndexType >= 0 && colIndexType < cols.size) {
                cols[colIndexType].trim().uppercase(Locale.US).ifEmpty { "CPI" }
            } else "CPI"
            val freq = if (colFreq >= 0 && colFreq < cols.size) {
                cols[colFreq].trim().uppercase(Locale.US).ifEmpty {
                    if (timeRaw.contains("-Q")) "Q" else "M"
                }
            } else if (timeRaw.contains("-Q")) "Q" else "M"

            val bucketKey = "$idxType|$freq"
            buckets
                .getOrPut(country) { HashMap() }
                .getOrPut(bucketKey) { TreeMap() }[ym] = value
        }

        val priority = listOf("CPI|M", "HICP|M", "CPI|Q", "HICP|Q")
        val out = HashMap<String, TreeMap<Int, Double>>()
        for ((country, countryBuckets) in buckets) {
            val chosen = priority.firstNotNullOfOrNull { key ->
                countryBuckets[key]?.takeIf { it.isNotEmpty() }
            } ?: countryBuckets.values.maxByOrNull { it.size }
            if (chosen != null && chosen.isNotEmpty()) {
                out[country] = chosen
            }
        }
        return out
    }

    /** Parses IMF SDMX time strings: `2025-M01`, `2025-01`, `2025-Q1`..`2025-Q4`. */
    internal fun parseImfTimePeriod(raw: String): Int? {
        val dash = raw.indexOf('-')
        if (dash != 4 || raw.length < 6) return null
        val year = raw.substring(0, 4).toIntOrNull() ?: return null
        val suffix = raw.substring(5)
        val month = when {
            suffix.startsWith("M") -> suffix.substring(1).toIntOrNull()
            suffix.startsWith("Q") -> suffix.substring(1).toIntOrNull()?.let { q ->
                if (q in 1..4) q * 3 else null
            }
            else -> suffix.toIntOrNull()
        } ?: return null
        if (month !in 1..12) return null
        return packYearMonth(year, month)
    }

    /** RFC-4180 minimal CSV line parser supporting quoted fields and escaped quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>(12)
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        result.add(cur.toString())
        return result
    }

    /**
     * World Bank annual fallback: compounds `FP.CPI.TOTL.ZG` into a synthetic `YYYY12` index
     * series so economies missing from IMF monthly CPI still work seamlessly.
     */
    private fun fetchWorldBankFallback(imfCountries: List<String>): Map<String, TreeMap<Int, Double>> {
        val wbToImf = HashMap<String, String>()
        val wbCodes = ArrayList<String>()
        for (imfCode in imfCountries) {
            val wb = wbCodeByImfCountry[imfCode] ?: continue
            wbToImf[wb] = imfCode
            wbCodes.add(wb)
        }
        if (wbCodes.isEmpty()) return emptyMap()

        val endYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val url = String.format(
            Locale.US,
            WORLDBANK_URL,
            wbCodes.joinToString(";"),
            FIRST_YEAR,
            endYear
        )
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MEPCalc/1.0 (Android; +https://github.com/Amit2286658/MEP-Calc)")
            .build()

        val ratesByWb = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap<String, TreeMap<Int, Double>>()
                parseWorldBankAnnual(response.body?.string().orEmpty(), wbCodes)
            }
        }.getOrDefault(emptyMap())

        val out = HashMap<String, TreeMap<Int, Double>>()
        for ((wbCode, annualRates) in ratesByWb) {
            val imfCode = wbToImf[wbCode] ?: continue
            if (annualRates.isEmpty()) continue
            val idxSeries = TreeMap<Int, Double>()
            var level = 100.0
            val firstYear = annualRates.firstKey()
            idxSeries[packYearMonth(firstYear, 12)] = level
            for ((year, rate) in annualRates) {
                if (year == firstYear) continue
                val mult = 1.0 + rate / 100.0
                if (mult <= 0.0 || mult.isNaN() || mult.isInfinite()) continue
                level *= mult
                idxSeries[packYearMonth(year, 12)] = level
            }
            if (idxSeries.isNotEmpty()) out[imfCode] = idxSeries
        }
        return out
    }

    private fun parseWorldBankAnnual(body: String, requested: List<String>): Map<String, TreeMap<Int, Double>> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyMap()
        val rows = root.optJSONArray(1) ?: return emptyMap()
        val requestedSet = requested.toSet()
        val out = HashMap<String, TreeMap<Int, Double>>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val value = row.optDouble("value", Double.NaN)
            if (value.isNaN() || value.isInfinite()) continue
            val year = row.optString("date").toIntOrNull() ?: continue
            val iso2 = row.optJSONObject("country")?.optString("id").orEmpty()
            val iso3 = row.optString("countryiso3code")
            val key = when {
                iso2 in requestedSet -> iso2
                iso3 in requestedSet -> iso3
                iso3.isNotEmpty() -> iso3
                else -> iso2
            }
            if (key.isEmpty()) continue
            out.getOrPut(key) { TreeMap() }[year] = value
        }
        return out
    }

    private fun currencyForCountry(country: String): String =
        imfCountryByCurrency.entries.firstOrNull { it.value == country }?.key ?: country

    // =========================================================================
    // Disk cache
    // =========================================================================

    private fun loadCacheFromDisk(context: Context) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) != CACHE_VERSION) return@runCatching
            val countries = root.optJSONObject("countries") ?: return@runCatching
            val keys = countries.keys()
            while (keys.hasNext()) {
                val country = keys.next()
                val entry = countries.optJSONObject(country) ?: continue
                val fetchedAt = entry.optLong("fetchedAt", 0L)
                val source = entry.optString("source", SOURCE_IMF)
                val monthsObj = entry.optJSONObject("months") ?: continue
                val series = TreeMap<Int, Double>()
                val monthKeys = monthsObj.keys()
                while (monthKeys.hasNext()) {
                    val ym = monthKeys.next().toIntOrNull() ?: continue
                    val v = monthsObj.optDouble(ym.toString(), Double.NaN)
                    if (v > 0.0 && !v.isNaN() && !v.isInfinite()) series[ym] = v
                }
                if (series.isNotEmpty()) {
                    seriesByCountry[country] = CountryCpi(series, fetchedAt, source)
                }
            }
            Log.i(TAG, "Loaded IMF CPI cache for ${seriesByCountry.size} economies")
        }.onFailure { Log.w(TAG, "Could not read IMF CPI cache", it) }
    }

    private fun persistCache() {
        val context = appContext ?: return
        runCatching {
            val countries = JSONObject()
            seriesByCountry.forEach { (country, data) ->
                val months = JSONObject()
                data.monthlyIndex.forEach { (ym, idx) -> months.put(ym.toString(), idx) }
                countries.put(
                    country,
                    JSONObject()
                        .put("fetchedAt", data.fetchedAtMs)
                        .put("source", data.source)
                        .put("months", months)
                )
            }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("countries", countries)
            File(context.filesDir, CACHE_FILE_NAME)
                .writeText(root.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Could not persist IMF CPI cache", it) }
    }
}
