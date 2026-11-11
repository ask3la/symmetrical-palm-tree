package com.example.modularcrm.crm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class TaskScheduler(private val context:Context) {
    fun schedule(task:CrmTask) {
        val manager=context.getSystemService(AlarmManager::class.java)
        val intent=Intent(context,TaskAlarmReceiver::class.java).putExtra("task_id",task.id)
        val pending=PendingIntent.getBroadcast(context,(task.id%Int.MAX_VALUE).toInt(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,task.dueAt.coerceAtLeast(System.currentTimeMillis()+1000),pending)
    }
    fun cancel(id:Long) {
        val pending=PendingIntent.getBroadcast(context,(id%Int.MAX_VALUE).toInt(),Intent(context,TaskAlarmReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if(pending!=null) context.getSystemService(AlarmManager::class.java).cancel(pending)
    }
    fun rescheduleAll() { CrmRepository(context).tasks().filter { !it.done }.forEach(::schedule) }
}
