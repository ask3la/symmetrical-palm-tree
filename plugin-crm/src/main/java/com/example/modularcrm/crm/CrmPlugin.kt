package com.example.modularcrm.crm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.modularcrm.core.AppPlugin
import com.example.modularcrm.core.PluginPage
import com.example.modularcrm.core.PluginRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrmPlugin : AppPlugin {
    override val id="crm"
    override val name="CRM"
    private lateinit var registry:PluginRegistry
    override fun onLoad(context:Context,registry:PluginRegistry) { this.registry=registry; TaskScheduler(context).rescheduleAll() }
    override fun pages() = listOf(
        PluginPage("crm.clients","Клиенты") { ClientsScreen(registry) },
        PluginPage("crm.tasks","Задачи") { TasksScreen() },
        PluginPage("crm.works","Работы") { AppointmentsScreen() },
        PluginPage("crm.stats","Статистика") { StatisticsScreen() },
        PluginPage("crm.stale","Давно без связи") { StaleScreen(registry) }
    )
    override fun settings()=listOf(PluginPage("crm.settings","Настройки CRM") { CrmSettingsScreen() })
}

private val dateFormat get()=SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).apply {isLenient=false}
private fun fmt(time:Long)=dateFormat.format(Date(time))
private fun parseDate(text:String):Long?=try {dateFormat.parse(text)?.time} catch (_:Exception){null}
private fun values(context:Context)=context.getSharedPreferences("crm_settings",Context.MODE_PRIVATE)

@Composable private fun TextFieldShort(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier.fillMaxWidth()) {
    OutlinedTextField(value,onChange,modifier=modifier,label={Text(label)},singleLine=true)
}

@Composable private fun ClientsScreen(registry:PluginRegistry) {
    val context=LocalContext.current
    val repo=remember {CrmRepository(context,registry)}
    var revision by remember {mutableIntStateOf(0)}
    var selected by remember {mutableStateOf<Long?>(null)}
    var showCreate by remember {mutableStateOf(false)}
    var categoryFilter by remember {mutableStateOf<Long?>(null)}
    val clients=remember(revision){repo.clients()}
    val categories=remember(revision){repo.categories()}
    if(selected!=null) {
        ClientDetail(repo,registry,selected!!,onBack={selected=null;revision++},onChanged={revision++})
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Клиенты",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={showCreate=true}){Text("+ Клиент")}
            OutlinedButton(onClick={categoryFilter=null}){Text("Все")}
        }
        if(categories.isNotEmpty()) {
            var expanded by remember {mutableStateOf(false)}
            Box { TextButton(onClick={expanded=true}) {Text(categories.firstOrNull{it.id==categoryFilter}?.name ?: "Категория: все ▾")}
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    DropdownMenuItem(text={Text("Все")},onClick={categoryFilter=null;expanded=false})
                    categories.forEach {cat->DropdownMenuItem(text={Text(cat.name)},onClick={categoryFilter=cat.id;expanded=false})}
                }
            }
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(clients.filter { categoryFilter==null || categoryFilter in repo.categoryIds(it.id) },key={it.id}) {client ->
                ElevatedCard(modifier=Modifier.fillMaxWidth().clickable {selected=client.id}) {
                    Column(Modifier.padding(16.dp)) {
                        Text(client.name,style=MaterialTheme.typography.titleMedium)
                        if(client.phone.isNotBlank()) Text(client.phone)
                        val labels=categories.filter {it.id in repo.categoryIds(client.id)}.joinToString {it.name}
                        if(labels.isNotBlank()) Text(labels,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if(showCreate) {
        var name by remember {mutableStateOf("")}; var phone by remember {mutableStateOf("")};var notes by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={showCreate=false},title={Text("Новый клиент")},text={Column {
            TextFieldShort(name,{name=it},"Название / ФИО")
            TextFieldShort(phone,{phone=it},"Телефон")
            TextFieldShort(notes,{notes=it},"Примечание")
        }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={repo.addClient(name.trim(),phone.trim(),notes.trim());revision++;showCreate=false}){Text("Добавить")}},dismissButton={TextButton(onClick={showCreate=false}){Text("Отмена")}})
    }
}

@Composable private fun ClientDetail(repo:CrmRepository,registry:PluginRegistry,clientId:Long,onBack:()->Unit,onChanged:()->Unit) {
    val client=repo.client(clientId) ?: return
    var refresh by remember {mutableIntStateOf(0)}
    var dialog by remember {mutableStateOf("")}
    val categories=remember(refresh){repo.categories()}
    val assigned=remember(refresh){repo.categoryIds(clientId)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick=onBack){Text("← Все клиенты")}
        Text(client.name,style=MaterialTheme.typography.headlineMedium)
        if(client.phone.isNotBlank()) Text("Телефон: ${client.phone}")
        if(client.notes.isNotBlank()) Text(client.notes)
        HorizontalDivider()
        Text("Категории",style=MaterialTheme.typography.titleLarge)
        categories.forEach {cat-> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked=cat.id in assigned,onCheckedChange={repo.setCategory(clientId,cat.id,it);refresh++;onChanged()})
            Text(cat.name)
        }}
        TextButton(onClick={dialog="category"}) {Text("+ Создать категорию")}
        HorizontalDivider()
        Text("Контактные лица",style=MaterialTheme.typography.titleLarge)
        remember(refresh){repo.contacts(clientId)}.forEach {contact->
            Text("${contact.name} · ${contact.role}\n${contact.phone}")
        }
        TextButton(onClick={dialog="contact"}){Text("+ Добавить контакт")}
        HorizontalDivider()
        Text("История событий",style=MaterialTheme.typography.titleLarge)
        remember(refresh){repo.interactions(clientId)}.forEach {e->
            ElevatedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                Text("${e.type} · ${fmt(e.occurredAt)}",style=MaterialTheme.typography.labelMedium)
                Text(e.description)
            }}
        }
        Button(onClick={dialog="interaction"}){Text("+ Событие / контакт / работа")}
        HorizontalDivider()
        registry.Render("crm.client.detail",clientId)
    }
    when(dialog) {
        "category" -> {
            var name by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={dialog=""},title={Text("Новая категория")},text={TextFieldShort(name,{name=it},"Название")},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={runCatching{repo.addCategory(name.trim())};refresh++;onChanged();dialog=""}){Text("Сохранить")}},dismissButton={TextButton(onClick={dialog=""}){Text("Отмена")}})
        }
        "contact" -> {
            var name by remember {mutableStateOf("")};var role by remember {mutableStateOf("")};var phone by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={dialog=""},title={Text("Контактное лицо")},text={Column {
                TextFieldShort(name,{name=it},"ФИО");TextFieldShort(role,{role=it},"Должность");TextFieldShort(phone,{phone=it},"Телефон")
            }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={repo.addContact(clientId,name,role,phone);refresh++;dialog=""}){Text("Добавить")}},dismissButton={TextButton(onClick={dialog=""}){Text("Отмена")}})
        }
        "interaction" -> {
            var type by remember {mutableStateOf("Контакт")};var description by remember {mutableStateOf("")};var expanded by remember {mutableStateOf(false)}
            AlertDialog(onDismissRequest={dialog=""},title={Text("Событие клиента")},text={Column {
                Box {TextButton(onClick={expanded=true}){Text("Тип: $type ▾")}
                    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {listOf("Контакт","Работа","Заметка").forEach {t->DropdownMenuItem(text={Text(t)},onClick={type=t;expanded=false})}}
                }
                OutlinedTextField(description,{description=it},label={Text("Описание")},minLines=3)
            }},confirmButton={TextButton(enabled=description.isNotBlank(),onClick={repo.addInteraction(clientId,type,description);refresh++;dialog=""}){Text("Сохранить")}},dismissButton={TextButton(onClick={dialog=""}){Text("Отмена")}})
        }
    }
}

