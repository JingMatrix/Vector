package org.matrix.vector.manager.ui.components

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import android.text.format.Formatter
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.matrix.vector.manager.data.model.ReleaseAsset
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.manager.ui.screens.repo.StoreChannel
import org.matrix.vector.manager.ui.screens.repo.releasesOn
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.TextButton
import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel
import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel.Companion.SYSTEM_FRAMEWORK_PACKAGE
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.theme.VectorMono

/** What a long press did, and how it went. */
data class PackageActionResult(
    val messageRes: Int,
    val argument: String? = null,
    val tone: SnackbarTone = SnackbarTone.Neutral,
)

/**
 * The long-press sheet for a package, whether it is a module or an app in a module's scope.
 *
 * A sheet rather than a dropdown menu, for two reasons. It can say *which* package it is about — a
 * menu that floats over a list gives no way to tell whether it belongs to the row under your thumb
 * or the one above it, which matters a great deal when one of the actions is "uninstall". And it
 * has room to explain the action that needs explaining, instead of offering a bare verb.
 *
 * Every action here is a Binder call into the daemon, which is the process holding the privilege to
 * carry it out, so each one reports what came back rather than assuming it worked.
 *
 * **Re-optimize is the one that is not obvious.** ART inlines small methods into their callers
 * during ahead-of-time compilation, and an inlined method can no longer be hooked — so a module
 * that works on one device silently does nothing on another that happened to compile the target
 * more aggressively. Re-optimizing the app clears that, and it is the first thing to try when a
 * hook "just doesn't fire". It is slow and it is per-app, which is why it belongs on a long press
 * rather than in a settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageActionSheet(
    packageName: String,
    userId: Int,
    appName: String,
    applicationInfo: ApplicationInfo,
    isModule: Boolean,
    onDismiss: () -> Unit,
    onResult: (PackageActionResult) -> Unit,
    /**
     * Where the module's store page is, when there is one to go to.
     *
     * Optional because this sheet is also opened from the Scope screen, over an app that is not a
     * module and has no page. Null there rather than a row that leads nowhere.
     */
    onOpenStore: ((String) -> Unit)? = null,
) {
    // The framework is a scope target, not an app. It has no launcher entry, no settings page in
    // Settings, and nothing ART could re-optimize, so those three rows would lead nowhere. What it
    // does have is a way to be restarted, which takes every running app down with it — so that is
    // what the sheet offers, and what it says.
    val isSystemFramework = packageName == SYSTEM_FRAMEWORK_PACKAGE

    // Asked once, when the sheet opens. Most modules have neither a companion nor a launcher entry,
    // and a row that exists only to report that it has nothing to do is worse than no row.
    var openable by remember(packageName, userId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(packageName, userId) {
        openable =
            ServiceLocator.daemon
                .findAppUi(packageName, userId, companionFirst = isModule)
                .onFailure { e ->
                    Log.w(
                        Constants.TAG,
                        "actions: launch target lookup for $packageName u$userId failed",
                        e,
                    )
                }
                .getOrNull() != null
    }
    var confirmSoftReboot by remember { mutableStateOf(false) }

    // Deliberately not `rememberCoroutineScope()`. Every action on this sheet dismisses it before
    // it starts working, and the dismissal takes this composable out of the composition — which
    // cancels the scope a composition remembered for it. The work launched into that scope then
    // dies at the first `withContext` hop inside the daemon call, before the transaction is ever
    // made, and dies quietly: no daemon call, no error branch, no snackbar, a button that did
    // nothing. Worse, it is a race against the next frame rather than a reliable failure, so it
    // reads as a flaky button. `appScope` belongs to the process and outlives the sheet.
    val scope = ServiceLocator.appScope
    val daemon = ServiceLocator.daemon

    if (confirmSoftReboot) {
        VectorAlertDialog(
            onDismissRequest = { confirmSoftReboot = false },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.action_soft_reboot)) },
            text = { Text(stringResource(R.string.action_soft_reboot_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSoftReboot = false
                        onDismiss()
                        scope.launch(Dispatchers.Main) {
                            daemon.softReboot().onFailure {
                                Log.e(Constants.TAG, "actions: soft reboot request failed", it)
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_soft_reboot),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSoftReboot = false }) {
                    Text(stringResource(R.string.store_cancel))
                }
            },
        )
    }

    val colors = MaterialTheme.colorScheme
    // Left at the default, without skipPartiallyExpanded: that flag removes the half-height stop,
    // which is the only thing a drag on a sheet can *do* other than dismiss it, so a sheet taller
    // than half the screen would open at full height and could not be made smaller. Material adds
    // the stop only when the content is actually taller than half the screen, so short sheets
    // still open at their own height and nothing gains a useless drag.
    val sheetState = rememberModalBottomSheetState()

    // `Dispatchers.Main` because [onResult] reaches a snackbar on the screen underneath, and
    // because that is the thread the composition scope this replaces used to resume on.
    fun finish(block: suspend () -> PackageActionResult) {
        onDismiss()
        scope.launch(Dispatchers.Main) { onResult(block()) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
LocalizedOverlay {

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(applicationInfo = applicationInfo, contentDescription = null, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ScopeViewModel.displayPackageName(packageName),
                    style = VectorMono,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(4.dp))

        if (isModule) {
            ModuleUpdateSection(
                packageName = packageName,
                onOpenStore = onOpenStore,
                onDismiss = onDismiss,
                onResult = onResult,
            )
        }

        // A module is not an app you "open" — most have nothing to look at. What it may have is a
        // companion: the screen its author wrote to configure it, which is what the Xposed settings
        // category marks. Naming it that way is the difference between a control that looks
        // pointless and one that says what it is for.
        if (!isSystemFramework && openable == true)
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.Launch,
            title =
                stringResource(
                    if (isModule) R.string.action_open_companion else R.string.action_launch
                ),
            subtitle =
                if (isModule) stringResource(R.string.action_open_companion_summary) else null,
        ) {
            finish {
                val result = daemon.openAppUi(packageName, userId, companionFirst = isModule)
                if (result.getOrDefault(false)) {
                    PackageActionResult(R.string.action_launched)
                } else {
                    // The row is only drawn once findAppUi resolved a target, so reaching this
                    // branch contradicts what was rendered. One line for both shapes: a failed
                    // transaction carries a throwable, a resolve that found nothing does not.
                    Log.e(
                        Constants.TAG,
                        "actions: open of $packageName for user $userId did nothing, though the " +
                            "row had resolved a target",
                        result.exceptionOrNull(),
                    )
                    PackageActionResult(R.string.action_no_launcher, tone = SnackbarTone.Failure)
                }
            }
        }

        if (!isSystemFramework)
        ActionRow(icon = Icons.Rounded.Info, title = stringResource(R.string.action_app_info)) {
            finish {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val started = daemon.startActivityAsUserWithFeature(intent, userId)
                // The dominant failure is not an exception: the daemon returns a negative int for
                // a null activity manager or a refused user switch.
                val code = started.getOrDefault(-1)
                if (code < 0) {
                    Log.e(
                        Constants.TAG,
                        "actions: opening app info for $packageName as user $userId failed " +
                            "(code $code)",
                        started.exceptionOrNull(),
                    )
                }
                PackageActionResult(R.string.action_opened_info)
            }
        }

        // Force-stopping the framework is a soft reboot, and calling it anything else would hide
        // what the button does: the daemon restarts the primary zygote, so `system_server` and
        // every app forked from it go down together. Named and explained accordingly, and
        // confirmed first — this is the one action on this sheet that ends what the reader is
        // doing everywhere else on the phone.
        if (isSystemFramework) {
            ActionRow(
                icon = Icons.Rounded.RestartAlt,
                title = stringResource(R.string.action_soft_reboot),
                subtitle = stringResource(R.string.action_soft_reboot_summary),
                tint = colors.error,
            ) {
                confirmSoftReboot = true
            }
        } else {
            ActionRow(
                icon = Icons.Rounded.Stop,
                title = stringResource(R.string.action_force_stop),
            ) {
                finish {
                    val result =
                        daemon.forceStopPackage(packageName, userId).onFailure { e ->
                            Log.e(
                                Constants.TAG,
                                "actions: force stop of $packageName (user $userId) failed",
                                e,
                            )
                        }
                    // Unlike uninstall below there is no boolean to weigh: the call answers with
                    // Unit, so the Result itself is the verdict — a failure here means the
                    // transaction never reached a live daemon and nothing was stopped.
                    val ok = result.isSuccess
                    PackageActionResult(
                        if (ok) R.string.action_force_stopped
                        else R.string.action_force_stop_failed,
                        appName,
                        tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                    )
                }
            }
        }

        // Only for a hook target. Re-optimizing recompiles an app so that ART stops inlining the
        // methods a module wants to hook — which is about the app being hooked, not about the
        // module doing the hooking, so on a module it would be an expensive button for nothing.
        if (!isModule && !isSystemFramework) {
            ActionRow(
                icon = Icons.Rounded.Bolt,
                title = stringResource(R.string.action_optimize),
                subtitle = stringResource(R.string.action_optimize_summary),
                tint = colors.primary,
            ) {
                finish {
                    // Slow — this recompiles the app — so the caller is told it started and told
                    // again when it finishes.
                    onResult(
                        PackageActionResult(
                            R.string.action_optimizing,
                            appName,
                            tone = SnackbarTone.Working,
                        )
                    )
                    val ok =
                        daemon
                            .optimizePackage(packageName)
                            .onFailure { e ->
                                Log.e(
                                    Constants.TAG,
                                    "actions: re-optimize of $packageName failed",
                                    e,
                                )
                            }
                            .getOrDefault(false)
                    PackageActionResult(
                        if (ok) R.string.action_optimized else R.string.action_optimize_failed,
                        appName,
                        tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                    )
                }
            }
        }

        if (isModule) {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ActionRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.action_uninstall),
                tint = colors.error,
            ) {
                finish {
                    val result = daemon.uninstallPackage(packageName, userId)
                    val ok = result.getOrDefault(false)
                    // On `!ok`: a device-policy refusal and a missing user come back as a plain
                    // `false`, which onFailure would never see.
                    if (!ok) {
                        Log.e(
                            Constants.TAG,
                            "actions: uninstall of $packageName for user $userId failed",
                            result.exceptionOrNull(),
                        )
                    }
                    PackageActionResult(
                        if (ok) R.string.action_uninstalled else R.string.action_uninstall_failed,
                        appName,
                        tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
}

/**
 * The one shape every row on this sheet takes: a glyph in a tinted disc, the verb, and — when it
 * needs one — the sentence under it saying what the verb costs.
 *
 * The disc is what lets a destructive action look destructive: an error-red glyph on a bare row is
 * easy to miss, the same glyph on a red disc is not. Once one row carries it they all have to, or
 * the bare one reads as a different kind of thing sitting in the same list — which is what the mute
 * switch did while it was borrowing the generic [ToggleRow], a Material list item whose leading
 * icon has no disc and whose text starts ten pixels to the left of every other row here.
 *
 * The measurements are chosen so that one column runs down the whole sheet: 24dp of margin, a 40dp
 * disc and 20dp of gap put every title at 84dp, which is where the header puts the app's name over
 * its 44dp icon and 16dp gap.
 *
 * [trailing] is for a row that carries state as well as an action, and the click behaviour comes in
 * through [modifier] rather than as a callback: a switch row has to announce itself to a screen
 * reader as a switch, not as a button, and only the caller knows which it is.
 */
@Composable
private fun ActionRowLayout(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val accent = tint ?: colors.onSurfaceVariant

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(modifier)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tint == colors.error) colors.error else colors.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * One action.
 *
 * [onClick] is nullable because one row on this sheet is a statement rather than an action — "not
 * in the store" — and a row that ripples under a thumb and then does nothing is a worse answer than
 * one that visibly cannot be pressed.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)?,
) {
    ActionRowLayout(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        icon = icon,
        title = title,
        subtitle = subtitle,
        tint = tint,
    )
}

/** One setting, in the same shape as the actions it sits among. */
@Composable
private fun ActionToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ActionRowLayout(
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        icon = icon,
        title = title,
        subtitle = subtitle,
        tint = null,
        // The whole row is the target, and the switch itself takes no callback, so a tap on it
        // cannot be counted twice.
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * What this module's update situation is, and the two things to do about it.
 *
 * It belongs on the module rather than only in the Store, because this is where the reader already
 * is: the alternative to a row here is remembering the module's name, crossing to the Store tab and
 * finding it again — and the same detour for the switch that silences a module you have decided not
 * to follow.
 *
 * Three states, and the third is the one usually got wrong:
 *
 * * **Out of date** — the update leads, named with the version it brings, because that is the
 *   reason the sheet was opened.
 * * **Current** — no row at all. "Up to date" is a sentence that has to be read to learn nothing.
 * * **Not in the store** — said plainly. Most sideloaded modules are not in the catalogue, and a
 *   silent absence is indistinguishable from "up to date"; someone waiting to be told about a
 *   version that can never be checked is worse off than someone told to check themselves.
 *
 * The catalogue is only asked once it has loaded. Saying "not in the store" while the answer is
 * still on its way would be a guess dressed as a fact.
 */
@Composable
private fun ModuleUpdateSection(
    packageName: String,
    onOpenStore: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    onResult: (PackageActionResult) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = ServiceLocator.settings

    val entries by ServiceLocator.storeEntries.collectAsStateWithLifecycle()
    val catalog by ServiceLocator.store.catalog.collectAsStateWithLifecycle()
    val muted by settings.mutedUpdates.collectAsStateWithLifecycle()
    val channelPreference by settings.updateChannel.collectAsStateWithLifecycle()
    val queue by ServiceLocator.moduleUpdates.state.collectAsStateWithLifecycle()

    val entry = entries[packageName]
    if (entry == null) {
        if (catalog.loaded) {
            ActionRow(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.action_not_in_store),
                subtitle = stringResource(R.string.action_not_in_store_summary),
                onClick = null,
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(4.dp))
        }
        return
    }

    // Asked without the mute, because the sheet still has to show the update to the person who
    // muted it — the whole point of putting the switch here is that they can change their mind in
    // the place where they see the consequence.
    val outdated = entry.copy(updatesMuted = false).upgradable
    val release =
        remember(entry.module, channelPreference) {
            entry.module.releasesOn(StoreChannel.of(channelPreference)).firstOrNull()
        }
    val apks = release?.releaseAssets.orEmpty().filter { it.isApk }
    var confirming by remember { mutableStateOf<ReleaseAsset?>(null) }

    if (outdated) {
        val busy = queue.running && (queue.current?.packageName == packageName)
        ActionRow(
            icon = Icons.Rounded.ArrowCircleUp,
            title =
                stringResource(
                    if (entry.sameVersion) R.string.store_badge_reinstall
                    else R.string.action_update_to,
                    entry.latest?.versionName.orEmpty(),
                ),
            subtitle =
                when {
                    busy -> stringResource(R.string.action_update_running)
                    apks.isEmpty() -> stringResource(R.string.action_update_no_apk)
                    // Several APKs is an architecture split or a variant, and choosing between
                    // them needs the names and sizes the store page already lays out. Sending the
                    // reader there is better than picking one on their behalf.
                    apks.size > 1 -> stringResource(R.string.action_update_choose)
                    // The title already names the version; saying "from 1.1.1" under "Reinstall
                    // 1.1.1" would only invite the reader to look for the difference.
                    entry.sameVersion ->
                        stringResource(
                            R.string.action_reinstall_same,
                            Formatter.formatShortFileSize(context, apks.first().size),
                        )
                    else ->
                        stringResource(
                            R.string.action_update_from,
                            entry.installed?.versionName.orEmpty(),
                            Formatter.formatShortFileSize(context, apks.first().size),
                        )
                },
            tint = if (busy || apks.isEmpty()) colors.onSurfaceVariant else colors.primary,
            onClick = {
                when {
                    busy || apks.isEmpty() -> Unit
                    apks.size > 1 -> {
                        onDismiss()
                        onOpenStore?.invoke(packageName)
                    }
                    else -> confirming = apks.first()
                }
            },
        )
    }

    ActionToggleRow(
        title = stringResource(R.string.store_mute_updates),
        icon = Icons.Rounded.NotificationsOff,
        checked = packageName in muted,
        onCheckedChange = { settings.setUpdatesMuted(packageName, it) },
        subtitle = stringResource(R.string.store_mute_updates_summary),
    )

    if (onOpenStore != null) {
        ActionRow(
            // The same glyph the Store tab carries, because it is the same place.
            icon = Icons.Rounded.CloudDownload,
            title = stringResource(R.string.action_open_store),
            onClick = {
                onDismiss()
                onOpenStore(packageName)
            },
        )
    }

    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    Spacer(Modifier.height(4.dp))

    confirming?.let { asset ->
        ConfirmInstall(
            module = entry.module,
            packageName = packageName,
            asset = asset,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                // Through the queue rather than straight to the installer, so a single update
                // reports itself in the same place a batch does — the line on the Modules header,
                // which outlives this sheet. Closing the sheet is not cancelling the install.
                ServiceLocator.moduleUpdates.start(
                    listOf(
                        ModuleUpdateQueue.Item(
                            packageName = packageName,
                            title = entry.module.title,
                            asset = asset,
                            release = release?.version,
                        )
                    )
                )
                onDismiss()
                onResult(PackageActionResult(R.string.action_update_started))
            },
        )
    }
}
