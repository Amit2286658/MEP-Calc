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
 * World Bank CPI provider & host extern backing for the C11 Math Engine `[inflation]` patch.
 *
 * The engine patch (`assets/patches/inflation.math`) owns all of the real-terms maths:
 * cumulative inflation, CPI factors, and relative-PPP appreciation/depreciation. It calls the
 * single host extern `@cpi_ratio(currency, fromYear, toYear)`; this object supplies that number
 * from the World Bank's annual consumer-price inflation series (`FP.CPI.TOTL.ZG`).
 *
 * Design notes:
 * - **No hardcoded inflation.** The annual rates come from the World Bank API and are cached on
 *   disk (`filesDir/inflation_cpi_cache.json`) so the tab keeps working offline after one fetch.
 * - **Synchronous extern, asynchronous fetch.** The JNI callback runs on the engine worker and
 *   must never block, so [cpiRatio] only reads the in-memory table and returns `-1.0` when the
 *   requested currency/years are not cached yet. The UI calls [ensureDataAsync] first and only
 *   evaluates the patch once the data is present.
 * - **Currency -> economy mapping.** Each ISO 4217 code maps to a World Bank economy code
 *   (ISO2, or the `EMU` aggregate for the euro area). The euro area has no CPI *index* in the
 *   World Bank dataset, which is why the annual inflation-rate indicator is used for every
 *   currency and compounded here.
 */
object InflationProvider {

    private const val TAG = "InflationProvider"
    private const val CACHE_FILE_NAME = "inflation_cpi_cache.json"
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    private const val RETRY_BACKOFF_MS = 6L * 60L * 60L * 1000L // 6 hours after a failed fetch
    private const val CONNECT_TIMEOUT_MS = 4_500L
    private const val READ_TIMEOUT_MS = 12_000L
    private const val FIRST_YEAR = 1960

    /** Host extern name consumed by `patches/inflation.math`. */
    const val EXTERN_NAME = "cpi_ratio"

    /** Human-readable attribution shown in the Currency tab. */
    const val SOURCE_LABEL = "World Bank CPI"

    private const val WORLDBANK_URL =
        "https://api.worldbank.org/v2/country/%s/indicator/FP.CPI.TOTL.ZG" +
            "?format=json&date=%d:%d&per_page=20000"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val fetchLock = Any()

    @Volatile
    private var appContext: Context? = null

    /** ISO 4217 currency -> World Bank economy code accepted by the API. */
    private val countryByCurrency = mapOf(
        "USD" to "US", "INR" to "IN", "EUR" to "EMU", "GBP" to "GB", "JPY" to "JP",
        "CNY" to "CN", "AUD" to "AU", "CAD" to "CA", "CHF" to "CH", "SGD" to "SG",
        "HKD" to "HK", "NZD" to "NZ", "KRW" to "KR", "SEK" to "SE", "NOK" to "NO",
        "DKK" to "DK", "MXN" to "MX", "BRL" to "BR", "ZAR" to "ZA", "RUB" to "RU",
        "TRY" to "TR", "AED" to "AE", "SAR" to "SA", "THB" to "TH", "MYR" to "MY",
        "IDR" to "ID", "PHP" to "PH", "PLN" to "PL", "TWD" to "TW", "VND" to "VN",
        "EGP" to "EG", "PKR" to "PK", "BDT" to "BD", "LKR" to "LK", "NPR" to "NP",
        "NGN" to "NG", "KES" to "KE", "GHS" to "GH", "ARS" to "AR", "CLP" to "CL",
        "COP" to "CO", "PEN" to "PE", "ILS" to "IL", "QAR" to "QA", "KWD" to "KW",
        "BHD" to "BH", "OMR" to "OM", "CZK" to "CZ", "HUF" to "HU", "RON" to "RO",
        "BGN" to "BG", "ISK" to "IS", "UAH" to "UA", "KZT" to "KZ", "MAD" to "MA"
    )

    private data class CountryCpi(
        /** Annual CPI inflation in percent, keyed by calendar year (year-over-year). */
        val annualRatePct: TreeMap<Int, Double>,
        val fetchedAtMs: Long
    )

    /** World Bank economy code -> annual CPI inflation series. */
    private val seriesByCountry = ConcurrentHashMap<String, CountryCpi>()

    /** Last failed fetch attempt per economy, to avoid hammering an unreachable API. */
    private val lastFailureMs = ConcurrentHashMap<String, Long>()

    fun countryCodeFor(currency: String): String? =
        countryByCurrency[currency.trim().uppercase(Locale.US)]

