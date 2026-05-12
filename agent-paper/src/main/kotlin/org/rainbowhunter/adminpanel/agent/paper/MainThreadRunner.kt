package org.rainbowhunter.adminpanel.agent.paper

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.Callable

interface MainThreadRunner {
    fun <T> run(block: () -> T): T

    companion object {
        fun bukkit(plugin: Plugin): MainThreadRunner = object : MainThreadRunner {
            override fun <T> run(block: () -> T): T {
                if (Bukkit.isPrimaryThread()) return block()
                return Bukkit.getScheduler().callSyncMethod(plugin, Callable { block() }).get()
            }
        }

        fun inline(): MainThreadRunner = object : MainThreadRunner {
            override fun <T> run(block: () -> T): T = block()
        }
    }
}
