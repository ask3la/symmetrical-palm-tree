package com.example.modularcrm.crm

import android.content.ContentValues
import android.content.Context
import com.example.modularcrm.core.AppEvent
import com.example.modularcrm.core.PluginRegistry

class CrmRepository(context: Context, private val events: PluginRegistry? = null) {
    private val db = CrmDatabase(context.applicationContext)
    fun clients() = db.query("SELECT id,name,phone,notes,created_at FROM clients ORDER BY name") { Client(it.getLong(0),it.getString(1),it.getString(2),it.getString(3),it.getLong(4)) }
    fun client(id: Long) = clients().firstOrNull { it.id == id }
    fun addClient(name: String, phone: String, notes: String): Long {
        val id = db.insert("clients", ContentValues().apply { put("name",name);put("phone",phone);put("notes",notes);put("created_at",System.currentTimeMillis()) })
        events?.publish(AppEvent("crm.client.created",id)); return id
    }
    fun categories() = db.query("SELECT id,name FROM categories ORDER BY name") { Category(it.getLong(0),it.getString(1)) }
    fun addCategory(name: String) = db.insert("categories",ContentValues().apply { put("name",name) })
    fun categoryIds(clientId: Long) = db.query("SELECT category_id FROM client_categories WHERE client_id=?",arrayOf(clientId.toString())) { it.getLong(0) }.toSet()
    fun setCategory(clientId: Long, categoryId: Long, active: Boolean) {
        if (active) db.exec("INSERT OR IGNORE INTO client_categories(client_id,category_id) VALUES (?,?)",arrayOf(clientId,categoryId))
        else db.exec("DELETE FROM client_categories WHERE client_id=? AND category_id=?",arrayOf(clientId,categoryId))
        events?.publish(AppEvent("crm.client.categories.changed",clientId))
    }
    fun contacts(id: Long) = db.query("SELECT id,client_id,full_name,role,phone FROM contacts WHERE client_id=? ORDER BY full_name",arrayOf(id.toString())) { Contact(it.getLong(0),it.getLong(1),it.getString(2),it.getString(3),it.getString(4)) }
    fun addContact(clientId: Long, name: String, role: String, phone: String) = db.insert("contacts", ContentValues().apply { put("client_id",clientId); put("full_name",name);put("role",role);put("phone",phone) })
    fun interactions(clientId: Long) = db.query("SELECT id,client_id,type,description,occurred_at FROM interactions WHERE client_id=? ORDER BY occurred_at DESC",arrayOf(clientId.toString())) { Interaction(it.getLong(0),it.getLong(1),it.getString(2),it.getString(3),it.getLong(4)) }
    fun allInteractions() = db.query("SELECT id,client_id,type,description,occurred_at FROM interactions ORDER BY occurred_at DESC") { Interaction(it.getLong(0),it.getLong(1),it.getString(2),it.getString(3),it.getLong(4)) }
    fun addInteraction(clientId: Long, type: String, description: String): Long {
        val id = db.insert("interactions",ContentValues().apply { put("client_id",clientId);put("type",type);put("description",description);put("occurred_at",System.currentTimeMillis()) })
        events?.publish(AppEvent("crm.interaction.created",clientId,mapOf("interactionId" to id.toString(),"type" to type))); return id
    }
    fun staleClients(days: Int): List<Client> {
        val cutoff = System.currentTimeMillis() - days.toLong().coerceAtLeast(1) * 86400000L
        return db.query("SELECT c.id,c.name,c.phone,c.notes,c.created_at FROM clients c LEFT JOIN (SELECT client_id, MAX(occurred_at) AS latest FROM interactions WHERE type='Контакт' GROUP BY client_id) i ON c.id=i.client_id WHERE COALESCE(i.latest,c.created_at) < ? ORDER BY COALESCE(i.latest,c.created_at)",arrayOf(cutoff.toString())) { Client(it.getLong(0),it.getString(1),it.getString(2),it.getString(3),it.getLong(4)) }
    }
    fun tasks() = db.query("SELECT id,client_id,title,description,due_at,repeat_rule,done,last_notified FROM tasks ORDER BY done,due_at") { CrmTask(it.getLong(0),if(it.isNull(1))null else it.getLong(1),it.getString(2),it.getString(3),it.getLong(4),it.getString(5),it.getInt(6)==1,it.getLong(7)) }
    fun addTask(clientId: Long?, title: String, description: String, dueAt: Long, rule: String) = db.insert("tasks",ContentValues().apply { put("client_id",clientId);put("title",title);put("description",description);put("due_at",dueAt);put("repeat_rule",rule) })
    fun setTaskDone(task: CrmTask) {
        val values=ContentValues()
        if (task.repeatRule == "NONE") values.put("done",1)
        else { values.put("done",0); values.put("due_at",nextDue(task.dueAt,task.repeatRule));values.put("last_notified",0) }
        db.update("tasks",values,task.id)
        events?.publish(AppEvent("crm.task.completed",task.clientId ?: 0L,mapOf("taskId" to task.id.toString())))
    }
    fun markNotified(taskId: Long, timestamp: Long) = db.update("tasks",ContentValues().apply {put("last_notified",timestamp)},taskId)
    fun advanceAfterReminder(task: CrmTask) {
        if (task.repeatRule != "NONE") db.update("tasks",ContentValues().apply {
            put("due_at",nextDue(task.dueAt,task.repeatRule))
            put("last_notified",System.currentTimeMillis())
        },task.id)
    }
    fun deleteTask(taskId: Long) = db.delete("tasks",taskId)
    fun appointments() = db.query("SELECT id,client_id,title,description,starts_at,ends_at,calendar_event_id FROM appointments ORDER BY starts_at DESC") { Appointment(it.getLong(0),it.getLong(1),it.getString(2),it.getString(3),it.getLong(4),it.getLong(5),if(it.isNull(6))null else it.getLong(6)) }
    fun addAppointment(clientId: Long,title:String,description:String,start:Long,end:Long):Long {
        val id=db.insert("appointments",ContentValues().apply {put("client_id",clientId);put("title",title);put("description",description);put("starts_at",start);put("ends_at",end)})
        events?.publish(AppEvent("crm.appointment.created",clientId,mapOf("appointmentId" to id.toString())));return id
    }
    fun saveCalendarEvent(id:Long,calendarEventId:Long?) = db.update("appointments",ContentValues().apply { if(calendarEventId==null)putNull("calendar_event_id") else put("calendar_event_id",calendarEventId) },id)
    fun editAppointment(id:Long,title:String,description:String,start:Long,end:Long) = db.update("appointments",ContentValues().apply {put("title",title);put("description",description);put("starts_at",start);put("ends_at",end)},id)
    fun deleteAppointment(id:Long) = db.delete("appointments",id)
}

fun nextDue(old:Long,rule:String):Long {
    val calendar=java.util.Calendar.getInstance().apply {timeInMillis=old}
    val field=when(rule){"DAILY"->java.util.Calendar.DAY_OF_MONTH;"WEEKLY"->java.util.Calendar.WEEK_OF_YEAR;else->java.util.Calendar.MONTH}
    do { calendar.add(field,1) } while(calendar.timeInMillis<=System.currentTimeMillis())
    return calendar.timeInMillis
}
