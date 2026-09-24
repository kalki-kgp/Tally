package dev.pixelchutney.tally

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.ui.TallyRoot
import dev.pixelchutney.tally.ui.theme.TallyTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** A screen a notification asked to open, consumed once navigation has happened. */
    private val pendingRoute = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        requestLocationPermission()
        pendingRoute.value = intent?.getStringExtra(EXTRA_OPEN)

        setContent {
            TallyTheme {
                TallyRoot(
                    pendingRoute = pendingRoute.value,
                    onRouteHandled = { pendingRoute.value = null },
                )
            }
        }
    }

    // singleTask: a notification tapped while Tally is open arrives here instead.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingRoute.value = it }
    }

    /**
     * Without this the capture prompt is silently dropped on Android 13 and later,
     * which would take the whole point of the app with it.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Location is recorded with each payment so that later work can say *where*
     * money goes, not just how much. Asked for once; refusing it costs only the
     * metadata, never the capture.
     *
     * Calendar is deliberately not requested here — it is the more invasive of the
     * two and lives behind its own row in Settings.
     */
    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_INBOX = "inbox"
    }
}