    fun isSupported(currency: String): Boolean = countryCodeFor(currency) != null

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
     * Price-level ratio `CPI(toYear) / CPI(fromYear)` for [currency], or `-1.0` when the
     * economy/years are not cached yet. Annual inflation rates are compounded, skipping years
     * the World Bank does not report.
     */
    fun cpiRatio(currency: String, fromYear: Int, toYear: Int): Double {
        val country = countryCodeFor(currency) ?: return -1.0
        val data = seriesByCountry[country] ?: return -1.0
        if (toYear < fromYear) return -1.0
        if (toYear == fromYear) return 1.0

        // Snap to the latest reported year at or before each endpoint so a missing
        // current-year estimate still resolves to a complete period.
        val y0 = data.annualRatePct.floorKey(fromYear) ?: return -1.0
        val y1 = data.annualRatePct.floorKey(toYear) ?: return -1.0
        if (y1 <= y0) return if (y1 == y0) 1.0 else -1.0

        var factor = 1.0
        var used = 0
        for (year in (y0 + 1)..y1) {
            val rate = data.annualRatePct[year] ?: continue
            if (rate.isNaN() || rate.isInfinite()) continue
            factor *= 1.0 + rate / 100.0
            used++
        }
        val span = y1 - y0
        // Refuse obviously incomplete series rather than reporting a misleading number.
        if (used == 0 || used * 4 < span * 3) return -1.0
        return factor
    }

    /** Cumulative inflation in percent, or `NaN` when unavailable. */
    fun inflationPercent(currency: String, fromYear: Int, toYear: Int): Double {
        val ratio = cpiRatio(currency, fromYear, toYear)
        return if (ratio > 0.0) (ratio - 1.0) * 100.0 else Double.NaN
    }

    /** Relative-PPP real change of [base] against [quote] in percent, or `NaN`. */
    fun realChangePercent(base: String, quote: String, fromYear: Int, toYear: Int): Double {
        val fb = cpiRatio(base, fromYear, toYear)
        val fq = cpiRatio(quote, fromYear, toYear)
        if (fb <= 0.0 || fq <= 0.0) return Double.NaN
        return (fq / fb - 1.0) * 100.0
    }

    /** Latest year at or before [year] that has a reported rate, or `null`. */
    fun resolvedYear(currency: String, year: Int): Int? {
        val country = countryCodeFor(currency) ?: return null
        val data = seriesByCountry[country] ?: return null
        return data.annualRatePct.floorKey(year) ?: data.annualRatePct.ceilingKey(year)
    }

    /**
     * Common comparison window for [currencies]: the latest start year and the earliest
     * end year that every economy reports, so the relative-PPP comparison covers exactly
     * the same period on both sides. Returns `null` when the window collapses or data is missing.
     */
    fun resolvedRange(
        currencies: Collection<String>,
        fromYear: Int,
        toYear: Int
    ): Pair<Int, Int>? {
        var start = Int.MIN_VALUE
        var end = Int.MAX_VALUE
        for (currency in currencies) {
            val country = countryCodeFor(currency) ?: return null
            val data = seriesByCountry[country] ?: return null
            val s = data.annualRatePct.floorKey(fromYear) ?: return null
            val e = data.annualRatePct.floorKey(toYear) ?: return null
            start = maxOf(start, s)
            end = minOf(end, e)
        }
        return if (start != Int.MIN_VALUE && end != Int.MAX_VALUE && end >= start) {
            start to end
        } else {
            null
        }
    }

    fun hasData(currency: String, fromYear: Int, toYear: Int): Boolean =
        cpiRatio(currency, fromYear, toYear) > 0.0

    fun lastUpdatedMs(currency: String): Long {
        val country = countryCodeFor(currency) ?: return 0L
        return seriesByCountry[country]?.fetchedAtMs ?: 0L
    }

    // =========================================================================
    // Asynchronous data loading
    // =========================================================================

    /**
     * Ensures both [currencies] have CPI data covering [fromYear]..[toYear].
     * [onDone] runs on the network thread with `true` when at least one requested
     * economy now has usable data.
     */
    fun ensureDataAsync(
        currencies: Collection<String>,
        fromYear: Int,
        toYear: Int,
        onDone: (Boolean) -> Unit
    ) {
        val countries = currencies.mapNotNull { countryCodeFor(it) }.distinct()
        if (countries.isEmpty()) {
            onDone(false)
            return
        }
        val now = System.currentTimeMillis()
        val needed = countries.filter { country ->
            val data = seriesByCountry[country]
            val stale = data == null || isStale(data)
            val missing = data == null || data.annualRatePct.floorKey(toYear) == null
            val recentlyFailed = (now - (lastFailureMs[country] ?: 0L)) < RETRY_BACKOFF_MS
            (stale || missing) && !recentlyFailed
        }
        if (needed.isEmpty()) {
            onDone(countries.any { country -> cpiRatio(currencyForCountry(country), fromYear, toYear) > 0.0 })
            return
        }
        networkExecutor.execute { fetchCountries(needed, onDone) }
    }

    /** Refreshes the full cached table (used when the tab is opened and data is stale). */
    fun refreshIfStaleAsync() {
        val stale = seriesByCountry.entries.filter { isStale(it.value) }.map { it.key }
        if (stale.isEmpty()) return
        networkExecutor.execute { fetchCountries(stale, null) }
    }

