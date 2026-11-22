package com.tollystream

import android.content.Context
import android.app.AlertDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TollystreamPlugin : Plugin() {
    override fun load(context: Context) {
        TollyPreferences.load(context)
        openSettings = { activity ->
            val values = TollyPreferences.values()
            val labels = TollyPreferences.labels()
            val selected = values.indexOf(TollyPreferences.selected()).coerceAtLeast(0)
            AlertDialog.Builder(activity)
                .setTitle("Tollystream · Language")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    TollyPreferences.set(activity, values[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        }
        registerMainAPI(TollystreamProvider())
        listOf("ta", "te", "hi", "ml", "kn", "multi").forEach { language ->
            registerMainAPI(TollystreamProvider(language))
        }
    }
}
