package com.example.modularcrm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.time.Instant

/** Narrowly scoped native capabilities available to declarative actions. */
object HostServices {
    fun notify(context:Context,title:String,body:String) {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val service=context.getSystemService(NotificationManager::class.java)
        service.createNotificationChannel(NotificationChannel("tasks","Задачи",NotificationManager.IMPORTANCE_DEFAULT))
        val builder=Notification.Builder(context,"tasks").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title.take(120)).setContentText(body.take(250)).setAutoCancel(true)
        service.notify((System.currentTimeMillis()%Int.MAX_VALUE).toInt(),builder.build())
    }
    fun calendar(context:Context,record:DataStore.Record,step:JSONObject) {
        if(context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)!=PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return
        val start=record.data.optString(step.str("start_field","start_at")).toLongOrNull() ?: return
        val end=record.data.optString(step.str("end_field","end_at")).toLongOrNull() ?: (start+3600000L)
        val calendarId=step.optLong("calendar_id",-1L).takeIf{it>=0} ?: context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf(CalendarContract.Calendars._ID),"${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=500",null,null)?.use{ if(it.moveToFirst())it.getLong(0) else null } ?:return
        val vals=ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,calendarId)
            put(CalendarContract.Events.TITLE,record.data.optString(step.str("title_field","title"),"Работа"))
            put(CalendarContract.Events.DESCRIPTION,record.data.optString(step.str("description_field","description")))
            put(CalendarContract.Events.DTSTART,start)
            put(CalendarContract.Events.DTEND,end.coerceAtLeast(start+60000))
            put(CalendarContract.Events.EVENT_TIMEZONE,java.util.TimeZone.getDefault().id)
        }
        val existing=record.data.optString("_calendar_event_id").toLongOrNull()
        if(existing!=null)context.contentResolver.update(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,existing),vals,null,null)
        else {
            val uri=context.contentResolver.insert(CalendarContract.Events.CONTENT_URI,vals) ?: return
            val id=android.content.ContentUris.parseId(uri)
            record.data.put("_calendar_event_id",id.toString())
            // internal metadata update without firing another business event
            DataStore(context).put(record.collection,record.data,record.id)
        }
    }
    fun remind(context:Context,record:DataStore.Record,step:JSONObject) {
        val at=record.data.optString(step.str("time_field","due_at")).toLongOrNull()?:return
        val title=record.data.optString(step.str("title_field","title"),"Напоминание")
        val description=record.data.optString(step.str("description_field","description"))
        val repeat=record.data.optString(step.str("repeat_field","repeat"),"Нет")
        val next=if(at>System.currentTimeMillis())at else nextOccurrence(at,repeat)
        if(next>System.currentTimeMillis())schedule(context,record.id,title,description,next,repeat)
    }
    private fun nextOccurrence(from:Long,frequency:String):Long {
        if(frequency=="Нет")return 0L
        var date=java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault())
        repeat(10000) {
            date=when(frequency){"Ежедневно"->date.plusDays(1);"Еженедельно"->date.plusWeeks(1);"Ежемесячно"->date.plusMonths(1);else->return 0L}
            if(date.toInstant().toEpochMilli()>System.currentTimeMillis())return date.toInstant().toEpochMilli()
        }
        return 0L
    }
    fun schedule(context:Context,id:Long,title:String,description:String,atMillis:Long,repeat:String="Нет") {
        val intent=Intent(context,ReminderReceiver::class.java).putExtra("title",title).putExtra("description",description).putExtra("repeat",repeat).putExtra("at",atMillis).putExtra("reminder_id",id)
        val pi=PendingIntent.getBroadcast(context,id.toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm=context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,atMillis,pi)
    }
}
class ReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val title=intent.getStringExtra("title")?:"Напоминание"
        val description=intent.getStringExtra("description")?:""
        HostServices.notify(context,title,description)
        val repeat=intent.getStringExtra("repeat")?:"Нет"
        val from=intent.getLongExtra("at",0L)
        val id=intent.getLongExtra("reminder_id",0L)
        val next=when(repeat) {
            "Ежедневно"->java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
            "Еженедельно"->java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).plusWeeks(1).toInstant().toEpochMilli()
            "Ежемесячно"->java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).plusMonths(1).toInstant().toEpochMilli()
            else->0L
        }
        if(next>0L)HostServices.schedule(context,id,title,description,next,repeat)
    }
}
