package com.pavbhaji.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
}
