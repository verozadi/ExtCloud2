
package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SoraAnimePlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("SoraAnime", Context.MODE_PRIVATE)
        // All providers should be added in this manner. Please don't edit the providers list directly.
        SoraAnime.context = context
        registerMainAPI(SoraAnime(sharedPref))
        registerExtractorAPI(Jeniusplay2())
        registerExtractorAPI(Majorplay2())
    }
}
