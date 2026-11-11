package com.example.modularcrm.crm

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** One-way source-of-truth is local SQLite: create/update/delete mapped calendar events. */
class CalendarSync(private val context: Context, private val repo: CrmRepository) {
    fun permitted() = ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED
    fun writableCalendars(): List<Pair<Long,String>> {
        if (!permitted()) return emptyList()
        val projection=arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${CalendarContract.Calendars.VISIBLE}=1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),null)?.use { cursor ->
            buildList { while(cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
        }.orEmpty()
    }
    fun sync(appointment: Appointment, calendarId: Long): Boolean {
        if(!permitted()) return false
        val values=ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,calendarId)
            put(CalendarContract.Events.TITLE,appointment.title)
            put(CalendarContract.Events.DESCRIPTION,appointment.description)
            put(CalendarContract.Events.DTSTART,appointment.startsAt)
            put(CalendarContract.Events.DTEND,appointment.endsAt)
            put(CalendarContract.Events.EVENT_TIMEZONE,TimeZone.getDefault().id)
        }
        return try {
            val eventId=appointment.calendarEventId
            if(eventId!=null) {
                val count=context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId),values,null,null)
                if(count>0) return true
            }
            val uri=context.contentResolver.insert(CalendarContract.Events.CONTENT_URI,values) ?: return false
            repo.saveCalendarEvent(appointment.id,ContentUris.parseId(uri));true
        } catch (_:Exception) {false}
    }
    fun delete(appointment:Appointment) {
        if(permitted() && appointment.calendarEventId!=null) try {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,appointment.calendarEventId),null,null)
        } catch (_:Exception) { }
        repo.deleteAppointment(appointment.id)
    }
}
