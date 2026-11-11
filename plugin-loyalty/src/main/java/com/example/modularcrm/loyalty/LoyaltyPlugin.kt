package com.example.modularcrm.loyalty

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import com.example.modularcrm.core.AppPlugin
import com.example.modularcrm.core.PluginRegistry

/** Example cross-plugin extension. Independent storage; reacts to CRM interaction events. */
class LoyaltyPlugin : AppPlugin {
    override val id="loyalty"
    override val name="Лояльность (пример расширения)"
    override fun onLoad(context:Context, registry:PluginRegistry) {
        val database=LoyaltyDb(context.applicationContext)
        registry.subscribe("crm.interaction.created") { event ->
            if(event.payload["type"]=="Работа") database.award(event.entityId)
        }
        registry.contribute("crm.client.detail") {clientId->
            val revision=remember{mutableIntStateOf(0)}
            Column {
                val points=remember(revision.intValue) { database.points(clientId) }
                Text("Бонусные баллы: $points")
                TextButton(onClick={database.award(clientId);revision.intValue++}) {Text("+ 1 бонус вручную")}
            }
        }
    }
}
private class LoyaltyDb(context:Context):SQLiteOpenHelper(context,"loyalty.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase) {db.execSQL("CREATE TABLE points(client_id INTEGER PRIMARY KEY,total INTEGER NOT NULL DEFAULT 0)")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {}
    fun award(id:Long) {writableDatabase.execSQL("INSERT OR IGNORE INTO points(client_id,total) VALUES(?,0)", arrayOf(id));writableDatabase.execSQL("UPDATE points SET total=total+1 WHERE client_id=?",arrayOf(id))}
    fun points(id:Long):Int=readableDatabase.rawQuery("SELECT total FROM points WHERE client_id=?",arrayOf(id.toString())).use {if(it.moveToFirst())it.getInt(0) else 0}
}
