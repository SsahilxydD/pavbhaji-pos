package com.pavbhaji.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pavbhaji.pos.ui.PBTheme
import com.pavbhaji.pos.ui.PosApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Deps.init(this)
        Deps.live.start()
        setContent {
            PBTheme { PosApp() }
        }
    }
}