@Composable private fun TasksScreen() {
    val context=LocalContext.current;val repo=remember{CrmRepository(context)};val scheduler=remember{TaskScheduler(context)}
    var revision by remember {mutableIntStateOf(0)};var creating by remember {mutableStateOf(false)}
    val tasks=remember(revision){repo.tasks()};val clients=remember(revision){repo.clients()}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Задачи и напоминания",style=MaterialTheme.typography.headlineMedium)
        Button(onClick={creating=true}){Text("+ Новая задача")}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(tasks,key={it.id}) {task-> ElevatedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {
                Text(task.title,style=MaterialTheme.typography.titleMedium)
                if(task.description.isNotBlank()) Text(task.description)
                Text("${fmt(task.dueAt)} · ${repeatText(task.repeatRule)}",style=MaterialTheme.typography.bodySmall)
                if(task.clientId!=null) Text(clients.find {it.id==task.clientId}?.name ?: "Клиент удалён")
                Row {if(!task.done) TextButton(onClick={repo.setTaskDone(task);scheduler.cancel(task.id);repo.tasks().firstOrNull{it.id==task.id && !it.done}?.let(scheduler::schedule);revision++}){Text("Выполнено")}
                    TextButton(onClick={scheduler.cancel(task.id);repo.deleteTask(task.id);revision++}){Text("Удалить")}
                }
            }}}
        }
    }
    if(creating) {
        var title by remember {mutableStateOf("")};var description by remember {mutableStateOf("")}
        var date by remember {mutableStateOf(fmt(System.currentTimeMillis()+3600000))}
        var rule by remember {mutableStateOf("NONE")};var selectedClient by remember {mutableStateOf<Long?>(null)}
        val due=parseDate(date)
        AlertDialog(onDismissRequest={creating=false},title={Text("Запланировать действие")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            TextFieldShort(title,{title=it},"Название")
            OutlinedTextField(description,{description=it},label={Text("Что нужно сделать")},minLines=2)
            TextFieldShort(date,{date=it},"Дата: дд.мм.гггг чч:мм")
            Choice("Повторение",listOf("NONE","DAILY","WEEKLY","MONTHLY"),rule,{rule=it},::repeatText)
            Choice("Клиент",listOf<Long?>(null)+clients.map{it.id},selectedClient,{selectedClient=it}){id->clients.find{it.id==id}?.name ?: "Без клиента"}
            if(due==null) Text("Некорректная дата",color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(enabled=title.isNotBlank() && due!=null,onClick={
            repo.addTask(selectedClient,title,description,due!!,rule);repo.tasks().lastOrNull{it.title==title && it.dueAt==due}?.let(scheduler::schedule)
            revision++;creating=false
        }){Text("Создать")}},dismissButton={TextButton(onClick={creating=false}){Text("Отмена")}})
    }
}
private fun repeatText(rule:String)=when(rule){"DAILY"->"Ежедневно";"WEEKLY"->"Еженедельно";"MONTHLY"->"Ежемесячно";else->"Без повтора"}

