package org.matrix.vector.manager.demo

import org.lsposed.lspd.ILSPManagerService
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.VectorApp
import org.matrix.vector.manager.ui.theme.LocalizedContent
import org.matrix.vector.manager.ui.theme.VectorTheme

/**
 * The way in to the scripted states, and the reason this whole thing is a separate source set.
 *
 * It exists only in `src/debug`, so a release build does not merely branch around it — there is no
 * such class to compile and no such activity in the merged manifest. That distinction matters more
 * here than in an ordinary app: a demo mode that could be switched on in a release build would be a
 * way to make the manager report the framework as healthy when it is not, which is the one lie this
 * app must never be able to tell. A reviewer can confirm it by looking for
 * `org.matrix.vector.manager.demo` in a release APK's classes and finding nothing.
 *
 * It hosts the app itself rather than launching MainActivity, and that is not a stylistic choice.
 * The first version did launch it, and every scenario silently did nothing: `ParasiticManagerHooker`
 * intercepts the manager activity starting and hands the *real* binder to `Constants.setBinder`,
 * overwriting whatever was bound a moment earlier. Even "no daemon at all" came up reporting a
 * healthy framework — the failure mode a test harness can least afford, since it looks like a pass.
 * Rendering VectorApp here means no manager activity is ever launched, so nothing re-binds behind
 * us.
 */
class DemoActivity : ComponentActivity() {

    /**
     * Captured before anything is bound, so a scenario can delegate what it does not script.
     *
     * Frequently null: the hooker sends the binder when the app's class loader is first asked for,
     * which happens after this activity is constructed. That is fine — a scenario with no real
     * daemon behind it simply has empty lists, and the scripted answers are the point.
     */
    private val realService = ServiceLocator.service.value

    /**
     * What the demo insists the binder is, and whether it is currently insisting.
     *
     * `ParasiticManagerHooker` hands the real binder to `Constants.setBinder` when the manager's
     * class loader is first obtained — which is *after* a scenario has been chosen, so a single
     * bind was quietly undone a moment later and every scenario reported a healthy framework. This
     * re-asserts the choice whenever something else replaces it. It settles immediately: the next
     * emission is the pinned value, which the collector then ignores.
     */
    private var pinned: ILSPManagerService? = null

    private var pinning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.attach(this)

        lifecycleScope.launch {
            ServiceLocator.service.collect { current ->
                if (pinning && current !== pinned) ServiceLocator.bind(pinned)
            }
        }

        setContent {
            var scenario by remember { mutableStateOf<DemoScenario?>(null) }

            if (scenario == null) {
                VectorTheme { ScenarioList { picked -> scenario = install(picked) } }
            } else {
                // Back returns to the picker rather than leaving, so trying six states in a row is
                // not six trips through the launcher. The real binder goes back on the way out, so
                // the app is never left holding a fake after the demo is done with it.
                BackHandler {
                    // Stop insisting first, or the collector would immediately undo this.
                    pinning = false
                    ServiceLocator.bind(realService)
                    scenario = null
                }
                LocalizedContent { VectorTheme { VectorApp() } }
            }
        }
    }

    private fun install(scenario: DemoScenario): DemoScenario {
        if (scenario.id == "healthy") {
            // Deliberately does not bind: realService is usually null here, and forcing that would
            // break the app rather than restore it. Letting go is enough — whatever the hooker
            // bound is the real thing.
            pinning = false
            pinned = null
            return scenario
        }
        pinned =
            if (!scenario.connected) null else FakeManagerService(scenario, realService)
        pinning = true
        ServiceLocator.bind(pinned)
        return scenario
    }
}

@Composable
private fun ScenarioList(onPick: (DemoScenario) -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(
                        "Demo states",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Device states that cannot be reached without breaking the phone. " +
                            "The scripted answers stop at the binder; everything above it is " +
                            "real. Back returns here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            items(DEMO_SCENARIOS, key = { it.id }) { scenario ->
                ListItem(
                    modifier = Modifier.clickable { onPick(scenario) },
                    supportingContent = { Text(scenario.summary) },
                ) { Text(scenario.title) }
            }
        }
    }
}
