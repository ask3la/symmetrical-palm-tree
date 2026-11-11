package com.example.modularcrm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Host-provided network operations. No shell commands or plugin-executed code. */
object NetworkDiagnostics {
    data class Subnet(val title:String,val interfaceName:String,val prefix:Int,val network:Long,val broadcast:Long) {
        val cidr:String get()="${format(network)}/$prefix"
    }
    data class Result(val status:String,val detail:String,val durationMs:Long)

    private fun numeric(ip:String):Long? {
        val parts=ip.trim().split('.')
        if(parts.size!=4) return null
        var out=0L
        for(part in parts) { if(part.isEmpty() || part.length>3 || part.any{!it.isDigit()})return null
            val n=part.toIntOrNull()?:return null
            if(n !in 0..255)return null
            out=(out shl 8) or n.toLong()
        }
        return out
    }
    private fun format(ip:Long):String=(0..3).joinToString("."){ ((ip shr (24-8*it)) and 255).toString() }
    private fun mask(prefix:Int):Long=if(prefix==0) 0 else ((0xffffffffL shl (32-prefix)) and 0xffffffffL)

    fun subnets():List<Subnet> = buildList {
        val interfaces=NetworkInterface.getNetworkInterfaces() ?: return@buildList
        while(interfaces.hasMoreElements()) {
            val device=interfaces.nextElement()
            if(!device.isUp || device.isLoopback || device.isVirtual)continue
            for(link in device.interfaceAddresses) {
                val address=link.address as? Inet4Address ?: continue
                val prefix=link.networkPrefixLength.toInt()
                if(prefix !in 16..30 || address.isLoopbackAddress || address.isLinkLocalAddress)continue
                val ip=numeric(address.hostAddress?:continue)?:continue
                val net=ip and mask(prefix)
                val br=net or (mask(prefix) xor 0xffffffffL)
                add(Subnet("${device.displayName} · ${address.hostAddress}/$prefix",device.name,prefix,net,br))
            }
        }
    }.distinctBy{it.cidr+it.interfaceName}

    /** Select only IPv4 hosts within a locally attached subnet; /16+ are clipped to local /24. */
    fun scanRange(subnet:Subnet):List<String> {
        val ipv4=NetworkInterface.getByName(subnet.interfaceName)?.interfaceAddresses?.firstOrNull {
            (it.address as? Inet4Address)?.let{ a->numeric(a.hostAddress)?.let{v -> v in subnet.network..subnet.broadcast} }==true
        }?.address?.hostAddress ?: return emptyList()
        val my=numeric(ipv4)?:return emptyList()
        val subStart= if(subnet.prefix<24)my and mask(24) else subnet.network
        val subEnd=if(subnet.prefix<24)subStart+255 else subnet.broadcast
        val first=maxOf(subnet.network+1,subStart+1)
        val last=minOf(subnet.broadcast-1,subEnd-1)
        if(last<first)return emptyList()
        return (first..last).take(254).map(::format).filter{it!=ipv4}
    }

