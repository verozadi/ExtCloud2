package com.pusatfilm21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Pusatfilm21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pusatfilm21Provider())
        registerExtractorAPI(KotakAjaib())
        registerExtractorAPI(PusatEmturbovid())
        registerExtractorAPI(PusatTurbovidHls())
        registerExtractorAPI(PusatHydrax())
        registerExtractorAPI(PusatGdriveplayer())
    }
}
