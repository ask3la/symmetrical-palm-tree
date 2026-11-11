package com.example.modularcrm.core

import android.content.Context
import androidx.compose.runtime.Composable

/** Plugins are trusted build-time Android library modules. */
interface AppPlugin {
    val id: String
    val name: String
    fun onLoad(context: Context, registry: PluginRegistry) {}
    fun pages(): List<PluginPage> = emptyList()
    fun settings(): List<PluginPage> = emptyList()
}

class PluginPage(val key: String, val label: String, val content: @Composable () -> Unit)

data class AppEvent(val topic: String, val entityId: Long, val payload: Map<String, String> = emptyMap())
fun interface EventListener { fun onEvent(event: AppEvent) }

class PluginRegistry {
    private val listeners = mutableMapOf<String, MutableList<EventListener>>()
    private val extensions = mutableMapOf<String, MutableList<@Composable (Long) -> Unit>>()

    fun subscribe(topic: String, listener: EventListener) { listeners.getOrPut(topic) { mutableListOf() }.add(listener) }
    fun publish(event: AppEvent) { (listeners[event.topic].orEmpty() + listeners["*"].orEmpty()).forEach { it.onEvent(event) } }
    fun contribute(slot: String, component: @Composable (Long) -> Unit) {
        extensions.getOrPut(slot) { mutableListOf() }.add(component)
    }
    @Composable fun Render(slot: String, entityId: Long) {
        extensions[slot].orEmpty().forEach { it(entityId) }
    }
}
