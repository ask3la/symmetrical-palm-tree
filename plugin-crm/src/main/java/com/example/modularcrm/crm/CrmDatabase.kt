package com.example.modularcrm.crm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CrmDatabase(context: Context) : SQLiteOpenHelper(context, "crm.sqlite", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clients(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,phone TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE client_categories(client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,PRIMARY KEY(client_id,category_id))")
        db.execSQL("CREATE TABLE contacts(id INTEGER PRIMARY KEY AUTOINCREMENT,client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,full_name TEXT NOT NULL,role TEXT NOT NULL DEFAULT '',phone TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE interactions(id INTEGER PRIMARY KEY AUTOINCREMENT,client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,type TEXT NOT NULL,description TEXT NOT NULL,occurred_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,client_id INTEGER REFERENCES clients(id) ON DELETE SET NULL,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',due_at INTEGER NOT NULL,repeat_rule TEXT NOT NULL DEFAULT 'NONE',done INTEGER NOT NULL DEFAULT 0,last_notified INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE appointments(id INTEGER PRIMARY KEY AUTOINCREMENT,client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',starts_at INTEGER NOT NULL,ends_at INTEGER NOT NULL,calendar_event_id INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* Future migrations only; never destructive. */ }
    fun insert(table: String, values: ContentValues): Long = writableDatabase.insertOrThrow(table, null, values)
    fun update(table: String, values: ContentValues, id: Long) = writableDatabase.update(table, values, "id=?", arrayOf(id.toString()))
    fun delete(table: String, id: Long) = writableDatabase.delete(table, "id=?", arrayOf(id.toString()))
    fun <T> query(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): List<T> = readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
    fun exec(sql: String, args: Array<Any>) { writableDatabase.execSQL(sql, args) }
}

data class Client(val id: Long, val name: String, val phone: String, val notes: String, val createdAt: Long)
data class Category(val id: Long, val name: String)
data class Contact(val id: Long, val clientId: Long, val name: String, val role: String, val phone: String)
data class Interaction(val id: Long, val clientId: Long, val type: String, val description: String, val occurredAt: Long)
data class CrmTask(val id: Long, val clientId: Long?, val title: String, val description: String, val dueAt: Long, val repeatRule: String, val done: Boolean, val lastNotified: Long)
data class Appointment(val id: Long, val clientId: Long, val title: String, val description: String, val startsAt: Long, val endsAt: Long, val calendarEventId: Long?)
