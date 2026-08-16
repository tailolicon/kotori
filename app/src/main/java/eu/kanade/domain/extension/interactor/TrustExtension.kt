package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.getAndSet

class TrustExtension(
    private val repository: ExtensionStoreRepository,
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = repository.getAll().mapTo(hashSetOf(KOTORI_KEY)) { it.signingKey }
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions.get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }

    companion object {
        /**
         * The key Kotori signs its own extensions with, trusted without a store being configured.
         *
         * Otherwise trust hangs on the store list: an extension installed by hand — sideloaded,
         * or pushed to a test device — is refused, and so is one whose store row was written from
         * an older index. Kotori built these apks and signs them with a key only it holds, so the
         * signature is the stronger statement of the two.
         */
        const val KOTORI_KEY = "4cc9ab1cd650537c42c39582fa22eb5012029d56f9f4483f9ea9d073b4f9c779"
    }
}
