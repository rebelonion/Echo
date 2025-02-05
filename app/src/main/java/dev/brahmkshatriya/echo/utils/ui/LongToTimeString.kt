package dev.brahmkshatriya.echo.utils.ui

import java.util.Locale
import kotlin.math.roundToLong

fun Long.toTimeString(): String {
    val seconds = (this.toFloat() / 1000).roundToLong()
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format(Locale.ENGLISH, "%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format(Locale.ENGLISH, "%02d:%02d", minutes, seconds % 60)
    }
}