package com.sontolfilm

import com.lagradost.cloudstream3.extractors.VidStack

class HaruPlayer : VidStack() {
    override var name = "HaruPlayer"
    override var mainUrl = "https://haruplayer.embed4me.com"
    override var requiresReferer = true
}
