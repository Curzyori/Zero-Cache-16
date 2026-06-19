package com.zerocache

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.zerocache.ui.DashboardViewModel
import com.zerocache.ui.screen.DashboardScreen
import com.zerocache.ui.theme.ZeroCacheTheme
import com.zerocache.util.LocaleManager

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroCacheTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onLanguageToggle = { newLang ->
                            LocaleManager.setLanguage(this, newLang)
                            recreate()
                        },
                        onOpenAccessibilitySettings = {
                            com.zerocache.util.SettingsOpener.openAccessibilitySettings(this)
                        },
                        onOpenUsageSettings = {
                            com.zerocache.util.SettingsOpener.openUsageAccessSettings(this)
                        }
                    )
                }
            }
        }
    }
}
