package com.noonmaru.routy.pleos.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import com.noonmaru.routy.pleos.R
import org.json.JSONObject

val RoutyFont=FontFamily(Font(R.font.suit_variable,FontWeight.Normal))
@Composable fun RoutyTheme(content:@Composable ()->Unit) {
    val context=LocalContext.current
    val colors=remember {JSONObject(context.assets.open("routy.tokens.json").bufferedReader().use {it.readText()}).getJSONObject("colors")}
    fun color(name:String)=Color(android.graphics.Color.parseColor(colors.getString(name)))
    val scheme=lightColorScheme(primary=color("primary"),onPrimary=color("onPrimary"),background=color("background"),onBackground=color("ink"),surface=color("surface"),onSurface=color("ink"),surfaceVariant=color("mint"),onSurfaceVariant=color("muted"),outline=color("border"),error=color("error"))
    val base=Typography()
    MaterialTheme(colorScheme=scheme,typography=Typography(
        displayLarge=base.displayLarge.copy(fontFamily=RoutyFont),headlineLarge=base.headlineLarge.copy(fontFamily=RoutyFont),headlineMedium=base.headlineMedium.copy(fontFamily=RoutyFont),
        titleLarge=base.titleLarge.copy(fontFamily=RoutyFont),titleMedium=base.titleMedium.copy(fontFamily=RoutyFont),bodyLarge=base.bodyLarge.copy(fontFamily=RoutyFont),bodyMedium=base.bodyMedium.copy(fontFamily=RoutyFont),labelLarge=base.labelLarge.copy(fontFamily=RoutyFont)),content=content)
}
