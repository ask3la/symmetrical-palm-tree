package com.example.modularcrm

import android.Manifest
import android.os.Build
import android.os.Bundle
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
        setContent {
            MaterialTheme(colorScheme=lightColorScheme()) {
                var unlocked by remember { mutableStateOf(false) }
                if(!unlocked) PinScreen(security){unlocked=true}
                else Workspace(engine){unlocked=false}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Workspace(engine:PluginEngine,onLock:()->Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(engine) {engine.changed={refresh++};onDispose{engine.changed=null}}
    val plugins=refresh.let {engine.plugins.toList()}
    var pluginId by remember { mutableStateOf("crm") }
    var pageId by remember { mutableStateOf("clients") }
    var mode by remember { mutableStateOf("plugin") }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val active=plugins.firstOrNull{it.id==pluginId} ?: plugins.firstOrNull()
    val pages=active?.pages.orEmpty()
    ModalNavigationDrawer(drawerState=drawer, drawerContent={
        ModalDrawerSheet {
            Text("Modular Workspace",Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            Text("Плагины",Modifier.padding(16.dp),style=MaterialTheme.typography.labelLarge)
            plugins.forEach {p -> NavigationDrawerItem(label={Text(p.name)}, selected=mode=="plugin" && pluginId==p.id,
                onClick={pluginId=p.id;pageId=p.pages.firstOrNull()?.str("id").orEmpty();mode="plugin";scope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=12.dp)) }
            HorizontalDivider()
            NavigationDrawerItem(label={Text("Управление плагинами")},icon={Icon(Icons.Default.Extension,null)},selected=mode=="manager",onClick={mode="manager";scope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=12.dp))
            NavigationDrawerItem(label={Text("Настройки")},icon={Icon(Icons.Default.Settings,null)},selected=mode=="settings",onClick={mode="settings";scope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=12.dp))
            NavigationDrawerItem(label={Text("Блокировка")},icon={Icon(Icons.Default.Lock,null)},selected=false,onClick={onLock();scope.launch{drawer.close()}},modifier=Modifier.padding(horizontal=12.dp))
        }
    }) {
        Scaffold(topBar={TopAppBar(title={Text(if(mode=="plugin")active?.name?:"Modular Workspace" else if(mode=="manager")"Плагины" else "Настройки")},navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Default.Menu,"Меню")}})}) {padding->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when(mode) {
                    "manager" -> ManagerScreen(engine)
                    "settings" -> SettingsScreen(engine,refresh)
                    else -> {
                        if(pages.isNotEmpty()) {
                            val selected=pages.indexOfFirst{it.str("id")==pageId}.coerceAtLeast(0)
                            ScrollableTabRow(selectedTabIndex=selected,edgePadding=8.dp) {
                                pages.forEach{p->Tab(selected=p.str("id")==pages[selected].str("id"),onClick={pageId=p.str("id")},text={Text(p.str("title"))})}
                            }
                            val page=pages[selected]
                            when(page.str("kind","collection")) {
                                "stale" -> StaleScreen(engine,page,refresh)
                                "metrics" -> MetricsScreen(engine,page,refresh)
                                else -> CollectionScreen(engine,"${active!!.id}.${page.str("collection")}",refresh)
                            }
                        } else Text("У плагина пока нет разделов",Modifier.padding(20.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun ManagerScreen(engine:PluginEngine) {
    var result by remember{mutableStateOf("")}
    var pending by remember{mutableStateOf<android.net.Uri?>(null)}
    var manifest by remember{mutableStateOf<JSONObject?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) runCatching {engine.inspect(uri)}.onSuccess {meta ->
            val requested=meta.optJSONObject("permissions")?.optJSONArray("write")
            if(requested==null || requested.length()==0) {
                runCatching{engine.install(uri)}.onSuccess{result="Установлен ${it.name}"}.onFailure{result="Ошибка: ${it.message}"}
            } else {pending=uri;manifest=meta}
        }.onFailure {result="Ошибка проверки: ${it.message}"}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Плагины .mcrm",style=MaterialTheme.typography.headlineSmall)
        Text("Файлы ZIP с manifest.json. Приложение интерпретирует их интерфейс, события и действия. Дополнительные APK не нужны.")
        Button(onClick={picker.launch(arrayOf("application/zip","application/octet-stream","*/*"))}){Text("+ Установить .mcrm")}
        if(result.isNotEmpty())Text(result)
        engine.plugins.forEach{p->ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
            Text(p.name,style=MaterialTheme.typography.titleMedium)
            Text("${p.id} · API ${p.manifest.optInt("api")}")
            Text("Разделы: ${p.pages.size}; расширения: ${p.extensions.size}; обработчики: ${p.hooks.size}")
            if(p.id!="crm") TextButton(onClick={result=runCatching{engine.remove(p.id);"Удалён ${p.name}"}.getOrElse{it.message?:"Ошибка"}}){Text("Удалить")}
        }}}
    }
    if(manifest!=null && pending!=null) {
        val meta=manifest!!
        val array=meta.optJSONObject("permissions")?.optJSONArray("write")
        val requested=(0 until (array?.length()?:0)).map{array!!.getString(it)}.toSet()
        AlertDialog(onDismissRequest={manifest=null;pending=null},title={Text("Разрешения плагина ${meta.str("name")}")},
            text={Text("Плагин запрашивает изменение данных других плагинов: ${requested.joinToString()}. Это позволит ему добавлять и менять связанные записи. Разрешить только доверенному файлу.")},
            confirmButton={TextButton(onClick={
                val uri=pending!!
                runCatching{engine.install(uri,requested)}.onSuccess{result="Установлен ${it.name}"}.onFailure{result="Ошибка: ${it.message}"}
                manifest=null;pending=null
            }){Text("Разрешить и установить")}},
            dismissButton={TextButton(onClick={manifest=null;pending=null}){Text("Отмена")}})
    }
}

@Composable private fun SettingsScreen(engine:PluginEngine,revision:Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
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
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            Button(onClick={editingId=null;editing=true}){Text("+ Добавить")}
        }
        OutlinedTextField(search,{search=it},label={Text("Поиск")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(rows.filter {r->r.data.toString().contains(search,true)},key={it.id}){r->
                ElevatedCard(Modifier.fillMaxWidth().clickable{selected=r.id}){
                    Column(Modifier.padding(12.dp)){
                        Text(engine.nameOf(collection,r.id.toString()),style=MaterialTheme.typography.titleMedium)
                        engine.fields(collection).filter{it.str("type")!="multiline"}.take(3).forEach{f->val v=r.data.str(f.str("id"));if(v.isNotBlank())Text("${f.str("label",f.str("id"))}: ${display(engine,f,v)}",style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
        }
    }
    if(selected!=null) DetailDialog(engine,collection,selected!!,revision,onClose={selected=null},onEdit={editingId=selected;editing=true})
    if(editing) RecordForm(engine,collection,editingId,relationField,relationId,onClose={editing=false})
}

private fun display(engine:PluginEngine,field:JSONObject,value:String):String = if(field.str("type")=="reference")engine.nameOf(field.str("target"),value) else value

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
                record?.let{r->engine.fields(collection).forEach{f->val v=r.data.str(f.str("id"));if(v.isNotBlank())Text("${f.str("label",f.str("id"))}: ${display(engine,f,v)}")}}
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
            text={Column{item?.let {r->engine.fields(collection).forEach{f->Text("${f.str("label")}: ${display(engine,f,r.data.str(f.str("id")))}")}}}},
            confirmButton={TextButton(onClick={view=null;add=true}){Text("Новая запись")}},
            dismissButton={TextButton(onClick={view=null}){Text("Закрыть")}})
    }
}

@Composable private fun RecordForm(engine:PluginEngine,collection:String,id:Long?,relationField:String?,relationId:String?,onClose:()->Unit) {
    val source=remember(collection,id){id?.let{engine.data.read(collection,it)?.data}}
    val fields=remember(collection){engine.fields(collection)}
    val values=remember(collection,id){mutableStateMapOf<String,String>().also{map->fields.forEach{map[it.str("id")]=source?.str(it.str("id")).orEmpty()};if(relationField!=null&&relationId!=null)map[relationField]=relationId}}
    var error by remember{mutableStateOf("")}
    val context=LocalContext.current
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
    AlertDialog(onDismissRequest=onClose,title={Text(if(id==null)"Новая запись" else "Редактирование")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            fields.forEach{f->
                val key=f.str("id");val type=f.str("type")
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
                    "boolean" -> Row {Checkbox(values[key]=="true",onCheckedChange={values[key]=it.toString()});Text(f.str("label"))}
                    else -> OutlinedTextField(value=values[key].orEmpty(),onValueChange={values[key]=it},label={Text(f.str("label",key)+(if(f.optBoolean("required"))" *" else ""))},singleLine=type!="multiline",modifier=Modifier.fillMaxWidth(),supportingText=if(type=="datetime"){{Text("Unix timestamp в миллисекундах")}}else null)
                }
            }
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        val required=fields.firstOrNull{it.optBoolean("required")&&values[it.str("id")].isNullOrBlank()}
        if(required!=null){error="Заполните ${required.str("label")}"}
        else runCatching{
            val json=JSONObject();values.forEach{(k,v)->json.put(k,v)}
            if(id!=null&&source!=null){source.keys().forEach{key->if(!json.has(key))json.put(key,source.get(key))}}
            engine.save(collection,json,id)
            onClose()
        }.onFailure{error=it.message?:"Ошибка"}
    }){Text("Сохранить")}},dismissButton={TextButton(onClick=onClose){Text("Отмена")}})
}
