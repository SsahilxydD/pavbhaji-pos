package com.pavbhaji.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pavbhaji.pos.data.AppUpdater
import com.pavbhaji.pos.ui.PBTheme
import com.pavbhaji.pos.ui.PosApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Content draws behind the system bars; each screen picks its own
        // status-bar icon tint (StatusBarIcons) since headers vary dark/light.
        enableEdgeToEdge()
        Deps.init(this)
        Deps.live.start()
        setContent {
            PBTheme { PosApp() }
        }
    }

    // If Android decided it wanted a tap after all, this is the moment to ask:
    // the phone has just been picked up, and nobody is mid-order yet.
    override fun onResume() {
        super.onResume()
        AppUpdater.consumeUserAction(this)
    }

    // Phone put down, screen off, app switched away — the only safe moment to
    // replace the app under itself, because doing so ends this process.
    override fun onStop() {
        super.onStop()
        AppUpdater.applyStaged(this)
    }
}
