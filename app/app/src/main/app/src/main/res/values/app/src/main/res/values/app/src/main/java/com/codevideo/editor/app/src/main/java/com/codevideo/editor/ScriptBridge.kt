package com.codevideo.editor

import android.webkit.JavascriptInterface

class ScriptBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun pickVideo() {
        activity.pickVideo()
    }

    @JavascriptInterface
    fun runEdits(editsJson: String) {
        activity.runEdits(editsJson)
    }
}
