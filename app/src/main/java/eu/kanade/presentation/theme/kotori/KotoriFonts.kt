package eu.kanade.presentation.theme.kotori

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R

/**
 * Kotori type system:
 * - Unbounded (variable): display, screen titles, card titles, numerals, section labels
 * - Be Vietnam Pro: body / general UI
 * - Literata (variable, serif): novel reading text
 * All three support Vietnamese diacritics.
 */

private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val UnboundedFamily = FontFamily(
    variableFont(R.font.unbounded, FontWeight.Normal),
    variableFont(R.font.unbounded, FontWeight.Medium),
    variableFont(R.font.unbounded, FontWeight.SemiBold),
    variableFont(R.font.unbounded, FontWeight.Bold),
    variableFont(R.font.unbounded, FontWeight.ExtraBold),
)

val BeVietnamProFamily = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
)

val LiterataFamily = FontFamily(
    variableFont(R.font.literata, FontWeight.Normal),
    variableFont(R.font.literata, FontWeight.Medium),
    variableFont(R.font.literata, FontWeight.SemiBold),
    variableFont(R.font.literata, FontWeight.Bold),
)

/** Section labels like `HÔM NAY`, `ĐANG TẢI`: Unbounded 10sp, wide tracking, uppercase. */
val KotoriSectionLabelStyle = TextStyle(
    fontFamily = UnboundedFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    letterSpacing = 0.16.em,
)

private val default = Typography()

val KotoriTypography = Typography(
    displayLarge = default.displayLarge.copy(fontFamily = UnboundedFamily),
    displayMedium = default.displayMedium.copy(fontFamily = UnboundedFamily),
    displaySmall = default.displaySmall.copy(fontFamily = UnboundedFamily),
    headlineLarge = default.headlineLarge.copy(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold),
    headlineMedium = default.headlineMedium.copy(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold),
    headlineSmall = default.headlineSmall.copy(fontFamily = UnboundedFamily, fontWeight = FontWeight.Bold),
    titleLarge = default.titleLarge.copy(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    ),
    titleMedium = default.titleMedium.copy(fontFamily = UnboundedFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = default.titleSmall.copy(fontFamily = BeVietnamProFamily, fontWeight = FontWeight.Bold),
    bodyLarge = default.bodyLarge.copy(fontFamily = BeVietnamProFamily),
    bodyMedium = default.bodyMedium.copy(fontFamily = BeVietnamProFamily),
    bodySmall = default.bodySmall.copy(fontFamily = BeVietnamProFamily),
    labelLarge = default.labelLarge.copy(fontFamily = BeVietnamProFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = default.labelMedium.copy(fontFamily = BeVietnamProFamily, fontWeight = FontWeight.Medium),
    labelSmall = default.labelSmall.copy(fontFamily = BeVietnamProFamily, fontWeight = FontWeight.Medium),
)