    private fun validateHost(host:String):String {
        val h=host.trim()
        require(h.isNotBlank() && h.length<=253 && h.none{it.isWhitespace() || it in "/\\@?#"}){"Неверный адрес"}
        // Hostnames are allowed for direct tests; no URL / userinfo fields.
        return h
    }
    fun tcp(host:String,port:Int,timeoutMs:Int=1200):Result=timed {
        require(port in 1..65535)
        try {
            Socket().use {it.connect(InetSocketAddress(validateHost(host),port),timeoutMs.coerceIn(250,5000))}
            Result("Доступен","TCP порт $port открыт",0)
        } catch(e:ConnectException) {Result("Недоступен","TCP $port: соединение отклонено",0)}
        catch(e:SocketTimeoutException) {Result("Недоступен","TCP $port: тайм-аут",0)}
    }
    fun icmp(host:String,timeoutMs:Int=1200):Result=timed {
        val reachable=InetAddress.getByName(validateHost(host)).isReachable(timeoutMs.coerceIn(250,5000))
        Result(if(reachable)"Доступен" else "Неопределённо",if(reachable)"ICMP/системная проверка успешна" else "Ответ не получен (Android может не иметь доступа к ICMP)",0)
    }
    fun udp(host:String,port:Int,timeoutMs:Int=1200):Result=timed {
        require(port in 1..65535)
        DatagramSocket().use { socket ->
            socket.soTimeout=timeoutMs.coerceIn(250,5000)
            socket.connect(InetAddress.getByName(validateHost(host)),port)
            socket.send(DatagramPacket(byteArrayOf(0),1))
            val response=ByteArray(1024)
            try {socket.receive(DatagramPacket(response,response.size));Result("Доступен","Получен UDP ответ от $host:$port",0)}
            catch(_:SocketTimeoutException){Result("Неопределённо","UDP без ответа: невозможно определить открыт ли порт",0)}
            catch(_:PortUnreachableException){Result("Недоступен","ICMP port unreachable",0)}
        }
    }
    fun http(host:String,port:Int,https:Boolean,path:String,expected:Int,timeoutMs:Int=2500):Result=timed {
        require(port in 1..65535 && expected in 100..599)
        val safePath=if(path.startsWith('/'))path else "/$path"
        require(safePath.length<=512 && !safePath.contains('#'))
        val address=validateHost(host)
        val hostUrl=if(address.contains(':') && !address.startsWith('['))"[$address]" else address
        val url=URL("${if(https)"https" else "http"}://$hostUrl:$port$safePath")
        val connection=url.openConnection() as HttpURLConnection
        connection.connectTimeout=timeoutMs.coerceIn(250,5000)
        connection.readTimeout=timeoutMs.coerceIn(250,5000)
        connection.instanceFollowRedirects=false
        connection.requestMethod="GET"
        try {
            val code=connection.responseCode
            Result(if(code==expected)"Доступен" else "Недоступен","HTTP $code, ожидался $expected",0)
        }catch(e:SocketTimeoutException){Result("Недоступен","HTTP(S): тайм-аут",0)}
        catch(e:ConnectException){Result("Недоступен","HTTP(S): соединение отклонено",0)}
        finally{connection.disconnect()}
    }
    private fun timed(action:()->Result):Result {
        val start=System.nanoTime()
        val result=try {action()}catch(e:Exception){Result("Ошибка",e.message?.take(180)?:e.javaClass.simpleName,0)}
        return result.copy(durationMs=(System.nanoTime()-start)/1_000_000)
    }
    fun wol(mac:String,broadcast:String="255.255.255.255",port:Int=9) {
        require(port in 1..65535)
        val bytes=mac.split(':','-').also{require(it.size==6)}.map{require(it.length==2);it.toInt(16).toByte()}.toByteArray()
        val packet=ByteArray(102){i->if(i<6)0xff.toByte() else bytes[(i-6)%6]}
        DatagramSocket().use{it.broadcast=true;it.send(DatagramPacket(packet,packet.size,InetAddress.getByName(validateHost(broadcast)),port))}
    }
    suspend fun discover(subnet:Subnet,onResult:suspend(String,Result)->Unit) = withContext(Dispatchers.IO) {
        val hosts=scanRange(subnet)
        val limiter=Semaphore(12)
        coroutineScope {
            hosts.map{address->async {
                limiter.withPermit {
                    val ping=icmp(address,250)
                    if(ping.status=="Доступен") {
                        onResult(address,ping)
                    }else {
                        for(p in listOf(80,443,22,445,3389)) {
                            val r=tcp(address,p,200)
                            if(r.status=="Доступен") {onResult(address,r);break}
                        }
                    }
                }
            }}.awaitAll()
        }
    }
    suspend fun scanPorts(host:String,ports:List<Int>,onResult:suspend(Int,Result)->Unit) = withContext(Dispatchers.IO) {
        for(p in ports.distinct().take(64)) {
            val result=tcp(host,p,350)
            if(result.status=="Доступен")onResult(p,result)
        }
    }
    fun check(method:String,host:String,port:Int,path:String,expected:Int):Result=when(method) {
        "ICMP"->icmp(host)
        "TCP"->tcp(host,port)
        "UDP"->udp(host,port)
        "HTTP"->http(host,port,false,path,expected)
        "HTTPS"->http(host,port,true,path,expected)
        else->Result("Ошибка","Неизвестный способ проверки",0)
    }
    fun schedule(context:Context) {
        val job=PeriodicWorkRequestBuilder<AvailabilityWorker>(15,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sysadmin_availability",ExistingPeriodicWorkPolicy.KEEP,job)
    }
}

class AvailabilityWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val store=DataStore(applicationContext)
        val monitors=store.list("sysadmin.monitors").filter{it.data.optString("enabled")=="true"}.take(100)
        for(monitor in monitors) {
            try {
                val dev=store.read("hardware.devices",monitor.data.optString("device_id").toLongOrNull()?:continue)?:continue
                val d=monitor.data
                val address=d.optString("host_override").ifBlank{dev.data.optString("ip").ifBlank{dev.data.optString("public_ip")}}
                if(address.isBlank())continue
                val method=d.optString("method","TCP")
                val port=d.optString("port").toIntOrNull() ?: if(method=="HTTPS")443 else if(method=="HTTP")80 else 443
                val expected=d.optString("expected_code").toIntOrNull()?:200
                val test=NetworkDiagnostics.check(method,address,port,d.optString("path","/"),expected)
                val now=System.currentTimeMillis().toString()
                store.put("sysadmin.checks",JSONObject().put("monitor_id",monitor.id.toString()).put("checked_at",now).put("status",test.status).put("details",test.detail))
                val previous=d.optString("_last_status")
                if(previous!=test.status) {
                    d.put("_last_status",test.status)
                    store.put("sysadmin.monitors",d,monitor.id)
                    if(previous.isNotEmpty() && test.status=="Недоступен")HostServices.notify(applicationContext,"Сервер недоступен","${dev.data.optString("name")}: ${test.detail}")
                }
            }catch(e:Exception){Log.w("AvailabilityWorker","Check failed",e)}
        }
        Result.success()
    }
}
