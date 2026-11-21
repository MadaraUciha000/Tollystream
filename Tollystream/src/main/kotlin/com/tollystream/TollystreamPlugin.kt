package com.tollystream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TollystreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TollystreamProvider())
    }
}
