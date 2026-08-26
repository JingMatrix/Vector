package org.matrix.vector.manager.data.repository

import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.module.PER_USER_RANGE
import org.matrix.vector.ui.store.InstallStep
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreInstallHost

/**
 * Vector's implementation of the shared Details screen's install capability: the module installer,
 * the post-install bookkeeping, and the device APK inspection that used to live in the details view
 * model. Created per opened module (it needs the package name). LSPatch has no installer and passes
 * null instead, collapsing the shared screen to open-in-browser links.
 */
class VectorStoreInstallHost(private val packageName: String) : StoreInstallHost {

    private val installer = ServiceLocator.installer

    override val installState: StateFlow<InstallStep> = installer.state

    override val silentInstall: Boolean
        get() =
            ServiceLocator.context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) ==
                PackageManager.PERMISSION_GRANTED

    private val _installedScope = MutableStateFlow<List<String>>(emptyList())
    override val installedScope: StateFlow<List<String>> = _installedScope.asStateFlow()

    private val _installedIsLegacy = MutableStateFlow(false)
    override val installedIsLegacy: StateFlow<Boolean> = _installedIsLegacy.asStateFlow()

    private val _installedUserId = MutableStateFlow<Int?>(null)
    override val installedUserId: StateFlow<Int?> = _installedUserId.asStateFlow()

    init {
        refreshScope()
    }

    private fun refreshScope() {
        ServiceLocator.appScope.launch {
            runCatching {
                val pm = ServiceLocator.context.packageManager
                val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                val appInfo = info.applicationInfo ?: return@runCatching
                val manifest =
                    ServiceLocator.moduleDetection.inspect(
                        appInfo,
                        pm,
                        info.versionCodeCompat,
                        info.lastUpdateTime,
                    )
                _installedScope.value = manifest.scope
                _installedIsLegacy.value = manifest.isLegacy
                _installedUserId.value = appInfo.uid / PER_USER_RANGE
            }
        }
    }

    override fun install(asset: ReleaseAsset, releaseVersion: RepoVersion?) {
        ServiceLocator.appScope.launch {
            installer.install(packageName, asset)
            // Record what was installed so a satisfied update stops being offered.
            val installed = ServiceLocator.store.readInstalled()[packageName]
            if (releaseVersion != null && installed != null) {
                ServiceLocator.settings.noteStoreInstall(
                    packageName,
                    StoreInstall(releaseVersion, installed),
                )
            }
            refreshScope()
        }
    }

    override fun acknowledge() = installer.acknowledge()
}