@Composable private fun <T> Choice(label:String,items:List<T>,selected:T,onSelected:(T)->Unit,display:(T)->String) {
    var expanded by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={expanded=true}){Text("$label: ${display(selected)} ▾")}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {items.forEach {v->DropdownMenuItem(text={Text(display(v))},onClick={onSelected(v);expanded=false})}}
    }
}

@Composable private fun AppointmentsScreen() {
    val context=LocalContext.current;val repo=remember{CrmRepository(context)};val sync=remember {CalendarSync(context,repo)}
    var revision by remember {mutableIntStateOf(0)};var creating by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Appointment?>(null)}
    val clients=remember(revision){repo.clients()};val appointments=remember(revision){repo.appointments()}
    val prefs=values(context)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    val calendars=remember(revision){sync.writableCalendars()}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Запланированные работы",style=MaterialTheme.typography.headlineMedium)
        Button(onClick={creating=true}){Text("+ Запланировать")}
        if(!sync.permitted()) TextButton(onClick={permission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))}){Text("Разрешить доступ к календарю")}
        if(sync.permitted() && calendars.isEmpty()) Text("Нет доступного календаря с правами записи")
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(appointments,key={it.id}) {a->ElevatedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {
                Text(a.title,style=MaterialTheme.typography.titleMedium)
                Text("${fmt(a.startsAt)} — ${fmt(a.endsAt)}")
                Text(clients.find{it.id==a.clientId}?.name ?: "Клиент")
                if(a.description.isNotBlank()) Text(a.description)
                Text(if(a.calendarEventId==null) "Не синхронизировано" else "В системном календаре ✓")
                Row {TextButton(onClick={editing=a}){Text("Изменить")}
                    TextButton(onClick={sync.delete(a);revision++}){Text("Удалить")}
                    if(a.calendarEventId==null && sync.permitted()) TextButton(onClick={val cal=prefs.getLong("calendar_id",-1);if(cal!=-1L)sync.sync(a,cal);revision++}){Text("Синхронизировать")}
                }
            }}}
        }
    }
    if(creating || editing!=null) {
        val existing=editing
        var title by remember(existing) {mutableStateOf(existing?.title ?: "")}
        var description by remember(existing) {mutableStateOf(existing?.description ?: "")}
        var start by remember(existing){mutableStateOf(fmt(existing?.startsAt ?: System.currentTimeMillis()+3600000))}
        var end by remember(existing){mutableStateOf(fmt(existing?.endsAt ?: System.currentTimeMillis()+7200000))}
        var selectedClient by remember(existing){mutableStateOf<Long?>(existing?.clientId ?: clients.firstOrNull()?.id)}
        val startMs=parseDate(start);val endMs=parseDate(end)
        AlertDialog(onDismissRequest={creating=false;editing=null},title={Text(if(existing==null)"Новая работа" else "Изменить работу")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            TextFieldShort(title,{title=it},"Название работы")
            OutlinedTextField(description,{description=it},label={Text("Описание")})
            TextFieldShort(start,{start=it},"Начало: дд.мм.гггг чч:мм")
            TextFieldShort(end,{end=it},"Окончание: дд.мм.гггг чч:мм")
            if(existing==null && clients.isNotEmpty()) Choice("Клиент",clients.map{it.id},selectedClient ?: clients[0].id,{selectedClient=it}) { id->clients.firstOrNull{it.id==id}?.name ?: "" }
            if(startMs==null || endMs==null || endMs<=startMs) Text("Укажите корректный интервал",color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(enabled=title.isNotBlank() && selectedClient!=null && startMs!=null && endMs!=null && endMs>startMs,onClick={
            val id=if(existing==null) repo.addAppointment(selectedClient!!,title,description,startMs!!,endMs!!) else {repo.editAppointment(existing.id,title,description,startMs!!,endMs!!);existing.id}
            val appointment=repo.appointments().firstOrNull{it.id==id}
            val cal=prefs.getLong("calendar_id",-1L)
            if(appointment!=null && cal!=-1L && prefs.getBoolean("calendar_sync",false)) sync.sync(appointment,cal)
            creating=false;editing=null;revision++
        }){Text("Сохранить")}},dismissButton={TextButton(onClick={creating=false;editing=null}){Text("Отмена")}})
    }
}

