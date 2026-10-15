package dev.vaultaire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.vaultaire.ui.AppGateway
import dev.vaultaire.ui.VaultaireApp

/**
 * The app's only screen. Nothing on it can be captured (threat-model I9, T12, T30, T31), and the
 * vault locks on screen-off, after time in the background, and when the activity goes away.
 */
class MainActivity : FragmentActivity() {

    private lateinit var gateway: AppGateway

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            gateway.autoLock.screenOff()
        }
    }

    private val processLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = gateway.autoLock.movedToForeground()

        override fun onStop(owner: LifecycleOwner) {
            gateway.revealed.maskAll()
            gateway.autoLock.movedToBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)

        gateway = AppGateway(this, debugBuild = BuildConfig.DEBUG)
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycle)
        setContent { VaultaireApp(gateway, testBuild = BuildConfig.DEBUG) }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        gateway.autoLock.userActivity()
    }

    override fun onStop() {
        super.onStop()
        gateway.revealed.maskAll()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycle)
        unregisterReceiver(screenOff)
        gateway.lock()
        super.onDestroy()
    }
}
