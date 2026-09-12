package io.github.neurone00.adblock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.neurone00.adblock.ui.AdBlockTheme
import io.github.neurone00.adblock.ui.AppRoot
import io.github.neurone00.adblock.update.InstallReceiver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AdBlockTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        InstallReceiver.foreground = true
    }

    override fun onPause() {
        InstallReceiver.foreground = false
        super.onPause()
    }
}
