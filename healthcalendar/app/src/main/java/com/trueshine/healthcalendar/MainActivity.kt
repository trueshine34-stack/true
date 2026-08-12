package com.trueshine.healthcalendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trueshine.healthcalendar.ui.HealthCalendarApp
import com.trueshine.healthcalendar.ui.theme.HealthCalendarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HealthCalendarTheme {
                HealthCalendarApp()
            }
        }
    }
}
