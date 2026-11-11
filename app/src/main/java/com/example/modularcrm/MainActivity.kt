package com.example.modularcrm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.modularcrm.core.AppPlugin
import com.example.modularcrm.core.PluginPage
import com.example.modularcrm.core.PluginRegistry
import com.example.modularcrm.crm.CrmPlugin
import com.example.modularcrm.loyalty.LoyaltyPlugin

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val registry=PluginRegistry()
        val plugins: List<AppPlugin> =listOf(CrmPlugin(),LoyaltyPlugin())
        plugins.forEach{it.onLoad(applicationContext,registry)}
        val pages=plugins.flatMap{it.pages()}
        val settings=plugins.flatMap{it.settings()}
        val security=PinSecurity(this)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme()) {
                var authenticated by remember {mutableStateOf(false)}
                if(!authenticated) PinGate(security){authenticated=true}
                else AppShell(pages,settings,onLock={authenticated=false})
            }
        }
    }
}

@Composable private fun PinGate(auth:PinSecurity,onSuccess:()->Unit) {
    var pin by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    var setup by remember {mutableStateOf(!auth.configured())}
    var failures by remember {mutableIntStateOf(0)}
    var unlockAfter by remember {mutableLongStateOf(0L)}
    Column(Modifier.fillMaxSize().padding(26.dp),verticalArrangement=Arrangement.Center) {
        Text(if(setup)"Придумайте PIN" else "Вход в CRM",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8);error=""},label={Text("PIN (4–8 цифр)")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        if(setup) OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(8)},label={Text("Повторите PIN")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(enabled=pin.length in 4..8,onClick={
            if(setup) {
                if(pin!=confirm) error="PIN не совпадают" else {auth.set(pin);onSuccess()}
            } else if(System.currentTimeMillis()<unlockAfter) error="Слишком много попыток. Повторите позже."
            else if(auth.verify(pin)) {failures=0;onSuccess()}
            else {failures++;pin="";if(failures>=5){unlockAfter=System.currentTimeMillis()+30_000L;failures=0};error="Неверный PIN"}
        }) {Text(if(setup)"Создать PIN" else "Войти")}
        Text("Данные хранятся локально. PIN защищает интерфейс, но не шифрует базу SQLite.",style=MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppShell(pages:List<PluginPage>,settings:List<PluginPage>,onLock:()->Unit) {
    var selected by remember {mutableStateOf(pages.firstOrNull()?.key ?: "")}
    val all=pages+settings
    val current=all.firstOrNull{it.key==selected}
    val isSettings=selected in settings.map{it.key}
    BackHandler(isSettings){selected=pages.firstOrNull()?.key ?: ""}
    Scaffold(topBar={TopAppBar(title={Text("Modular CRM")},actions={
        TextButton(onClick={selected=settings.firstOrNull()?.key ?: selected}){Text("Настройки")}
        TextButton(onClick=onLock){Text("Замок")}
    })}) {padding->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(Modifier.width(104.dp).verticalScroll(rememberScrollState()),header={Text("Модули",style=MaterialTheme.typography.labelSmall)}) {
                pages.forEach {page ->NavigationRailItem(selected=selected==page.key,onClick={selected=page.key},icon={Text("•")},label={Text(page.label)},alwaysShowLabel=true)}
                settings.forEach{page-> NavigationRailItem(selected=selected==page.key,onClick={selected=page.key},icon={Text("⚙")},label={Text(page.label)},alwaysShowLabel=true)}
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {current?.content?.invoke()}
        }
    }
}
