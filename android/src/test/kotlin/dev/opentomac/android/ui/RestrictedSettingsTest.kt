package dev.opentomac.android.ui

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestrictedSettingsTest {
    @Test
    fun downloadedFileIsRestrictedOnlyOnAndroid13AndLater() {
        assertFalse(isRestrictedInstall(32, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE))
        assertTrue(isRestrictedInstall(33, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE))
        assertFalse(isRestrictedInstall(33, PackageInstaller.PACKAGE_SOURCE_STORE))
    }
}
