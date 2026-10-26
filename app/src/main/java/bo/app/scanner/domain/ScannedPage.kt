package bo.app.scanner.domain

/**
 * Página de la mesa de trabajo.
 * [original]: captura normalizada (EXIF aplicado). [base]: resultado del recorte (o = original).
 * [corners]: 8 valores normalizados 0..1 (TL, TR, BR, BL) usados en el último recorte.
 */
data class ScannedPage(
    val id: String,
    val original: String,
    val base: String,
    val rotation: Int = 0,
    val filter: FilterType = FilterType.ORIGINAL,
    val corners: List<Float>? = null,
)

enum class QualitySetting(val label: String, val maxSide: Int?, val jpeg: Float) {
    ALTA("Alta", null, 0.9f),
    MEDIA("Media", 1600, 0.7f),
    ECONOMICA("Económica", 1080, 0.5f),
}