@Composable private fun StatisticsScreen() {
    val ctx=LocalContext.current;val repo=remember{CrmRepository(ctx)}
    var rev by remember{mutableIntStateOf(0)}
    val clients=remember(rev){repo.clients()};val tasks=remember(rev){repo.tasks()};val interactions=remember(rev){repo.allInteractions()}
    val stale=remember(rev){repo.staleClients(values(ctx).getInt("stale_days",30))}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Статистика CRM",style=MaterialTheme.typography.headlineMedium)
        listOf("Всего клиентов" to clients.size,"Событий и контактов" to interactions.size,
            "Запланированных работ" to repo.appointments().size,"Активных задач" to tasks.count{!it.done},
            "Просроченных задач" to tasks.count{!it.done && it.dueAt<System.currentTimeMillis()},
            "Давно без контакта" to stale.size).forEach {(label,count)->
            ElevatedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(label);Text("$count",style=MaterialTheme.typography.titleLarge)
            }}
        }
        TextButton(onClick={rev++}){Text("Обновить")}
    }
}

@Composable private fun StaleScreen(registry:PluginRegistry) {
    val ctx=LocalContext.current;val repo=remember{CrmRepository(ctx,registry)}
    val days=values(ctx).getInt("stale_days",30)
    var rev by remember{mutableIntStateOf(0)}
    val clients=remember(rev,days){repo.staleClients(days)}
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Давно без связи ($days дн.)",style=MaterialTheme.typography.headlineMedium)
        TextButton(onClick={rev++}){Text("Обновить")}
        LazyColumn {items(clients,key={it.id}) {client->ElevatedCard(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(client.name,style=MaterialTheme.typography.titleMedium);Text(client.phone)
                TextButton(onClick={repo.addInteraction(client.id,"Контакт","Связались с клиентом");rev++}) {Text("Отметить контакт")}
            }
        }}}
    }
}

@Composable private fun CrmSettingsScreen() {
    val ctx=LocalContext.current;val prefs=values(ctx);val repo=remember{CrmRepository(ctx)};val sync=remember{CalendarSync(ctx,repo)}
    var days by remember {mutableStateOf(prefs.getInt("stale_days",30).toString())}
    var enabled by remember {mutableStateOf(prefs.getBoolean("calendar_sync",false))}
    var revision by remember {mutableIntStateOf(0)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {revision++}
    val calendars=remember(revision){sync.writableCalendars()}
    var calendarId by remember(revision){mutableLongStateOf(prefs.getLong("calendar_id",-1))}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Настройки CRM",style=MaterialTheme.typography.headlineMedium)
        OutlinedTextField(days,{days=it.filter(Char::isDigit)},label={Text("Дней без контакта")})
        Button(enabled=days.toIntOrNull() in 1..3650,onClick={prefs.edit().putInt("stale_days",days.toInt()).apply()}) {Text("Сохранить период")}
        HorizontalDivider()
        Text("Интеграция с календарём",style=MaterialTheme.typography.titleLarge)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Switch(checked=enabled,onCheckedChange={enabled=it;prefs.edit().putBoolean("calendar_sync",it).apply()});Spacer(Modifier.width(8.dp));Text("Создавать события автоматически")}
        if(!sync.permitted()) Button(onClick={permission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))}){Text("Разрешить календарь")}
        if(sync.permitted()) {
            Text("Календарь для новых событий")
            Choice("Календарь",listOf(-1L)+calendars.map{it.first},calendarId,{id->calendarId=id;prefs.edit().putLong("calendar_id",id).apply()}) {id->calendars.find{it.first==id}?.second ?: "Не выбран"}
        }
        Text("События, созданные приложением, обновляются и удаляются при изменении работ. Изменения в системном календаре обратно в CRM не импортируются.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Уведомления",style=MaterialTheme.typography.titleLarge)
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        Button(onClick={if(android.os.Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Разрешить уведомления")}
        Text("Напоминания используют системные будильники без разрешения на точное время: фактическое время показа может немного сдвигаться.",style=MaterialTheme.typography.bodySmall)
    }
}
