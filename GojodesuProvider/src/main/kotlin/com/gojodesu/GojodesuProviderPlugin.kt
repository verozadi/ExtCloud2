package com.gojodesu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GojodesuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GojodesuProvider())
        registerExtractorAPI(GojodesuKotakAjaib())
    }
}
