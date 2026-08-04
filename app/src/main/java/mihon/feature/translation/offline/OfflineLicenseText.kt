package mihon.feature.translation.offline

import android.content.Context

/** Loads license / NOTICE text from app assets for the offline download consent UI. */
object OfflineLicenseText {

    fun loadLicense(context: Context): String =
        readAsset(context, OfflineModelSpec.LICENSE_ASSET_PATH)
            ?: FALLBACK_LICENSE_PLACEHOLDER

    fun loadNotice(context: Context): String =
        readAsset(context, OfflineModelSpec.NOTICE_ASSET_PATH)
            ?: FALLBACK_NOTICE

    private fun readAsset(context: Context, path: String): String? = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val FALLBACK_NOTICE =
        "Tencent HY is licensed under the Tencent HY Community License Agreement, " +
            "Copyright © 2025 Tencent. All Rights Reserved. " +
            "Offline translation in Kotori is provided by Kotori (app.mihon.dev). " +
            "Tencent is not affiliated with, associated with, sponsoring, or endorsing Kotori."

    private const val FALLBACK_LICENSE_PLACEHOLDER =
        "Full Tencent HY Community License text is packaged at " +
            "assets/licenses/TENCENT_HY_COMMUNITY_LICENSE.txt. " +
            "Territory: does not apply in the EU, United Kingdom, or South Korea."
}
