package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.pm.PackageManager

/**
 * MIUI/HyperOS guards the installed-package list with a runtime permission of its own, on top of
 * the AOSP [android.Manifest.permission.QUERY_ALL_PACKAGES] declaration.
 *
 * When it is missing, `getInstalledPackages()` quietly returns an empty list instead of throwing,
 * so extension discovery finds nothing and every installed source disappears with no error to
 * explain it. The permission only exists on Xiaomi ROMs, hence the runtime lookup.
 */
const val MIUI_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"

/**
 * True when the ROM defines MIUI's package-list permission, meaning it can — and must — be granted
 * before the package list is usable.
 */
val Context.definesMiuiPackageListPermission: Boolean
    get() = try {
        packageManager.getPermissionInfo(MIUI_GET_INSTALLED_APPS, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

/** True when the package list is readable: either the ROM doesn't gate it, or it granted us access. */
val Context.canQueryInstalledPackages: Boolean
    get() = !definesMiuiPackageListPermission ||
        checkSelfPermission(MIUI_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED
