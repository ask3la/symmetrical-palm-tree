package com.example.modularcrm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Orange & warm neutrals in daylight; deep navy surfaces with high-contrast amber at night.
private val LightColors = lightColorScheme(
    primary=Color(0xFFE95A00), onPrimary=Color.White,
    primaryContainer=Color(0xFFFFDBC7), onPrimaryContainer=Color(0xFF542000),
    secondary=Color(0xFF81543D), onSecondary=Color.White,
    background=Color(0xFFFFF9F5), onBackground=Color(0xFF221A16),
    surface=Color(0xFFFFFCFA), onSurface=Color(0xFF221A16),
    surfaceVariant=Color(0xFFF5EDE8), onSurfaceVariant=Color(0xFF615650),
    outline=Color(0xFF9B8D85), error=Color(0xFFBA1A1A)
)
private val DarkColors = darkColorScheme(
    primary=Color(0xFFFFB080), onPrimary=Color(0xFF592200),
    primaryContainer=Color(0xFF923C00), onPrimaryContainer=Color(0xFFFFDBC7),
    secondary=Color(0xFFE8BCA3), onSecondary=Color(0xFF462A1B),
    background=Color(0xFF12151D), onBackground=Color(0xFFF0EFF5),
    surface=Color(0xFF1C202B), onSurface=Color(0xFFF0EFF5),
    surfaceVariant=Color(0xFF2B3040), onSurfaceVariant=Color(0xFFC4C5D0),
    outline=Color(0xFF868B9B), error=Color(0xFFFFB4AB)
)

@Composable fun WorkspaceTheme(mode:String,content:@Composable ()->Unit) {
    val dark= when(mode){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors,typography=Typography(),content=content)
}
