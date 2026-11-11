package com.example.modularcrm

import androidx.compose.foundation.layout.*
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native host screen selected by the declarative sysadmin.mcrm page descriptor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsScreen(engine:PluginEngine) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var notificationAllowed by remember {mutableStateOf(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notificationAllowed=it}
    val networks=remember {runCatching{NetworkDiagnostics.subnets()}.getOrDefault(emptyList())}
    var subnet by remember {mutableStateOf(networks.firstOrNull())}
    var subnetOpen by remember {mutableStateOf(false)}
    var host by remember {mutableStateOf("")}
    var port by remember {mutableStateOf("443")}
    var portList by remember {mutableStateOf("22,80,443,445,3389,8080")}
    var mac by remember {mutableStateOf("")}
    var broadcast by remember {mutableStateOf("255.255.255.255")}
    var result by remember {mutableStateOf("Выбери действие")}
    var busy by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf("Ping")}
    var monitorClient by remember { mutableStateOf("") }
    var clientExpanded by remember { mutableStateOf(false) }
    var manualRevision by remember { mutableIntStateOf(0) }
    fun run(block:suspend ()->Unit) {
        if(busy)return
        scope.launch {
            busy=true
            try{block()}catch(e:Exception){result="Ошибка: ${e.message}"}
            finally{busy=false}
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
        Text("Сетевые инструменты",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        if(!notificationAllowed){OutlinedButton(onClick={notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("Разрешить уведомления о сбоях")}}
        Text("Для диагностики своих устройств и сетей. Поиск ограничен выбранной локальной подсетью.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Ping","Поиск","Порты","WOL").forEachIndexed {index,title ->
                SegmentedButton(selected=selected==title,onClick={selected=title},shape=SegmentedButtonDefaults.itemShape(index,4),label={Text(title)})
            }
        }
        when(selected) {
            "Поиск" -> {
                Text("Подсеть (из обнаруженных на устройстве)",style=MaterialTheme.typography.labelLarge)
                Box { OutlinedButton(onClick={subnetOpen=true}) {Text(subnet?.title?:"Нет доступных локальных интерфейсов")}
                    DropdownMenu(expanded=subnetOpen,onDismissRequest={subnetOpen=false}){
                        networks.forEach {n->DropdownMenuItem(text={Text("${n.title} · ${n.cidr}")},onClick={subnet=n;subnetOpen=false})}
                    }
                }
                Text("Проверяются только адреса локального /24 или более узкой сети; TCP 80 и 443. Устройства без этих портов могут быть не обнаружены.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=!busy&&subnet!=null,onClick={run {
                    result="Сканирование ${subnet!!.cidr}…"
                    var count=0
                    NetworkDiagnostics.discover(subnet!!){address,r->count++;result+="\n$address: ${r.detail}"}
                    result+="\nЗавершено. Найдено: $count"
                }}) {Text("Найти устройства")}
            }
            "Ping","Порты" -> {
                OutlinedTextField(value=host,onValueChange={host=it},label={Text("IP-адрес или имя хоста")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(selected=="Ping") {
                    Button(enabled=!busy&&host.isNotBlank(),onClick={run {val r=withContext(Dispatchers.IO){NetworkDiagnostics.icmp(host)};result="${r.status}: ${r.detail} (${r.durationMs} мс)"}}){Text("Проверить доступность")}
                } else {
                    OutlinedTextField(value=portList,onValueChange={portList=it},label={Text("TCP-порты (через запятую, максимум 64)")},modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy&&host.isNotBlank(),onClick={run {
                        val ports=portList.split(',').map{it.trim().toIntOrNull()?:error("Неверный порт")}
                        require(ports.size in 1..64&&ports.all{it in 1..65535}){"Укажи от 1 до 64 портов"}
                        result="Проверка TCP-портов…"
                        var found=0
                        NetworkDiagnostics.scanPorts(host,ports){p,r->found++;result+="\n$p: ${r.detail}"}
                        result+="\nГотово, открыто: $found"
                    }}){Text("Сканировать TCP-порты")}
                }
            }
            "WOL" -> {
                OutlinedTextField(mac,{mac=it},label={Text("MAC-адрес · AA:BB:CC:DD:EE:FF")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(broadcast,{broadcast=it},label={Text("Broadcast-адрес")},modifier=Modifier.fillMaxWidth())
                Text("Wake-on-LAN отправляет magic packet UDP/9. Устройство должно поддерживать WOL и быть доступным в широковещательном домене.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=!busy&&mac.isNotBlank(),onClick={run {withContext(Dispatchers.IO){NetworkDiagnostics.wol(mac,broadcast)};result="Magic packet отправлен. Подтверждение включения не предусмотрено."}}){Text("Разбудить устройство")}
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Результат",style=MaterialTheme.typography.titleMedium)
                Text(result,style=MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Проверки клиентских серверов",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        val clients=remember(manualRevision){engine.data.list("crm.clients")}
        val devices=remember(manualRevision){engine.data.list("hardware.devices")}
        val monitors=remember(manualRevision){engine.data.list("sysadmin.monitors")}
        Box {
            OutlinedButton(onClick={clientExpanded=true}) {
                Text(if(monitorClient.isBlank())"Все клиенты" else engine.nameOf("crm.clients",monitorClient))
            }
            DropdownMenu(expanded=clientExpanded,onDismissRequest={clientExpanded=false}) {
                DropdownMenuItem(text={Text("Все клиенты")},onClick={monitorClient="";clientExpanded=false})
                clients.forEach{client->DropdownMenuItem(text={Text(engine.nameOf("crm.clients",client.id.toString()))},onClick={monitorClient=client.id.toString();clientExpanded=false})}
            }
        }
        val filtered=monitors.filter{m->
            val d=devices.firstOrNull{it.id.toString()==m.data.optString("device_id")}
            d!=null && (monitorClient.isBlank() || d.data.optString("client_id")==monitorClient)
        }
        if(filtered.isEmpty()) Text("Нет настроенных проверок. Создай проверку в разделе «Мониторинг» и выбери оборудование клиента.",style=MaterialTheme.typography.bodySmall)
        filtered.take(30).forEach{monitor->
            val device=devices.firstOrNull{it.id.toString()==monitor.data.optString("device_id")} ?: return@forEach
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(device.data.optString("name","Сервер"),style=MaterialTheme.typography.titleSmall)
                    Text("${monitor.data.optString("method")} · ${device.data.optString("public_ip").ifBlank{device.data.optString("ip")}}",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled=!busy,onClick={run {
                        val definition=monitor.data
                        val method=definition.optString("method","TCP")
                        val address=definition.optString("host_override").ifBlank{device.data.optString("ip").ifBlank{device.data.optString("public_ip")}}
                        val port=definition.optString("port").toIntOrNull()?:if(method=="HTTPS")443 else if(method=="HTTP")80 else 443
                        val expected=definition.optString("expected_code").toIntOrNull()?:200
                        val r=withContext(Dispatchers.IO){NetworkDiagnostics.check(method,address,port,definition.optString("path","/"),expected)}
                        engine.data.put("sysadmin.checks",org.json.JSONObject().put("monitor_id",monitor.id.toString()).put("checked_at",System.currentTimeMillis().toString()).put("status",r.status).put("details",r.detail))
                        result="${device.data.optString("name")}: ${r.status} — ${r.detail} (${r.durationMs} мс)"
                        manualRevision++
                    }}){Text("Проверить сейчас")}
                }
            }
        }
        Text("Автоматические проверки запускаются Android WorkManager примерно раз в 15 минут (возможны задержки при ограничениях батареи). UDP без ответа имеет неопределённый статус, ICMP на некоторых Android-устройствах недоступен.",style=MaterialTheme.typography.bodySmall)
    }
}
