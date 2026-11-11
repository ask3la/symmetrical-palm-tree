package com.example.modularcrm

import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val engine=PluginEngine(applicationContext)
        val security=PinSecurity(this)
        val preferences=getSharedPreferences("workspace_ui",MODE_PRIVATE)
        setContent {
            var themeMode by remember { mutableStateOf(preferences.getString("theme","system")?:"system") }
            WorkspaceTheme(themeMode) {
                var unlocked by remember { mutableStateOf(false) }
                if(!unlocked) PinScreen(security){unlocked=true}
                else Workspace(engine,themeMode,{next->themeMode=next;preferences.edit().putString("theme",next).apply()}){unlocked=false}
            }
        }
    }
}

@Composable private fun PinScreen(security:PinSecurity,onUnlock:()->Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val configuring=remember { !security.configured() }
    var attempts by remember { mutableIntStateOf(0) }
    var nextAttempt by remember { mutableLongStateOf(0L) }
    Column(Modifier.fillMaxSize().padding(30.dp),verticalArrangement=Arrangement.Center) {
        Text(if(configuring)"Новый PIN" else "Вход",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(15.dp))
        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("PIN (4–8 цифр)")},visualTransformation=PasswordVisualTransformation())
        if(configuring) OutlinedTextField(confirmation,{confirmation=it.filter(Char::isDigit).take(8)},label={Text("Повтор PIN")},visualTransformation=PasswordVisualTransformation())
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Button(enabled=pin.length in 4..8,onClick={
            if(configuring) {if(pin!=confirmation)error="PIN не совпадает" else {security.set(pin);onUnlock()}}
            else if(System.currentTimeMillis()<nextAttempt)error="Повторите попытку через 30 секунд"
            else if(security.verify(pin))onUnlock()
            else {attempts++;error="Неверный PIN";pin="";if(attempts>=5){nextAttempt=System.currentTimeMillis()+30_000;attempts=0}}
        }) {Text(if(configuring)"Сохранить" else "Войти")}
        Text("PIN защищает вход. Данные SQLite пока не зашифрованы.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PluginIcon(id:String) {
    val icon=when(id){"crm"->Icons.Default.People;"hardware"->Icons.Default.Dns;"licenses"->Icons.Default.VpnKey;"support"->Icons.Default.SupportAgent;else->Icons.Default.Extension}
    Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Workspace(engine:PluginEngine,themeMode:String,onTheme:(String)->Unit,onLock:()->Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(engine) {engine.changed={refresh++};onDispose{engine.changed=null}}
    val plugins=refresh.let {engine.plugins.toList()}
    var pluginId by remember { mutableStateOf("crm") }
    var pageId by remember { mutableStateOf("clients") }
    var mode by remember { mutableStateOf("home") }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val active=plugins.firstOrNull{it.id==pluginId} ?: plugins.firstOrNull()
    val pages=active?.pages.orEmpty()
    val actualPage=pages.firstOrNull{it.str("id")==pageId}?:pages.firstOrNull()
    val pageTitle=actualPage?.str("title")?:active?.name?:"Раздел"
    ModalNavigationDrawer(drawerState=drawer,drawerContent={
        ModalDrawerSheet(modifier=Modifier.width(304.dp),drawerContainerColor=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp,18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.size(49.dp),contentAlignment=Alignment.Center){Icon(Icons.Default.Extension,null,tint=MaterialTheme.colorScheme.primary)}}
                    Column {Text("Modular",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("Workspace",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
                }
                HorizontalDivider(Modifier.padding(bottom=12.dp))
                NavigationDrawerItem(label={Text("Главная")},icon={Icon(Icons.Default.Home,null)},selected=mode=="home",onClick={mode="home";scope.launch{drawer.close()}},shape=RoundedCornerShape(15.dp))
                Text("ПЛАГИНЫ",Modifier.padding(start=16.dp,top=19.dp,bottom=9.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                plugins.forEach {p ->
                    NavigationDrawerItem(label={Text(p.name)},icon={PluginIcon(p.id)},selected=mode=="plugin"&&pluginId==p.id,
                        onClick={pluginId=p.id;pageId=p.pages.firstOrNull()?.str("id").orEmpty();mode="plugin";scope.launch{drawer.close()}},shape=RoundedCornerShape(15.dp))
                }
                HorizontalDivider(Modifier.padding(vertical=12.dp))
                NavigationDrawerItem(label={Text("Управление плагинами")},icon={Icon(Icons.Default.Extension,null)},selected=mode=="manager",onClick={mode="manager";scope.launch{drawer.close()}},shape=RoundedCornerShape(15.dp))
                NavigationDrawerItem(label={Text("Настройки")},icon={Icon(Icons.Default.Settings,null)},selected=mode=="settings",onClick={mode="settings";scope.launch{drawer.close()}},shape=RoundedCornerShape(15.dp))
                NavigationDrawerItem(label={Text("Заблокировать")},icon={Icon(Icons.Default.Lock,null)},selected=false,onClick={onLock();scope.launch{drawer.close()}},shape=RoundedCornerShape(15.dp))
            }
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (mode) {
                                "home" -> "Modular Workspace"
                                "plugin" -> active?.name ?: "Плагин"
                                "manager" -> "Плагины"
                                else -> "Настройки"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                        }
                    },
                    actions = {
                        if (mode == "home") {
                            IconButton(onClick = { mode = "settings" }) {
                                Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (mode == "plugin" && pages.isNotEmpty()) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        pages.take(4).forEachIndexed { index, page ->
                            NavigationBarItem(
                                selected = page.str("id") == actualPage?.str("id"),
                                onClick = { pageId = page.str("id") },
                                icon = {
                                    Icon(
                                        when (index) {
                                            0 -> Icons.Default.People
                                            1 -> Icons.Default.CheckCircle
                                            2 -> Icons.Default.CalendarMonth
                                            else -> Icons.Default.MoreHoriz
                                        },
                                        contentDescription = null
                                    )
                                },
                                label = {
                                    Text(page.str("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            )
                        }
                        if (pages.size > 4) {
                            var expanded by remember { mutableStateOf(false) }
                            NavigationBarItem(
                                selected = pages.drop(4).any { it.str("id") == pageId },
                                onClick = { expanded = true },
                                icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                                label = { Text("Ещё") }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                pages.drop(4).forEach { page ->
                                    DropdownMenuItem(
                                        text = { Text(page.str("title")) },
                                        onClick = { pageId = page.str("id"); expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) {padding->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when(mode) {
                    "home" -> HomeScreen(engine,refresh,onPlugin={id->val p=plugins.firstOrNull{it.id==id};if(p!=null){pluginId=id;pageId=p.pages.firstOrNull()?.str("id").orEmpty();mode="plugin"}},onManager={mode="manager"})
                    "manager" -> ManagerScreen(engine)
                    "settings" -> SettingsScreen(engine,refresh,themeMode,onTheme)
                    else -> if(actualPage!=null && active!=null) {
                        when(actualPage.str("kind","collection")) {
                            "stale" -> StaleScreen(engine,actualPage,refresh)
                            "metrics" -> MetricsScreen(engine,actualPage,refresh)
                            else -> CollectionScreen(engine,"${active.id}.${actualPage.str("collection")}",refresh)
                        }
                    } else EmptyState("В плагине пока нет разделов","Выбери другой плагин или установи расширение")
                }
            }
        }
    }
}

@Composable private fun EmptyState(title:String,detail:String) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Extension,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun HomeScreen(engine:PluginEngine,revision:Int,onPlugin:(String)->Unit,onManager:()->Unit) {
    val plugins=revision.let{engine.plugins.toList()}
    val clients=remember(revision){engine.data.list("crm.clients")}
    val tasks=remember(revision){engine.data.list("crm.tasks")}
    val hardware=remember(revision){engine.data.list("hardware.devices")}
    val licenses=remember(revision){engine.data.list("licenses.keys")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("Добрый день!",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Все важные данные в одном месте",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            MetricTile("Клиенты",clients.size,Icons.Default.People,Modifier.weight(1f)){onPlugin("crm")}
            MetricTile("Оборудование",hardware.size,Icons.Default.Dns,Modifier.weight(1f)){onPlugin("hardware")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            MetricTile("Лицензии",licenses.size,Icons.Default.VpnKey,Modifier.weight(1f)){onPlugin("licenses")}
            MetricTile("Задачи",tasks.size,Icons.Default.CheckCircle,Modifier.weight(1f)){onPlugin("crm")}
        }
        Text("Быстрый доступ",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        plugins.forEach{p->ElevatedCard(modifier=Modifier.fillMaxWidth().clickable{onPlugin(p.id)},shape=RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(14.dp)){Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){PluginIcon(p.id)}}
                Column(Modifier.weight(1f)){Text(p.name,style=MaterialTheme.typography.titleMedium);Text("${p.pages.size} разделов",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }}
        OutlinedButton(onClick=onManager,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Extension,null);Spacer(Modifier.width(8.dp));Text("Установить плагин .mcrm") }
    }
}

@Composable private fun MetricTile(title:String,count:Int,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    ElevatedCard(modifier=modifier.clickable{onClick()},shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.labelLarge,maxLines=1)
            Text(count.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable private fun ManagerScreen(engine:PluginEngine) {
    var result by remember{mutableStateOf("")}
    var pending by remember{mutableStateOf<android.net.Uri?>(null)}
    var manifest by remember{mutableStateOf<JSONObject?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) runCatching {engine.inspect(uri)}.onSuccess {meta ->
            pending=uri;manifest=meta
        }.onFailure {result="Ошибка проверки: ${it.message}"}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Управление плагинами",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Импортируй .mcrm: новый плагин установится, а более новая версия существующего обновит его без удаления записей.")
        Button(onClick={picker.launch(arrayOf("application/zip","application/octet-stream","*/*"))},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Загрузить плагин (.mcrm)")}
        if(result.isNotEmpty())Text(result)
        engine.plugins.forEach{p->ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){
            Text(p.name,style=MaterialTheme.typography.titleMedium)
            Text("${p.id} · версия ${p.manifest.str("version")} · API ${p.manifest.optInt("api")}")
            Text("Разделы: ${p.pages.size}; расширения: ${p.extensions.size}; обработчики: ${p.hooks.size}")
            if(p.id!="crm") TextButton(onClick={result=runCatching{engine.remove(p.id);"Удалён ${p.name}"}.getOrElse{it.message?:"Ошибка"}}){Text("Удалить")}
        }}}
    }
    if(manifest!=null && pending!=null) {
        val meta=manifest!!
        val array=meta.optJSONObject("permissions")?.optJSONArray("write")
        val requested=(0 until (array?.length()?:0)).map{array!!.getString(it)}.toSet()
        val current=engine.plugins.firstOrNull{it.id==meta.str("id")}
        val isUpdate=current!=null
        AlertDialog(onDismissRequest={manifest=null;pending=null},title={Text(if(isUpdate)"Обновление ${meta.str("name")}" else "Установка ${meta.str("name")}")},
            text={Text((if(isUpdate)"${current?.manifest?.str("version")} → ${meta.str("version")}. Данные SQLite сохраняются. " else "Новый плагин. ") + (if(requested.isEmpty())"Дополнительные разрешения не запрашиваются." else "Запрошено изменение коллекций: ${requested.joinToString()}. Разрешай только доверенным файлам."))},
            confirmButton={TextButton(onClick={
                val uri=pending!!
                runCatching{engine.install(uri,requested)}.onSuccess{result=(if(isUpdate)"Обновлён " else "Установлен ")+it.name+" до версии "+it.manifest.str("version")}.onFailure{result="Ошибка: ${it.message}"}
                manifest=null;pending=null
            }){Text(if(isUpdate)"Обновить" else "Установить")}},
            dismissButton={TextButton(onClick={manifest=null;pending=null}){Text("Отмена")}})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(engine:PluginEngine,revision:Int,themeMode:String,onTheme:(String)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Внешний вид",style=MaterialTheme.typography.titleLarge)
        Text("Тема приложения",style=MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("light" to "Светлая","dark" to "Тёмная","system" to "Системная").forEachIndexed {i,(key,title)->
                SegmentedButton(selected=themeMode==key,onClick={onTheme(key)},shape=SegmentedButtonDefaults.itemShape(index=i,count=3),label={Text(title)})
            }
        }
        HorizontalDivider()
        Text("Настройки плагинов",style=MaterialTheme.typography.titleLarge)
        engine.plugins.forEach{p-> if(p.settings.isNotEmpty()) {
            Text(p.name,style=MaterialTheme.typography.titleMedium)
            p.settings.forEach{setting->
                var value by remember(p.id,setting.str("id"),revision){mutableStateOf(engine.data.getSetting(p.id,setting.str("id"),setting.str("default")))}
                OutlinedTextField(value,{value=it;engine.data.setSetting(p.id,setting.str("id"),it)},label={Text(setting.str("title"))},modifier=Modifier.fillMaxWidth())
            }
        }}
        val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
        Button(onClick={
            val list=mutableListOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
            if(Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.launch(list.toTypedArray())
        }) {Text("Разрешить календарь и уведомления")}
        Text("Для создания системных событий и напоминаний выдайте разрешения. Плагины обращаются только к ограниченным действиям ядра.",style=MaterialTheme.typography.bodySmall)
    }
}

/** Generic analytics pages are declared by a plugin, not tied to CRM classes. */
@Composable private fun MetricsScreen(engine:PluginEngine,page:JSONObject,revision:Int) {
    val targets=page.optJSONArray("targets")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(page.str("title","Статистика"),style=MaterialTheme.typography.headlineSmall)
        if(targets!=null)repeat(targets.length()) {n->
            val target=targets.getJSONObject(n)
            val count=remember(revision){engine.data.list(target.str("collection")).size}
            ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){
                Text(target.str("title"),style=MaterialTheme.typography.titleMedium)
                Text(count.toString(),style=MaterialTheme.typography.headlineLarge)
            }}
        }
    }
}

@Composable private fun StaleScreen(engine:PluginEngine,page:JSONObject,revision:Int) {
    val clients=remember(revision){engine.data.list(page.str("collection"))}
    val interactions=remember(revision){engine.data.list(page.str("activity_collection"))}
    val config=page.str("setting","no_contact_days")
    val days=engine.data.getSetting(page.str("setting_plugin","crm"),config,"30").toIntOrNull()?.coerceAtLeast(1)?:30
    val threshold=System.currentTimeMillis()-days*86_400_000L
    val stale=clients.filter {client->
        interactions.filter{it.data.str(page.str("link_field","client_id"))==client.id.toString()}
            .maxOfOrNull{it.data.str(page.str("date_field","timestamp")).toLongOrNull()?:it.createdAt}?.let{it<threshold}?: (client.createdAt<threshold)
    }
    var open by remember{mutableStateOf<Long?>(null)}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Нет контактов более $days дней",style=MaterialTheme.typography.titleLarge)
        Text("Клиентов: ${stale.size}")
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(stale,key={it.id}){client->ElevatedCard(Modifier.fillMaxWidth().clickable{open=client.id}){
                Text(engine.nameOf(page.str("collection"),client.id.toString()),Modifier.padding(16.dp))
            }}
        }
    }
    if(open!=null)DetailDialog(engine,page.str("collection"),open!!,revision,onClose={open=null},onEdit={})
}

@Composable private fun CollectionScreen(engine:PluginEngine,collection:String,revision:Int,relationField:String?=null,relationId:String?=null) {
    var selected by remember(collection,relationId){mutableStateOf<Long?>(null)}
    var editing by remember(collection,relationId){mutableStateOf(false)}
    var editingId by remember(collection,relationId){mutableStateOf<Long?>(null)}
    var search by remember(collection){mutableStateOf("")}
    val def=engine.collectionDef(collection)
    val rows=remember(collection,revision,relationId){engine.data.list(collection,relationField,relationId)}
    val title=def?.str("title",collection)?:collection
    if(selected!=null){
        FullDetailScreen(engine,collection,selected!!,revision,onClose={selected=null},onEdit={editingId=selected;editing=true})
    }else {
        Scaffold(containerColor=MaterialTheme.colorScheme.background,floatingActionButton={FloatingActionButton(onClick={editingId=null;editing=true},containerColor=MaterialTheme.colorScheme.primary){Icon(Icons.Default.Add,"Добавить",tint=MaterialTheme.colorScheme.onPrimary)}}){padding->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                OutlinedTextField(search,{search=it},label={Text("Поиск")},leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(18.dp))
                val filtered=rows.filter{it.data.toString().contains(search,true)}
                Text("Найдено: ${filtered.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(filtered.isEmpty())EmptyState("Записей пока нет","Нажми +, чтобы создать первую запись")
                LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(bottom=85.dp)) {
                    items(filtered,key={it.id}){r->
                        ElevatedCard(modifier=Modifier.fillMaxWidth().clickable{selected=r.id},shape=RoundedCornerShape(18.dp)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(15.dp)) {Box(Modifier.size(46.dp),contentAlignment=Alignment.Center){Icon(if(collection=="crm.clients")Icons.Default.Business else Icons.Default.List,null,tint=MaterialTheme.colorScheme.primary)}}
                                Column(Modifier.weight(1f)) {
                                    Text(engine.nameOf(collection,r.id.toString()),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    engine.fields(collection).filter{it.str("type")!="multiline" && !it.optBoolean("hidden")}.take(2).forEach{f->val v=r.data.str(f.str("id"));if(v.isNotBlank())Text(display(engine,f,v),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
                                }
                                Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if(editing) RecordForm(engine,collection,editingId,relationField,relationId,onClose={editing=false})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FullDetailScreen(engine:PluginEngine,collection:String,id:Long,revision:Int,onClose:()->Unit,onEdit:()->Unit) {
    val record=remember(collection,id,revision){engine.data.read(collection,id)}
    val tabs=remember(collection,revision){engine.extensions("$collection.detail.tabs","detail_tab")}
    var tab by remember(collection,id){mutableIntStateOf(0)}
    val name=record?.let{engine.nameOf(collection,it.id.toString())}?:"Карточка"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose){Icon(Icons.Default.ArrowBack,"Назад")}
            Text("Карточка",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(onClick=onEdit){Text("Изменить")}
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(if(collection=="crm.clients")"Клиент · ${record?.data?.str("type").orEmpty()}" else collection,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScrollableTabRow(selectedTabIndex=tab,edgePadding=12.dp,containerColor=MaterialTheme.colorScheme.background) {
            Tab(tab==0,onClick={tab=0},text={Text("Общее")})
            tabs.forEachIndexed {i,e->Tab(tab==i+1,onClick={tab=i+1},text={Text(e.str("title"))})}
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(tab==0){
                ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Основная информация",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        record?.let{r->
                            var currentGroup=""
                            engine.fields(collection).filterNot{it.optBoolean("hidden")}.forEach{f->
                                val visible=f.optJSONObject("visible_when")?.let{condition->r.data.str(condition.str("field"))==condition.str("equals")}?:true
                                val v=r.data.str(f.str("id"))
                                if(visible && v.isNotBlank()) {
                                    val group=f.str("group")
                                    if(group.isNotBlank() && group!=currentGroup) {
                                        if(currentGroup.isNotEmpty()) HorizontalDivider()
                                        Text(group,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                                        currentGroup=group
                                    }
                                    Text(f.str("label",f.str("id")),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if(f.str("type")=="file") {
                                        val context=LocalContext.current
                                        TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(v)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}}){Text("Открыть документ")}
                                    } else Text(display(engine,f,v),style=MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                engine.extensions("$collection.labels","label_patch").forEach{patch->Text(patch.str("text"))}
                engine.extensions("$collection.detail.actions","detail_action").forEach{ext->
                    Button(onClick={runCatching{engine.action(ext.str("owner"),ext.str("action"),record)}}){Text(ext.str("title","Действие"))}
                }
            }else if(tab-1 in tabs.indices){
                val ext=tabs[tab-1]
                RelationPanel(engine,ext.str("collection"),revision,ext.str("relation_field","client_id"),id.toString())
            }
        }
    }
}

private fun display(engine:PluginEngine,field:JSONObject,value:String):String = when(field.str("type")) {
    "reference"->engine.nameOf(field.str("target"),value)
    "datetime"->value.toLongOrNull()?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}?:value
    "date"->runCatching{LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}.getOrDefault(value)
    "weekday"->listOf("Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье").getOrNull((value.toIntOrNull()?:0)-1)?:value
    "monthday"->"$value-е число"
    "file"->"Прикреплён документ"
    else->value
}

@Composable private fun DetailDialog(engine:PluginEngine,collection:String,id:Long,revision:Int,onClose:()->Unit,onEdit:()->Unit) {
    val record=remember(id,revision){engine.data.read(collection,id)}
    var tab by remember(id){mutableStateOf(0)}
    val tabs=engine.extensions("$collection.detail.tabs","detail_tab")
    AlertDialog(onDismissRequest=onClose,title={Text(record?.let{engine.nameOf(collection,it.id.toString())}?:"Карточка")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            if(tabs.isNotEmpty()) ScrollableTabRow(selectedTabIndex=tab){
                Tab(selected=tab==0,onClick={tab=0},text={Text("Общее")})
                tabs.forEachIndexed{i,e->Tab(selected=tab==i+1,onClick={tab=i+1},text={Text(e.str("title"))})}
            }
            if(tab==0){
                record?.let{r->engine.fields(collection).filterNot{it.optBoolean("hidden")}.forEach{f->val v=r.data.str(f.str("id"));if(v.isNotBlank())Text("${f.str("label",f.str("id"))}: ${display(engine,f,v)}")}}
                engine.extensions("$collection.labels","label_patch").forEach {patch->Text(patch.str("text"),style=MaterialTheme.typography.bodySmall)}
                engine.extensions("$collection.detail.actions","detail_action").forEach{ext->
                    Button(onClick={runCatching{engine.action(ext.str("owner"),ext.str("action"),record)}}){Text(ext.str("title","Действие"))}
                }
            }else {
                val ext=tabs[tab-1]
                val relation=ext.str("relation_field","client_id")
                RelationPanel(engine,ext.getString("collection"),revision,relation,id.toString())
            }
        }
    },confirmButton={TextButton(onClick=onEdit){Text("Изменить")}},dismissButton={TextButton(onClick=onClose){Text("Закрыть")}})
}

@Composable private fun RelationPanel(engine:PluginEngine,collection:String,revision:Int,relationField:String,relationId:String) {
    var add by remember(collection,relationId){mutableStateOf(false)}
    var view by remember(collection,relationId){mutableStateOf<Long?>(null)}
    var editId by remember(collection,relationId){mutableStateOf<Long?>(null)}
    val rows=remember(collection,relationId,revision){engine.data.list(collection,relationField,relationId)}
    Button(onClick={add=true}){Text("+ Добавить")}
    if(rows.isEmpty())Text("Пока нет записей")
    rows.take(40).forEach {r->
        OutlinedCard(Modifier.fillMaxWidth().clickable{view=r.id}) {
            Column(Modifier.padding(10.dp)) {
                Text(engine.nameOf(collection,r.id.toString()),style=MaterialTheme.typography.titleSmall)
                engine.fields(collection).filter{it.str("id")!=relationField}.take(2).forEach{f->
                    val value=r.data.str(f.str("id"))
                    if(value.isNotBlank())Text("${f.str("label")}: ${display(engine,f,value)}",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(add) RecordForm(engine,collection,null,relationField,relationId,onClose={add=false})
    if(view!=null) {
        val item=engine.data.read(collection,view!!)
        AlertDialog(onDismissRequest={view=null},title={Text(item?.let{engine.nameOf(collection,it.id.toString())}?:"Запись")},
            text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
                item?.let {r->engine.fields(collection).forEach{f->
                    val value=r.data.str(f.str("id"))
                    if(value.isNotBlank()){
                        Text(f.str("label"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(f.str("type")=="file"){
                            val context=LocalContext.current
                            TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(value)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}}){Text("Открыть документ")}
                        }else Text(display(engine,f,value))
                    }
                }}
            }},
            confirmButton={TextButton(onClick={editId=view;view=null}){Text("Изменить")}},
            dismissButton={TextButton(onClick={view=null}){Text("Закрыть")}})
    }
    if(editId!=null) RecordForm(engine,collection,editId,relationField,relationId,onClose={editId=null})
}

@Composable private fun RecordForm(engine:PluginEngine,collection:String,id:Long?,relationField:String?,relationId:String?,onClose:()->Unit) {
    val source=remember(collection,id){id?.let{engine.data.read(collection,it)?.data}}
    val fields=remember(collection){engine.fields(collection)}
    val values=remember(collection,id){mutableStateMapOf<String,String>().also{map->fields.forEach{map[it.str("id")]=source?.str(it.str("id")).orEmpty()};if(relationField!=null&&relationId!=null)map[relationField]=relationId; if(collection=="crm.tasks" && id==null){map["repeat"]="Одноразово";map["reminder_time"]="09:00"}}}
    var error by remember{mutableStateOf("")}
    val context=LocalContext.current
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
    var pickingField by remember { mutableStateOf<String?>(null) }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && pickingField!=null){
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            values[pickingField!!]=uri.toString()
        }
        pickingField=null
    }
    AlertDialog(onDismissRequest=onClose,title={Text(if(id==null)"Новая запись" else "Редактирование")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            var currentGroup=""
            fields.forEach{f->
                val key=f.str("id");val type=f.str("type")
                val visible=f.optJSONObject("visible_when")?.let { condition ->
                    values[condition.str("field")]==condition.str("equals")
                }?:true
                if(!visible || f.optBoolean("hidden")) return@forEach
                val group=f.str("group")
                if(group.isNotBlank() && group!=currentGroup) {
                    if(currentGroup.isNotEmpty()) HorizontalDivider(Modifier.padding(top=8.dp,bottom=3.dp))
                    Text(group,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
                    currentGroup=group
                }
                if(key==relationField && relationId!=null) {
                    Text("${f.str("label")}: ${engine.nameOf(f.str("target"),relationId)}")
                } else when(type) {
                    "reference" -> {
                        val choices=engine.data.list(f.str("target"))
                        var expanded by remember(key){mutableStateOf(false)}
                        Text(f.str("label"),style=MaterialTheme.typography.labelMedium)
                        Box { OutlinedButton(onClick={expanded=true}){Text(values[key]?.takeIf{it.isNotEmpty()}?.let{engine.nameOf(f.str("target"),it)}?:"Выбрать клиента / запись")}
                            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                                choices.forEach{r->DropdownMenuItem(text={Text(engine.nameOf(f.str("target"),r.id.toString()))},onClick={values[key]=r.id.toString();expanded=false})}
                            }
                        }
                    }
                    "select" -> {
                        var expanded by remember(key){mutableStateOf(false)}
                        Text(f.str("label"))
                        Box{OutlinedButton(onClick={expanded=true}){Text(values[key]?.ifBlank{"Выбрать"}?:"Выбрать")}
                            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                                f.optJSONArray("options")?.let{options->repeat(options.length()){n->val choice=options.getString(n);DropdownMenuItem(text={Text(choice)},onClick={values[key]=choice;expanded=false})}}
                            }
                        }
                    }
                    "boolean" -> Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(values[key]=="true",onCheckedChange={values[key]=it.toString()});Text(f.str("label"))}
                    "file" -> {
                        Text(f.str("label"),style=MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick={pickingField=key;filePicker.launch(arrayOf("application/pdf","image/*","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","*/*"))}) {
                            Text(if(values[key].isNullOrEmpty()) "Прикрепить файл" else "Заменить файл")
                        }
                        if(!values[key].isNullOrBlank()) Text("Документ прикреплён",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    }
                    "date","datetime","time" -> HumanDateTimeField(f.str("label"),type,values[key].orEmpty(),{values[key]=it})
                    "weekday" -> {
                        val weekdays=listOf("Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье")
                        var expanded by remember(key){mutableStateOf(false)}
                        Text(f.str("label"))
                        Box {OutlinedButton(onClick={expanded=true}) {Text(values[key]?.toIntOrNull()?.let{weekdays.getOrNull(it-1)}?:"Выбрать день недели")}
                            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {weekdays.forEachIndexed{i,day->DropdownMenuItem(text={Text(day)},onClick={values[key]=(i+1).toString();expanded=false})}}
                        }
                    }
                    "monthday" -> {
                        var expanded by remember(key){mutableStateOf(false)}
                        Text(f.str("label"))
                        Box{OutlinedButton(onClick={expanded=true}){Text(values[key]?.takeIf{it.isNotBlank()}?.let{"$it-е число"}?:"Выбрать число месяца")}
                            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=300.dp)){(1..31).forEach{day->DropdownMenuItem(text={Text("$day")},onClick={values[key]=day.toString();expanded=false})}}
                        }
                    }
                    else -> OutlinedTextField(value=values[key].orEmpty(),onValueChange={values[key]=it},label={Text(f.str("label",key)+(if(f.optBoolean("required"))" *" else ""))},singleLine=type!="multiline",modifier=Modifier.fillMaxWidth())
                }
            }
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        val required=fields.firstOrNull{f->
            val condition=f.optJSONObject("visible_when")
            val visible=condition==null || values[condition.str("field")]==condition.str("equals")
            visible && f.optBoolean("required") && values[f.str("id")].isNullOrBlank()
        }
        if(required!=null){error="Заполните ${required.str("label")}"}
        else runCatching{
            if(collection=="crm.tasks") {
                val repeat=values["repeat"].orEmpty().ifBlank{"Одноразово"}
                val now=ZonedDateTime.now()
                val time=runCatching{LocalTime.parse(values["reminder_time"].orEmpty().ifBlank{"09:00"})}.getOrElse{throw IllegalArgumentException("Неверное время")}
                val next=when(repeat){
                    "Одноразово","Нет" -> {
                        val day=values["once_date"]?.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it)}
                            ?:values["due_at"]?.toLongOrNull()?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}
                            ?:throw IllegalArgumentException("Выбери дату задачи")
                        day.atTime(time).atZone(ZoneId.systemDefault())
                    }
                    "Еженедельно" -> {
                        val weekday=values["weekday"]?.toIntOrNull()?.takeIf{it in 1..7}?:throw IllegalArgumentException("Выбери день недели")
                        var date=now.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
                        var result=date.atTime(time).atZone(ZoneId.systemDefault())
                        if(!result.isAfter(now))result=result.plusWeeks(1)
                        result
                    }
                    "Ежемесячно" -> {
                        val day=values["monthday"]?.toIntOrNull()?.takeIf{it in 1..31}?:throw IllegalArgumentException("Выбери число месяца")
                        var month=YearMonth.from(now)
                        var result=month.atDay(day.coerceAtMost(month.lengthOfMonth())).atTime(time).atZone(ZoneId.systemDefault())
                        if(!result.isAfter(now)){month=month.plusMonths(1);result=month.atDay(day.coerceAtMost(month.lengthOfMonth())).atTime(time).atZone(ZoneId.systemDefault())}
                        result
                    }
                    else -> throw IllegalArgumentException("Неизвестная периодичность")
                }
                require(next.isAfter(now)){"Укажи будущую дату и время"}
                values["due_at"]=next.toInstant().toEpochMilli().toString()
            }
            val json=JSONObject();values.forEach{(k,v)->json.put(k,v)}
            if(id!=null&&source!=null){source.keys().forEach{key->if(!json.has(key))json.put(key,source.get(key))}}
            engine.save(collection,json,id)
            onClose()
        }.onFailure{error=it.message?:"Ошибка"}
    }){Text("Сохранить")}},dismissButton={TextButton(onClick=onClose){Text("Отмена")}})
}


/** Date/time editors expose human-readable values; timestamps remain internal storage only. */
@Composable private fun HumanDateTimeField(label:String,type:String,value:String,onValue:(String)->Unit) {
    val context=LocalContext.current
    val zone=ZoneId.systemDefault()
    val parsed=remember(value,type) {
        runCatching {
            when(type) {
                "time"->null
                "date" -> value.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).atStartOfDay(zone)}
                else -> value.toLongOrNull()?.let{Instant.ofEpochMilli(it).atZone(zone)}
            }
        }.getOrNull()
    }
    val display=when(type){
        "time"->value.ifBlank{"Не выбрано"}
        "date"->parsed?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))?:"Выбрать дату"
        else->parsed?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))?:"Выбрать дату и время"
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick={
            val initial=parsed?:ZonedDateTime.now()
            if(type=="time") {
                val old=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.of(9,0))
                TimePickerDialog(context,{_,hour,minute->onValue("%02d:%02d".format(hour,minute))},old.hour,old.minute,true).show()
            } else {
                DatePickerDialog(context,{_,year,month,day->
                    val date=LocalDate.of(year,month+1,day)
                    if(type=="date")onValue(date.toString())
                    else {
                        val clock=parsed?.toLocalTime()?:LocalTime.of(9,0)
                        TimePickerDialog(context,{_,hour,minute->
                            onValue(date.atTime(hour,minute).atZone(zone).toInstant().toEpochMilli().toString())
                        },clock.hour,clock.minute,true).show()
                    }
                },initial.year,initial.monthValue-1,initial.dayOfMonth).show()
            }
        },modifier=Modifier.fillMaxWidth()) {Text(display)}
    }
}
