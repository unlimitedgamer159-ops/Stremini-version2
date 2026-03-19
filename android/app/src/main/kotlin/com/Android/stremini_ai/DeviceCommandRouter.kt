package com.Android.stremini_ai

class DeviceCommandRouter {
    private val deviceCommandKeywords = listOf(
        "open", "launch", "close", "go to", "navigate to",
        "tap", "click", "press", "scroll", "swipe",
        "type", "write", "fill", "search for",
        "call", "message", "send", "whatsapp",
        "take screenshot", "screenshot",
        "volume", "brightness", "mute", "unmute",
        "go home", "go back", "recent apps",
        "notifications", "settings",
        "play", "pause", "stop", "next", "previous",
        "zoom in", "zoom out",
        "copy", "paste", "cut", "select all",
        "find", "read screen"
    )

    fun isDeviceCommand(message: String): Boolean {
        val lower = message.lowercase()
        return deviceCommandKeywords.any { lower.contains(it) }
    }

    fun executeDirect(message: String): Boolean = ScreenReaderService.runGenericAutomation(message)
}