    private fun isStale(data: CountryCpi): Boolean =
        System.currentTimeMillis() - data.fetchedAtMs > CACHE_TTL_MS

    /**
     * Fetches the requested economies in one World Bank request, merges the result into the
     * in-memory table, persists the cache, and invokes [onDone] with success.
     */
    private fun fetchCountries(countries: List<String>, onDone: ((Boolean) -> Unit)?) {
        if (countries.isEmpty()) {
            onDone?.invoke(false)
            return
        }
        try {
            val now = System.currentTimeMillis()
            // Re-check under the lock: a queued duplicate request must not hit the network twice.
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

            val endYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val url = String.format(
                Locale.US,
                WORLDBANK_URL,
                toFetch.joinToString(";"),
                FIRST_YEAR,
                endYear
            )
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MEPCalc/1.0 (Android; +https://github.com/Amit2286658/MEP-Calc)")
                .build()

            val parsed = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "World Bank HTTP ${response.code} for $toFetch")
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    parseWorldBank(body, toFetch)
                }
            }.getOrNull()

            if (parsed.isNullOrEmpty()) {
                toFetch.forEach { lastFailureMs[it] = now }
                Log.w(TAG, "World Bank CPI fetch returned no usable rows for $toFetch")
                onDone?.invoke(countries.any { seriesByCountry[it]?.annualRatePct?.isNotEmpty() == true })
                return
            }

            parsed.forEach { (country, rates) ->
                if (rates.isNotEmpty()) {
                    seriesByCountry[country] = CountryCpi(TreeMap(rates), now)
                    lastFailureMs.remove(country)
                }
            }
            persistCache()
            onDone?.invoke(countries.any { seriesByCountry[it]?.annualRatePct?.isNotEmpty() == true })
        } catch (t: Throwable) {
            Log.w(TAG, "World Bank CPI fetch failed", t)
            val now = System.currentTimeMillis()
            countries.forEach { lastFailureMs[it] = now }
            onDone?.invoke(false)
        }
    }

    private fun currencyForCountry(country: String): String =
        countryByCurrency.entries.firstOrNull { it.value == country }?.key ?: country

    /**
     * Parses the World Bank `[metadata, rows]` envelope into `country -> (year -> rate%)`.
     * Rows are keyed by the requested economy code: the euro-area aggregate reports
     * `country.id = "XC"` but `countryiso3code = "EMU"`, which is what we requested.
     */
    private fun parseWorldBank(body: String, requested: List<String>): Map<String, TreeMap<Int, Double>> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyMap()
        val rows = root.optJSONArray(1) ?: return emptyMap()
        val requestedSet = requested.toSet()
        val out = HashMap<String, TreeMap<Int, Double>>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val value = row.optDouble("value", Double.NaN)
            if (value.isNaN() || value.isInfinite()) continue
            val year = row.optString("date").toIntOrNull() ?: continue
            val countryObj = row.optJSONObject("country")
            val iso2 = countryObj?.optString("id").orEmpty()
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

    // =========================================================================
    // Disk cache
    // =========================================================================

    private fun loadCacheFromDisk(context: Context) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val countries = root.optJSONObject("countries") ?: return@runCatching
            val keys = countries.keys()
            while (keys.hasNext()) {
                val country = keys.next()
                val entry = countries.optJSONObject(country) ?: continue
                val fetchedAt = entry.optLong("fetchedAt", 0L)
                val yearsObj = entry.optJSONObject("years") ?: continue
                val rates = TreeMap<Int, Double>()
                val yearKeys = yearsObj.keys()
                while (yearKeys.hasNext()) {
                    val y = yearKeys.next().toIntOrNull() ?: continue
                    val v = yearsObj.optDouble(y.toString(), Double.NaN)
                    if (!v.isNaN() && !v.isInfinite()) rates[y] = v
                }
                if (rates.isNotEmpty()) {
                    seriesByCountry[country] = CountryCpi(rates, fetchedAt)
                }
            }
            Log.i(TAG, "Loaded CPI cache for ${seriesByCountry.size} economies")
        }.onFailure { Log.w(TAG, "Could not read CPI cache", it) }
    }

    private fun persistCache() {
        val context = appContext ?: return
        runCatching {
            val countries = JSONObject()
            seriesByCountry.forEach { (country, data) ->
                val years = JSONObject()
                data.annualRatePct.forEach { (year, rate) -> years.put(year.toString(), rate) }
                countries.put(
                    country,
                    JSONObject()
                        .put("fetchedAt", data.fetchedAtMs)
                        .put("years", years)
                )
            }
            val root = JSONObject()
                .put("version", 1)
                .put("countries", countries)
            File(context.filesDir, CACHE_FILE_NAME)
                .writeText(root.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Could not persist CPI cache", it) }
    }
}
