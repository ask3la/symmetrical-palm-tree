package com.example.modularcrm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

private val ID = Regex("[a-z][a-z0-9_]{1,63}")
private val TOPIC = Regex("[a-z][a-z0-9_.]{1,127}")
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.array(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
fun JSONObject.str(key: String, fallback: String = ""): String = optString(key, fallback)

/** All business data lives in the host's private SQLite database. No plugin may run arbitrary SQL. */
class DataStore(context: Context): SQLiteOpenHelper(context, "workspace.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, collection TEXT NOT NULL, data TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX records_collection ON records(collection)")
        db.execSQL("CREATE TABLE preferences (plugin TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(plugin,key))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    data class Record(val id: Long, val collection: String, val data: JSONObject, val createdAt:Long = 0L)
    fun list(collection: String, relationField: String? = null, relationValue: String? = null): List<Record> {
        return readableDatabase.query("records", arrayOf("id", "data", "created_at"), "collection=?", arrayOf(collection), null, null, "updated_at DESC").use { c ->
            buildList {
                while(c.moveToNext()) {
                    val value = JSONObject(c.getString(1))
                    if (relationField == null || value.optString(relationField) == relationValue) add(Record(c.getLong(0), collection, value, c.getLong(2)))
                }
            }
        }
    }
    fun read(collection: String, id: Long): Record? = readableDatabase.query("records", arrayOf("data", "created_at"), "collection=? AND id=?", arrayOf(collection,id.toString()), null,null,null).use { c ->
        if(c.moveToFirst()) Record(id, collection, JSONObject(c.getString(0)), c.getLong(1)) else null
    }
    fun put(collection: String, data: JSONObject, id: Long? = null): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("data",data.toString());put("updated_at",now) }
        if(id != null) {
            require(writableDatabase.update("records",values,"collection=? AND id=?",arrayOf(collection,id.toString()))==1) { "Запись не найдена" }
            return id
        }
        values.put("collection",collection);values.put("created_at",now)
        return writableDatabase.insertOrThrow("records",null,values)
    }
    fun delete(collection:String,id:Long):Boolean = writableDatabase.delete("records","collection=? AND id=?",arrayOf(collection,id.toString()))>0
    fun getSetting(plugin:String,key:String,default:String=""):String = readableDatabase.query("preferences",arrayOf("value"),"plugin=? AND key=?",arrayOf(plugin,key),null,null,null).use { if(it.moveToFirst())it.getString(0) else default }
    fun setSetting(plugin:String,key:String,value:String) { writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply {put("plugin",plugin);put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE) }
}

data class Plugin(val file: File, val manifest: JSONObject) {
    val id: String get()=manifest.getString("id")
    val name: String get()=manifest.getString("name")
    val collections:List<JSONObject> get()=manifest.array("collections")
    val pages:List<JSONObject> get()=manifest.array("pages")
    val extensions:List<JSONObject> get()=manifest.array("extensions")
    val hooks:List<JSONObject> get()=manifest.array("hooks")
    val actions:List<JSONObject> get()=manifest.array("actions")
    val settings:List<JSONObject> get()=manifest.array("settings")
    val dependencies:List<String> get()=manifest.optJSONArray("depends")?.let {a->(0 until a.length()).map{a.getString(it)}}?: emptyList()
    val writePermissions:List<String> get()=manifest.optJSONObject("permissions")?.optJSONArray("write")?.let {a->(0 until a.length()).map{a.getString(it)}} ?: emptyList()
}

