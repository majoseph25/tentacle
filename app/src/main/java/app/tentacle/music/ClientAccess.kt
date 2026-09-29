// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationManagerCompat

/**
 * Decides which apps may browse the library and choose what plays (PlaybackService) and read artwork (ArtworkProvider).
 * Follows the rules of Google's reference media app (UAMP's PackageValidator).
 *
 * Allowed:
 *  - this app and the system process,
 *  - apps signed with the platform key (System UI and other core OS components),
 *  - apps holding MEDIA_CONTENT_CONTROL (a signature/privileged permission for media controllers),
 *  - apps signed with the same certificate as the preinstalled Google Play services, i.e. Google's own
 *    apps (Android Auto, Google app/Assistant, Play services). A third party can't forge this,
 *  - apps the user has granted notification access (they can already control every media session),
 *  - Android Auto and Google Assistant by package name, only when preinstalled on the system image or
 *    installed by the Play Store, so a sideloaded app can't claim their names on a phone without them.
 *
 * Being a preinstalled app alone is not enough: many phones ship third-party apps on the system image.
 */
object ClientAccess {

    private val TRUSTED_PACKAGES = setOf(
        "com.google.android.projection.gearhead", // Android Auto
        "com.google.android.googlequicksearchbox", // Google app / Assistant
        "com.google.android.carassistant",
    )

    private const val PLAY_STORE = "com.android.vending"
    private const val PLATFORM_PACKAGE = "android"
    private const val PLAY_SERVICES = "com.google.android.gms"

    /** Why a caller was allowed or rejected, for the connection log shown in Settings. */
    data class Decision(val allowed: Boolean, val packageName: String, val reason: String)

    fun isAllowed(context: Context, uid: Int, packageName: String? = null): Boolean =
        check(context, uid, packageName).allowed

    fun check(context: Context, uid: Int, packageName: String? = null): Decision {
        if (uid == Process.myUid()) return Decision(true, context.packageName, "this app")
        if (uid == Process.SYSTEM_UID) return Decision(true, "android", "system")
        val pm = context.packageManager
        val packages = if (packageName != null) arrayOf(packageName) else pm.getPackagesForUid(uid).orEmpty()
        if (packages.isEmpty()) return Decision(false, "uid $uid", "unknown app")
        for (pkg in packages) {
            val reason = try {
                trustReason(context, pm, pkg)
            } catch (e: Exception) {
                // Never let a lookup failure crash the service; treat it as untrusted.
                null
            }
            if (reason != null) return Decision(true, pkg, reason)
        }
        return Decision(false, packages.first(), "not a trusted media controller")
    }

    private fun trustReason(context: Context, pm: PackageManager, pkg: String): String? {
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if (pm.checkSignatures(PLATFORM_PACKAGE, pkg) == PackageManager.SIGNATURE_MATCH) return "platform signed"
        if (pm.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pkg) == PackageManager.PERMISSION_GRANTED) {
            return "media control permission"
        }
        if (signedLikePreinstalledPlayServices(pm, pkg)) return "Google signed"
        if (pkg in NotificationManagerCompat.getEnabledListenerPackages(context)) return "notification access"
        if (pkg in TRUSTED_PACKAGES) {
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return "preinstalled"
            if (installer(pm, pkg) == PLAY_STORE) return "installed from Play"
        }
        return null
    }

    /** True if [pkg] has the same signing certificate as Google Play services from the system image. */
    private fun signedLikePreinstalledPlayServices(pm: PackageManager, pkg: String): Boolean {
        val gms = try {
            pm.getApplicationInfo(PLAY_SERVICES, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        if ((gms.flags and ApplicationInfo.FLAG_SYSTEM) == 0) return false
        return pm.checkSignatures(PLAY_SERVICES, pkg) == PackageManager.SIGNATURE_MATCH
    }

    private fun installer(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }
}
