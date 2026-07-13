package eu.kanade.presentation.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

private val episodeFormatter = DecimalFormat(
    "#.###",
    DecimalFormatSymbols().apply { decimalSeparator = '.' },
)

fun formatEpisodeNumber(episodeNumber: Double): String {
    return episodeFormatter.format(episodeNumber)
}
