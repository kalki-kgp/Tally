package dev.pixelchutney.tally.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.work.TallyScheduler
import javax.inject.Inject

/**
 * Reboots and app updates both drop scheduled work. Re-arming here is what keeps
 * the weekly digest and the detection-health check alive across restarts.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: TallyScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                scheduler.scheduleAll()
                UsageWatcherService.start(context)
            }
        }
    }
}
