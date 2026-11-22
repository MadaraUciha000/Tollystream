package com.tollystream

import android.content.Context

/** Shared configuration between the provider and its CloudStream settings screen. */
object TollyPreferences {
    private const val PREFS = "tollystream"
    private const val LANGUAGE = "language"
    private const val ALL = "all"

    @Volatile
    private var selected = ALL

    fun load(context: Context) {
        selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE, ALL) ?: ALL
    }

    fun set(context: Context, value: String) {
        selected = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LANGUAGE, value).apply()
    }

    fun selected(): String = selected

    fun labels(): Array<String> = arrayOf(
        "All languages",
        "Tamil",
        "Telugu",
        "Hindi",
        "Malayalam",
        "Kannada",
        "Multilingual / Hollywood",
    )

    fun values(): Array<String> = arrayOf(
        ALL, "ta", "te", "hi", "ml", "kn", "multi"
    )
}
