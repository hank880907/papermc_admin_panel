package org.rainbowhunter.adminpanel.agent.paper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class ApCommandExecutor(
    private val client: CoreClient,
    private val scope: CoroutineScope,
    private val main: MainThreadRunner,
) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§eUsage: /ap <register|grant>")
            return true
        }
        when (args[0].lowercase()) {
            "register" -> handleRegister(sender)
            "grant" -> handleGrant(sender, args)
            else -> sender.sendMessage("§cUnknown subcommand: ${args[0]}")
        }
        return true
    }

    private fun handleRegister(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§c/ap register must be run by an online player.")
            return
        }
        if (!sender.hasPermission("adminpanel.register")) {
            sender.sendMessage("§cYou don't have permission to register.")
            return
        }
        val uuid = sender.uniqueId.toString()
        val name = sender.name
        val isOp = sender.isOp
        scope.launch {
            val result = ApCommandLogic.register(client, uuid, name, isOp)
            replyRegister(sender, result)
        }
    }

    private fun handleGrant(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("adminpanel.grant")) {
            sender.sendMessage("§cYou don't have permission to grant access.")
            return
        }
        if (sender !is Player) {
            sender.sendMessage("§c/ap grant must be run by an online player so we can identify you as granter.")
            return
        }
        if (args.size < 2) {
            sender.sendMessage("§eUsage: /ap grant <player>")
            return
        }
        val granterUuid = sender.uniqueId.toString()
        val targetName = args[1]
        scope.launch {
            val resolved = main.run { resolveTarget(targetName) }
            if (resolved == null) {
                main.run { sender.sendMessage("§cCould not resolve player '$targetName'.") }
                return@launch
            }
            val (targetUuid, resolvedName) = resolved
            val result = ApCommandLogic.grant(client, granterUuid, targetUuid, resolvedName)
            replyGrant(sender, resolvedName, result)
        }
    }

    private fun resolveTarget(name: String): Pair<String, String>? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId.toString() to it.name }
        val cached = Bukkit.getOfflinePlayerIfCached(name)
        if (cached != null && cached.name != null) return cached.uniqueId.toString() to cached.name!!
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(name)
        val resolvedName = offline.name ?: return null
        return offline.uniqueId.toString() to resolvedName
    }

    private fun replyRegister(sender: CommandSender, result: ApRegisterResult) {
        val message = when (result) {
            is ApRegisterResult.Issued ->
                "§aClick to set your password: §b${result.url}"
            is ApRegisterResult.NoAccess ->
                "§cYou don't have access to the Admin Panel. Ask an admin to run §e/ap grant <you>§c."
            is ApRegisterResult.Error ->
                "§cFailed to issue registration link: ${result.message}"
        }
        main.run { sender.sendMessage(message) }
    }

    private fun replyGrant(sender: CommandSender, target: String, result: ApGrantResult) {
        val message = when (result) {
            is ApGrantResult.Granted ->
                "§aGranted Admin Panel access to §b$target§a. They can now run §e/ap register§a."
            is ApGrantResult.NotAdmin ->
                "§cYou must register and be an admin in the Admin Panel before granting access."
            is ApGrantResult.AlreadyGranted ->
                "§e$target already has access."
            is ApGrantResult.Error ->
                "§cFailed to grant: ${result.message}"
        }
        main.run { sender.sendMessage(message) }
    }
}
