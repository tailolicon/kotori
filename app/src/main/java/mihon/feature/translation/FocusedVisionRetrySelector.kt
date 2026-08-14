package mihon.feature.translation

/** Selects blank speech slots and obvious provider echoes for one focused second vision pass. */
internal object FocusedVisionRetrySelector {
    fun indices(
        translations: List<String>,
        speechBoxes: List<Boolean>,
        suspectedEchoes: List<Boolean> = List(translations.size) { false },
        limit: Int,
    ): List<Int> =
        translations.indices
            .filter { index ->
                (translations[index].isBlank() && speechBoxes.getOrNull(index) == true) ||
                    suspectedEchoes.getOrNull(index) == true
            }
            .take(limit.coerceAtLeast(0))
}
