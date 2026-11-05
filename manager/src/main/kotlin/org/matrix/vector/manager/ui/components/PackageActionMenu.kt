package org.matrix.vector.manager.ui.components

import kotlin.math.abs
import kotlin.math.roundToInt
import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.ui.R as UiR
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.PersonAdd
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import android.text.format.Formatter
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.store.ConfirmInstall
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.ui.store.StoreChannel
import org.matrix.vector.ui.store.releasesOn
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.TextButton
import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel
import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel.Companion.SYSTEM_FRAMEWORK_PACKAGE
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.theme.Mono

/**
 * The actions the long-press sheet can offer, named so an order can outlive them.
 *
 * Declaration order is the default order. A stored order names ids, so an action added or retired
 * in a later build leaves the rest where they are: one nobody has dragged keeps its place here, and
 * a name this build no longer has is ignored when the order is read back.
 */
private enum class ActionId {
    Open,
    AppInfo,
    SoftReboot,
    ForceStop,
    Reoptimize,
    InstallOtherUser,
    Uninstall,
}

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
                    logW("actions: launch target lookup for $packageName u$userId failed", e)
                }
                .getOrNull() != null
    }
    var confirmSoftReboot by remember { mutableStateOf(false) }

    // Collected rather than emitted where written, so the order can be the reader's. Each keeps
    // its own condition: an unusable row is absent, not present and inert.
    val rows = remember { mutableListOf<Pair<ActionId, @Composable () -> Unit>>() }
    var reordering by remember { mutableStateOf(false) }

    // Every user the device has, less the ones already holding this package. Asked of the
    // daemon about *this* package rather than filtered from a cached list, since each of those is
    // filtered for its own purpose — and the module list's tabs cover only users it draws, while
    // a profile holding no modules has no tab and is exactly the one worth offering.
    var installTargets by remember(packageName) { mutableStateOf<List<DeviceUser>>(emptyList()) }
    var choosingUser by remember { mutableStateOf(false) }
    // Not the framework: it is a scope target rather than an installed package, so there is no
    // install state for another user to be missing.
    if (!isSystemFramework) {
        LaunchedEffect(packageName) {
            val daemonService = ServiceLocator.daemon
            val users =
                daemonService
                    .getUsers()
                    .onFailure { e -> logW("actions: user list for $packageName failed", e) }
                    .getOrDefault(emptyList())
            // Subtracted from the user list rather than inverted: the daemon answers empty
            // both for "no user holds it" and for a user service out of reach, and under that
            // failure the user list is empty too, so the difference stays empty.
            val holders =
                daemonService
                    .getPackageUsers(packageName)
                    .onFailure { e -> logW("actions: holders of $packageName failed", e) }
                    .getOrDefault(emptyList())
                    .toSet()
            installTargets = users.filter { it.id !in holders }
        }
    }

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
        SharedAlertDialog(
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
                                logE("actions: soft reboot request failed", it)
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
                    Text(stringResource(UiR.string.store_cancel))
                }
            },
        )
    }

    val colors = MaterialTheme.colorScheme
    // Every value left enabled, rather than dropping PartiallyExpanded: that stop is the only
    // thing a drag on a sheet can *do* other than dismiss it, so a sheet taller than half the
    // screen would open at full height and could not be made smaller.
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // Never shorter than the stop it opens at.
    //
    // Material offers the PartiallyExpanded anchor only to a sheet taller than half its container
    // and places it at exactly half. Three rows below wait on a binder call, so the sheet is first
    // measured without them, lands short of that line and is given no anchor: it retargets to
    // Expanded, the rows arrive mid-animation, and the correction lands as a snap. Measured at
    // 1131px without them and 1483 with, against a stop at 1200. A floor at the stop makes the
    // first target the one it keeps, whatever has answered by then. Dragging up is untouched.
    val minSheetHeight =
        with(LocalDensity.current) {
            // One pixel past half, because the anchor is offered to a sheet *taller* than half.
            (LocalWindowInfo.current.containerSize.height / 2 + 1).toDp()
        }

    // `Dispatchers.Main` because [onResult] reaches a snackbar on the screen underneath, and
    // because that is the thread the composition scope this replaces used to resume on.
    fun finish(block: suspend () -> PackageActionResult) {
        onDismiss()
        scope.launch(Dispatchers.Main) { onResult(block()) }
    }

    fun install(target: DeviceUser, label: String) = finish {
        val result = daemon.installExistingPackageAsUser(packageName, target.id)
        val ok = result.getOrDefault(false)
        // A device-policy refusal and a user that has gone away both come back as a plain false,
        // which onFailure never sees.
        if (!ok) {
            logE("actions: install of $packageName for user ${target.id} failed", result.exceptionOrNull())
        }
        PackageActionResult(
            if (ok) R.string.action_installed_other_user
            else R.string.action_install_other_user_failed,
            label,
            tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
        )
    }

    // A list rather than a confirmation, because the question is *which* user and there is no
    // sensible default to preselect: on a device with a work profile and a private space, either
    // may be the one meant.
    if (choosingUser) {
        SharedAlertDialog(
            onDismissRequest = { choosingUser = false },
            icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
            title = { Text(stringResource(R.string.action_install_other_user)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (isModule) R.string.action_install_other_user_prompt
                            else R.string.action_install_other_user_prompt_app
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    // A row rather than a text button: a profile is recognised by its picture
                    // before its name.
                    installTargets.forEach { target ->
                        val label = target.name ?: target.id.toString()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .clickable { choosingUser = false; install(target, label) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            UserAvatar(userId = target.id, name = label)
                            Spacer(Modifier.width(16.dp))
                            Text(text = label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosingUser = false }) {
                    Text(stringResource(UiR.string.store_cancel))
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(min = minSheetHeight),
        sheetState = sheetState,
    ) {
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
                    style = Mono,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Every sheet but the framework's carries at least three rows -- app info, force
            // stop, and either uninstall or re-optimize -- so there is always an order to change.
            // The framework's has one row and no use for this. Deliberately not `rows.size`: the
            // list is filled below this point, so reading it here sees the previous composition.
            if (!isSystemFramework) {
                IconButton(onClick = { reordering = !reordering }) {
                    Icon(
                        if (reordering) Icons.Rounded.Check else Icons.Rounded.SwapVert,
                        contentDescription = stringResource(R.string.action_reorder),
                        tint = if (reordering) colors.primary else colors.onSurfaceVariant,
                    )
                }
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

        rows.clear()
        fun row(id: ActionId, show: Boolean, content: @Composable () -> Unit) {
            if (show) rows.add(id to content)
        }

        // A module is not an app you "open" — most have nothing to look at. What it may have is a
        // companion: the screen its author wrote to configure it, which is what the Xposed settings
        // category marks. Naming it that way is the difference between a control that looks
        // pointless and one that says what it is for.
        row(ActionId.Open, !isSystemFramework && openable == true) {
        ActionDrawerItem(
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
                    logE(
                        "actions: open of $packageName for user $userId did nothing, though the " +
                            "row had resolved a target",
                        result.exceptionOrNull(),
                    )
                    PackageActionResult(R.string.action_no_launcher, tone = SnackbarTone.Failure)
                }
            }
        }

        }

        row(ActionId.AppInfo, !isSystemFramework) {
        ActionDrawerItem(icon = Icons.Rounded.Info, title = stringResource(R.string.action_app_info)) {
            finish {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // `noUserSwitch = false`, so the daemon switches to the target's profile parent
                // and locks the screen first. That is right here: this is Settings' own details
                // page for a package in that profile, which is not an activity that shows for
                // whichever user is current.
                val started = daemon.startActivityAsUser(intent, userId, noUserSwitch = false)
                // The dominant failure is not an exception: the daemon hands back the activity
                // manager's own start code, so a refused user switch or a screen that would not
                // start arrives as a number rather than as a throw. Started means 0 to 99 — the
                // band `ActivityManager.isStartResultSuccessful` tests, written out because those
                // constants are hidden — with -100 to -1 fatal and 100 to 199 non-fatal refusals.
                val code = started.getOrDefault(-1)
                if (code !in 0..99) {
                    logE(
                        "actions: opening app info for $packageName as user $userId failed " +
                            "(code $code)",
                        started.exceptionOrNull(),
                    )
                }
                PackageActionResult(R.string.action_opened_info)
            }
        }
        }

        // Force-stopping the framework is a soft reboot, and calling it anything else would hide
        // what the button does: the daemon restarts the primary zygote, so `system_server` and
        // every app forked from it go down together. Named and explained accordingly, and
        // confirmed first — this is the one action on this sheet that ends what the reader is
        // doing everywhere else on the phone.
        row(ActionId.SoftReboot, isSystemFramework) {
            ActionDrawerItem(
                icon = Icons.Rounded.RestartAlt,
                title = stringResource(R.string.action_soft_reboot),
                subtitle = stringResource(R.string.action_soft_reboot_summary),
                tint = colors.error,
            ) {
                confirmSoftReboot = true
            }
        }

        row(ActionId.ForceStop, !isSystemFramework) {
            ActionDrawerItem(
                icon = Icons.Rounded.Stop,
                title = stringResource(R.string.action_force_stop),
            ) {
                finish {
                    val result =
                        daemon.forceStopPackage(packageName, userId).onFailure { e ->
                            logE("actions: force stop of $packageName (user $userId) failed", e)
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
        row(ActionId.Reoptimize, !isModule && !isSystemFramework) {
            ActionDrawerItem(
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
                                logE("actions: re-optimize of $packageName failed", e)
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

        // Only when some user lacks it. Otherwise the row's whole function is to report that
        // there is nowhere to send it, and on a single-user device it would never do anything.
        row(ActionId.InstallOtherUser, installTargets.isNotEmpty()) {
            ActionDrawerItem(
                icon = Icons.Rounded.PersonAdd,
                title = stringResource(R.string.action_install_other_user),
                // Opposite ends of the same relation: a module goes into a profile to be
                // scoped there, an app so a module already there can reach it.
                subtitle =
                    stringResource(
                        if (isModule) R.string.action_install_other_user_summary
                        else R.string.action_install_other_user_summary_app
                    ),
                tint = colors.primary,
            ) {
                choosingUser = true
            }
        }

        row(ActionId.Uninstall, isModule) {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ActionDrawerItem(
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
                        logE(
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

        OrderedActions(
            kind = if (isModule) "module" else "app",
            reordering = reordering,
            rows = rows.toList(),
        )

        Spacer(Modifier.height(24.dp))
    }
}
}

/**
 * The collected rows, in the reader's order, with a way to change it.
 *
 * Arrows behind a toggle, not a drag. A drag has to be discovered before it can be used, it is
 * invisible to a screen reader, and on this sheet it contends with the sheet's own drag for the
 * same movement — which it loses, since the sheet takes the pointer back whenever the finger
 * strays sideways. Two buttons say what they do, move exactly one place, and work the same for
 * everyone.
 *
 * The rows keep their ordinary appearance while being moved, so what is reordered is what the
 * reader sees. They are only made inert: in this mode a tap belongs to the arrows.
 */
@Composable
private fun ColumnScope.OrderedActions(
    kind: String,
    reordering: Boolean,
    rows: List<Pair<ActionId, @Composable () -> Unit>>,
) {
    val settings = ServiceLocator.settings
    val revision by settings.actionOrderRevision.collectAsStateWithLifecycle()
    // Sorted by where the stored order names each id, with anything it does not name kept in
    // declaration order behind what it does.
    var order by
        remember(rows.map { it.first }, revision) {
            val stored = settings.actionOrder(kind)
            mutableStateOf(
                rows.map { it.first }.sortedBy { id ->
                    stored.indexOf(id.name).takeIf { it >= 0 } ?: (stored.size + id.ordinal)
                }
            )
        }

    fun move(id: ActionId, by: Int) {
        val at = order.indexOf(id)
        val to = at + by
        if (at < 0 || to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(at)) }
        settings.setActionOrder(kind, order.map { it.name })
    }

    val content = rows.toMap()
    order.forEachIndexed { index, id ->
        val draw = content[id] ?: return@forEachIndexed
        if (!reordering) {
            draw()
            return@forEachIndexed
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            // In a Column, because an entry may emit more than the row itself -- uninstall brings
            // its own divider -- and a Box would stack them on top of one another. Narrowed by
            // the width of the two buttons, so a long subtitle wraps clear of them rather than
            // running underneath; the row reflowing is also what says the mode is on.
            Column(Modifier.padding(end = 96.dp)) { draw() }
            // The row keeps its own look and is only made inert: this box swallows the tap it
            // would otherwise take, since here a tap belongs to the arrows.
            Box(
                Modifier.matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
            )
            Row(
                modifier = Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { move(id, -1) }, enabled = index > 0) {
                    Icon(
                        Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_move_up),
                    )
                }
                IconButton(onClick = { move(id, 1) }, enabled = index < order.lastIndex) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_move_down),
                    )
                }
            }
        }
    }
}

/**
 * One setting, in the same shape — the shared [ActionDrawerItem] disc layout — as the actions it
 * sits among.
 *
 * Not an [ActionDrawerItem] click: a switch row has to announce itself to a screen reader as a
 * switch, not as a button, so the whole-row toggle comes in through the modifier and the item is
 * left without an onClick. The switch itself takes no callback, so a tap on it cannot be counted
 * twice.
 */
@Composable
private fun ActionToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ActionDrawerItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
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
            ActionDrawerItem(
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
        ActionDrawerItem(
            icon = Icons.Rounded.ArrowCircleUp,
            title =
                stringResource(
                    if (entry.sameVersion) UiR.string.store_badge_reinstall
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
        title = stringResource(UiR.string.store_mute_updates),
        icon = Icons.Rounded.NotificationsOff,
        checked = packageName in muted,
        onCheckedChange = { settings.setUpdatesMuted(packageName, it) },
        subtitle = stringResource(UiR.string.store_mute_updates_summary),
    )

    if (onOpenStore != null) {
        ActionDrawerItem(
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
            // Shell-mode installs commit with no further system prompt; the dialog says so.
            silent =
                context.checkSelfPermission("android.permission.INSTALL_PACKAGES") ==
                    PackageManager.PERMISSION_GRANTED,
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

/**
 * A user's photo, or a letter in a circle when it has none — which is most users, so the circle is
 * the ordinary case. Its colour comes from the user id, so one profile looks the same every time.
 */
@Composable
private fun UserAvatar(userId: Int, name: String) {
    var photo by remember(userId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(userId) {
        photo =
            ServiceLocator.daemon
                .getUserIcon(userId)
                .onFailure { e -> logW("actions: photo of user $userId unavailable", e) }
                .getOrNull()
                ?.asImageBitmap()
    }

    val shown = photo
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val tones: List<Pair<Color, Color>> =
        listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
        )
    val (fill, ink) = tones[userId.mod(tones.size)]
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = ink,
        )
    }
}
