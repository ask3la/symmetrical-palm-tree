package com.example.modularcrm.crm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** AlarmManager wakes at each task's due time (inexact, battery-friendly). */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getLongExtra("task_id",-1)
        val repo=CrmRepository(context)
        val task=repo.tasks().firstOrNull { it.id==id } ?: return
        if(task.done || task.dueAt>System.currentTimeMillis()+60000L) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("crm_tasks","CRM: задачи",NotificationManager.IMPORTANCE_DEFAULT))
        if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
            val notification=NotificationCompat.Builder(context,"crm_tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(task.title)
                .setContentText(task.description.ifBlank { "Время выполнить задачу" }).setStyle(NotificationCompat.BigTextStyle().bigText(task.description))
                .setAutoCancel(true).build()
            manager.notify((task.id%Int.MAX_VALUE).toInt(),notification)
        }
        repo.markNotified(task.id,System.currentTimeMillis())
        if(task.repeatRule!="NONE") {
            repo.advanceAfterReminder(task)
            repo.tasks().firstOrNull { it.id==task.id }?.let { TaskScheduler(context).schedule(it) }
        }
    }
}