/** ZIP format v2. No dex, JS, SQL, reflection, shell, or arbitrary code execution. */
class PluginEngine(val context: Context) {
    val data = DataStore(context)
    private val directory=File(context.filesDir,"plugins").apply{mkdirs()}
    val plugins=mutableListOf<Plugin>()
    var changed: (() -> Unit)? = null
    init {
        // CRM is not compiled into the host. It is a regular packaged .mcrm file, preinstalled on first launch.
        val bootstrap=File(directory,"crm.mcrm")
        if(!bootstrap.exists()) context.assets.open("plugins/crm.mcrm").use {src->bootstrap.outputStream().use {src.copyTo(it)} }
        reload()
    }
    private fun check(json:JSONObject) {
        require(json.getInt("api")==2) {"Требуется API 2"}
        val id=json.getString("id")
        require(ID.matches(id) && json.getString("name").length in 1..90) {"Неверные данные плагина"}
        val local=mutableSetOf<String>()
        json.array("collections").also{require(it.size<=32)}.forEach { c ->
            require(ID.matches(c.getString("id")))
            require(local.add(c.getString("id")))
            val fields=c.array("fields");require(fields.size<=40)
            require(fields.map {it.getString("id")}.distinct().size==fields.size)
            fields.forEach { f ->
                require(ID.matches(f.getString("id")))
                require(f.getString("type") in setOf("text","multiline","number","date","datetime","time","weekday","monthday","select","reference","boolean","file"))
                if(f.getString("type")=="reference") require(f.getString("target").matches(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")))
            }
        }
        require(json.array("pages").size<=24 && json.array("extensions").size<=48 && json.array("hooks").size<=48 && json.array("actions").size<=64)
        json.array("pages").forEach {p ->
            require(ID.matches(p.getString("id")))
            when(p.str("kind","collection")) {
                "collection","metrics" -> require(p.getString("collection") in local)
                "native_tool" -> require(id=="sysadmin" && p.str("tool")=="network")
                "stale" -> require(p.getString("collection").contains('.') && p.getString("activity_collection").contains('.'))
                else -> error("Неизвестный тип раздела")
            }
        }
        json.array("extensions").forEach {e ->
            require(e.getString("kind") in setOf("detail_tab","form_field","detail_action","label_patch","hide_field","field_patch"))
            require(e.getString("target").matches(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*\\.(detail.tabs|form.fields|detail.actions|labels|detail.fields)")))
            if(e.getString("kind")=="detail_tab") require(e.getString("collection").contains('.'))
        }
        json.array("actions").forEach {a -> require(ID.matches(a.getString("id")));require(a.array("steps").size<=24)}
        json.array("hooks").forEach {h -> require(TOPIC.matches(h.getString("event")));require(h.array("steps").size<=24)}
        val permissions=json.optJSONObject("permissions")
        permissions?.optJSONArray("write")?.let { list -> repeat(list.length()){require(list.getString(it).matches(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*"))) } }
        for(step in (json.array("actions")+json.array("hooks")).flatMap{it.array("steps")}) require(step.getString("type") in setOf("create","update","delete","emit","notify","calendar_upsert","set_field","schedule_reminder"))
    }
    private fun parse(file:File):Plugin = ZipFile(file).use {zip ->
        require(zip.size() in 1..64) {"Неверный размер архива"}
        var sum=0L
        val seen=mutableSetOf<String>()
        val entries=zip.entries()
        while(entries.hasMoreElements()) {
            val entry=entries.nextElement()
            require(entry.name=="manifest.json" || entry.name.startsWith("assets/")) {"Недопустимый файл: ${entry.name}"}
            require(!entry.name.contains("..") && !entry.name.startsWith("/") && seen.add(entry.name)) {"Небезопасный ZIP"}
            require(entry.size in 0L..2_000_000L) {"Слишком большой файл"}
            sum+=entry.size;require(sum<=4_000_000) {"Превышен общий размер"}
        }
        val m=zip.getEntry("manifest.json")?:error("manifest.json отсутствует")
        val json=zip.getInputStream(m).use{ val bytes=it.readBytes();require(bytes.size<=256_000);JSONObject(String(bytes,Charsets.UTF_8)) }
        check(json)
        require(file.name=="${json.getString("id")}.mcrm")
        Plugin(file,json)
    }
    fun reload() {
        plugins.clear()
        val candidates=directory.listFiles{f->f.extension=="mcrm"}.orEmpty().sortedBy{it.name}.mapNotNull { f ->
            runCatching {parse(f)}.onFailure{Log.e("PluginEngine","Invalid plugin ${f.name}",it)}.getOrNull()
        }
        val loaded=mutableListOf<Plugin>()
        for(i in 0 until candidates.size) {
            var progressed=false
            candidates.forEach { p ->
                if(p !in loaded && p.dependencies.all { dep -> loaded.any{it.id==dep} } && p.collections.none {c->loaded.any{other->other.id==p.id}}) {loaded.add(p);progressed=true}
            }
            if(!progressed) break
        }
        plugins.addAll(loaded)
        changed?.invoke()
    }
    fun inspect(uri:Uri):JSONObject {
        val temp=File.createTempFile("inspect_",".zip",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use {input->temp.outputStream().use {output->
                val buffer=ByteArray(8192);var count=0L
                while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=4_000_000);output.write(buffer,0,n)}
            }}?:error("Файл недоступен")
            val json=ZipFile(temp).use{ zip ->
                val entry=zip.getEntry("manifest.json")?:error("Нет manifest.json")
                val bytes=zip.getInputStream(entry).use{it.readBytes()};require(bytes.size<=256_000)
                JSONObject(String(bytes,Charsets.UTF_8))
            }
            check(json);return json
        }finally{temp.delete()}
    }
    private fun compareVersions(a:String,b:String):Int {
        val pattern=Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")
        val aa=pattern.matchEntire(a)?.groupValues?.drop(1)?.map{it.toIntOrNull()?:0} ?: error("Версия должна быть X.Y.Z")
        val bb=pattern.matchEntire(b)?.groupValues?.drop(1)?.map{it.toIntOrNull()?:0} ?: error("Текущая версия имеет неверный формат")
        return aa.zip(bb).firstOrNull{it.first!=it.second}?.let{it.first.compareTo(it.second)}?:0
    }
    fun install(uri:Uri, approvedWrites:Set<String> = emptySet()):Plugin {
        val tmp=File.createTempFile("incoming_",".mcrm",directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { output ->
                val buffer=ByteArray(8192);var total=0L
                while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=4_000_000);output.write(buffer,0,count)}
            }} ?:error("Не удалось открыть файл")
            // Read ID from manifest before creating its canonical destination.
            val id=ZipFile(tmp).use{zip-> val e=zip.getEntry("manifest.json")?:error("Нет manifest.json"); val b=zip.getInputStream(e).use{it.readBytes()};require(b.size<=256_000);JSONObject(String(b)).getString("id")}
            require(ID.matches(id));val target=File(directory,"$id.mcrm")
            // Validate in a canonical-name staging directory before touching the installed archive.
            val stageDir=File(context.cacheDir,"mcrm_stage").apply{mkdirs()}
            val staged=File(stageDir,"$id.mcrm")
            tmp.copyTo(staged,overwrite=true)
            try {
                val incoming=parse(staged)
                val current=plugins.firstOrNull{it.id==id}
                if(current!=null) {
                    require(compareVersions(incoming.manifest.str("version"),current.manifest.str("version"))>0) {
                        "Установлена версия ${current.manifest.str("version")}. Для обновления нужна более новая версия"
                    }
                    val oldCollections=current.collections.map{it.str("id")}.toSet()
                    val newCollections=incoming.collections.map{it.str("id")}.toSet()
                    require(newCollections.containsAll(oldCollections)) {"Обновление удаляет коллекции: ${oldCollections-newCollections}. Требуется миграция"}
                    for(oldCollection in current.collections) {
                        val newest=incoming.collections.first{it.str("id")==oldCollection.str("id")}
                        val oldFields=oldCollection.array("fields").map{it.str("id")}.toSet()
                        val newFields=newest.array("fields").map{it.str("id")}.toSet()
                        require(newFields.containsAll(oldFields)) {"Удалены поля коллекции ${oldCollection.str("id")}. Нужна миграция"}
                    }
                }
                val existingPermissions=context.getSharedPreferences("plugin_permissions",Context.MODE_PRIVATE).getStringSet(id,emptySet()).orEmpty()
                val allowed=approvedWrites+existingPermissions
                require(incoming.writePermissions.all{it in allowed}) {"Требуется разрешение на изменение данных: ${incoming.writePermissions}"}
                require(incoming.dependencies.all{dep->dep==id || plugins.any{it.id==dep}}) {"Не установлены зависимости: ${incoming.dependencies}"}
                val backup=File(directory,"$id.mcrm.backup")
                if(target.exists())target.copyTo(backup,overwrite=true)
                try {
                    staged.copyTo(target,overwrite=true)
                    reload()
                    require(plugins.any{it.id==id}) {"Новый плагин не загрузился"}
                    context.getSharedPreferences("plugin_permissions",Context.MODE_PRIVATE).edit().putStringSet(id,allowed).apply()
                    backup.delete()
                    return incoming.copy(file=target)
                }catch(e:Exception){
                    if(backup.exists())backup.copyTo(target,overwrite=true) else target.delete()
                    reload()
                    throw e
                }finally{backup.delete()}
            }finally{staged.delete()}
        } finally{tmp.delete()}
    }
    fun remove(id:String) {
        require(id!="crm") {"CRM — стартовый плагин. Для его удаления требуется специальная миграция данных."}
        require(plugins.none{it.dependencies.contains(id)}) {"От плагина зависят другие модули"}
        File(directory,"$id.mcrm").delete()
        context.getSharedPreferences("plugin_permissions",Context.MODE_PRIVATE).edit().remove(id).apply()
        reload()
    }
    fun collectionDef(full:String):JSONObject? {val p=full.substringBefore('.');val c=full.substringAfter('.',"");return plugins.firstOrNull{it.id==p}?.collections?.firstOrNull{it.str("id")==c}}
    fun fields(collection:String):List<JSONObject> {
        val base=collectionDef(collection)?.array("fields").orEmpty()
        val extras=extensions("$collection.form.fields", "form_field").mapNotNull {it.optJSONObject("field")}
        val patches=extensions("$collection.form.fields", "field_patch")
        val hidden=extensions("$collection.detail.fields", "hide_field").map{it.str("field")}.toSet()
        return (base+extras).filterNot{it.str("id") in hidden}.distinctBy{it.str("id")}.map { original ->
            val patched=JSONObject(original.toString())
            patches.filter{it.str("field")==original.str("id")}.forEach{patch ->
                for(key in listOf("label","required","order")) if(patch.has(key))patched.put(key,patch.get(key))
            }
            patched
        }.sortedBy{it.optInt("order",100)}
    }
    fun extensions(target:String,kind:String):List<JSONObject> = plugins.flatMap{it.extensions}.filter{it.str("target")==target && it.str("kind")==kind}.sortedBy{it.optInt("priority",0)}
    fun nameOf(collection:String,id:String):String {
        val r=data.read(collection,id.toLongOrNull()?:return id)?:return "#${id}"
        val first=fields(collection).firstOrNull{it.str("id") in listOf("name","title","fullname")}
        return r.data.str(first?.str("id")?:"name","#${id}").ifEmpty{"#$id"}
    }
    fun execute(owner:String,steps:List<JSONObject>,record:DataStore.Record?,event:JSONObject=JSONObject(), depth:Int=0) {
        require(depth<12){"Превышена глубина цепочки событий"}
        require(steps.size<=24)
        for(step in steps) {
            if(!condition(step.optJSONObject("when"),record,event))continue
            val type=step.getString("type")
            val target=step.str("collection",record?.collection?:"")
            if(type in setOf("create","update","delete","set_field")) {
                val approved=context.getSharedPreferences("plugin_permissions",Context.MODE_PRIVATE).getStringSet(owner,emptySet()).orEmpty()
                require(target.substringBefore('.')==owner || (plugins.firstOrNull{it.id==owner}?.writePermissions?.contains(target)==true && target in approved)) {"Нет разрешения на изменение $target"}
                require(collectionDef(target)!=null) {"Неизвестная коллекция $target"}
            }
            when(type) {
                "create" -> {
                    val values=resolve(step.optJSONObject("values")?:JSONObject(),record,event)
                    val newId=data.put(target,values)
                    emit("$target.created",newId,values,depth+1)
                }
                "update","set_field" -> {
                    val id=resolveString(step.str("id","${'$'}{record.id}"),record,event).toLongOrNull()?:continue
                    val old=data.read(target,id)?:continue
                    val values=resolve(step.optJSONObject("values")?:JSONObject(),record,event)
                    values.keys().forEach {key->old.data.put(key,values.get(key))}
                    data.put(target,old.data,id);emit("$target.updated",id,old.data,depth+1)
                }
                "delete" -> {
                    val id=resolveString(step.str("id","${'$'}{record.id}"),record,event).toLongOrNull()?:continue
                    if(data.delete(target,id))emit("$target.deleted",id,JSONObject(),depth+1)
                }
                "emit" -> emit(step.getString("topic"),record?.id?:0L,resolve(step.optJSONObject("values")?:JSONObject(),record,event),depth+1)
                "notify" -> HostServices.notify(context,resolveString(step.str("title","Задача"),record,event),resolveString(step.str("message"),record,event))
                "calendar_upsert" -> if(record!=null) HostServices.calendar(context,record,step)
                "schedule_reminder" -> if(record!=null) HostServices.remind(context,record,step)
            }
        }
        changed?.invoke()
    }
    fun emit(topic:String,id:Long,payload:JSONObject=JSONObject(),depth:Int=0) {
        if(depth>=12) {Log.w("PluginEngine","Event recursion limit: $topic");return}
        val event=JSONObject().put("topic",topic).put("id",id).put("payload",payload)
        plugins.forEach{plugin->plugin.hooks.filter{it.str("event")==topic}.forEach{hook->
            runCatching{execute(plugin.id,hook.array("steps"),DataStore.Record(id,topic.substringBeforeLast('.'),payload),event,depth+1)}.onFailure{Log.e("PluginEngine","Hook failed",it)}
        }}
    }
    fun action(owner:String,id:String,record:DataStore.Record?) {
        val spec=plugins.firstOrNull{it.id==owner}?.actions?.firstOrNull{it.str("id")==id}?:error("Неизвестное действие")
        execute(owner,spec.array("steps"),record)
    }
    fun save(collection:String,fields:JSONObject,id:Long?=null):Long {
        require(collectionDef(collection)!=null)
        val owner=collection.substringBefore('.')
        val result=data.put(collection,fields,id)
        val current=data.read(collection,result)
        emit("$collection.${if(id==null)"created" else "updated"}",result,fields)
        plugins.firstOrNull{it.id==owner}?.actions?.filter{ it.str("trigger")=="${collection.substringAfter('.')}.${if(id==null)"created" else "updated"}" }?.forEach{execute(owner,it.array("steps"),current)}
        changed?.invoke()
        return result
    }
    fun delete(collection:String,id:Long) { if(data.delete(collection,id))emit("$collection.deleted",id) ;changed?.invoke() }
    private fun condition(whenObj:JSONObject?,record:DataStore.Record?,event:JSONObject):Boolean {
        if(whenObj==null)return true
        val actual=resolveString(whenObj.str("left"),record,event)
        if(whenObj.has("equals"))return actual==resolveString(whenObj.getString("equals"),record,event)
        if(whenObj.has("not_equals"))return actual!=resolveString(whenObj.getString("not_equals"),record,event)
        return true
    }
    private fun resolve(source:JSONObject,record:DataStore.Record?,event:JSONObject):JSONObject {
        val result=JSONObject();source.keys().forEach{key->result.put(key,resolveString(source.optString(key),record,event))};return result
    }
    private fun resolveString(source:String,record:DataStore.Record?,event:JSONObject):String = Regex("\\$\\{([^}]+)\\}").replace(source){ match ->
        val path=match.groupValues[1]
        when {
            path=="record.id"->record?.id?.toString().orEmpty()
            path.startsWith("record.")->record?.data?.optString(path.removePrefix("record.")).orEmpty()
            path=="event.id"->event.optString("id")
            path.startsWith("event.")->event.optJSONObject("payload")?.optString(path.removePrefix("event.")).orEmpty()
            else->""
        }
    }
}
