package moe.caa.multilogin.core;

import moe.caa.multilogin.core.data.PluginData;
import moe.caa.multilogin.core.data.SQLHandler;
import moe.caa.multilogin.core.data.UserEntry;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 命令处理器
 */
public class CommandHandler {

    /**
     * 处理命令“multilogin reload”
     */
    public static void executeReload(ISender commandSender) {
        if (testPermission(commandSender, "multilogin.multilogin.reload")) {
            try {
                PluginData.reloadConfig();
            } catch (Exception e) {
                e.printStackTrace();
            }
            commandSender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgReload")));
        }
    }

    /**
     * 处理命令“multilogin query [target]”
     */
    public static void executeQuery(ISender commandSender, String[] strings) {
        if (testPermission(commandSender, "multilogin.multilogin.query")) {
            String s = (strings.length == 2) ? strings[1] : (commandSender.isPlayer() ? commandSender.getSenderName() : null);
            if (s != null) {
                MultiCore.getPlugin().runTaskAsyncLater(()->{
                    try {
                        List<UserEntry> userList = SQLHandler.getUserEntryByCurrentName(s);
                        try {
                            UUID uuid = UUID.fromString(s);
                            UserEntry byUuid = SQLHandler.getUserEntryByOnlineUuid(uuid);
                            if(byUuid != null){
                                userList.add(byUuid);
                            }
                        } catch (IllegalArgumentException ignore){}

                        if (userList.size() > 0) {
                            for (UserEntry entry : userList) {
                                commandSender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgYDQuery"), s, entry.getServiceEntry().getName())));
                            }
                        } else {
                            commandSender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgYDQueryNoRel"), s)));
                        }
                    } catch (Exception e){
                        e.printStackTrace();
                        MultiCore.getPlugin().getPluginLogger().severe("执行命令时出现异常");
                        commandSender.sendMessage(new TextComponent(ChatColor.RED + "执行命令时出现异常"));
                    }
                }, 0);
            } else {
                commandSender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgNoPlayer")));
            }
        }
    }

    /**
     * 处理命令“whitelist add target”
     */
    public static void executeAdd(ISender sender, String[] args) {
        if (testPermission(sender, "multilogin.whitelist.add")){
            if (PluginData.addCacheWhitelist(args[1])) {
                sender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgAddWhitelist"), args[1])));
            } else {
                sender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgAddWhitelistAlready"), args[1])));
            }
        }
    }

    /**
     * 处理命令“whitelist remove target”
     * todo whitelist remove target命令仍未完成
     */
    public static void executeRemove(ISender sender, String[] args) {
        if (testPermission(sender, "multilogin.whitelist.remove")){
            MultiCore.getPlugin().runTaskAsyncLater(()->{
                boolean flag = false;
                if (PluginData.removeCacheWhitelist(args[1])) {
                    flag = true;
                }
                try {
                    List<UserEntry> userEntries = SQLHandler.getUserEntryByCurrentName(args[1]);
                    for (UserEntry entry : userEntries) {
                        entry.setWhitelist(0);
                        SQLHandler.updateUserEntry(entry);
                    }
                    UserEntry byUuid = SQLHandler.getUserEntryByOnlineUuid(UUID.fromString(args[1]));
                    byUuid.setWhitelist(0);
                    SQLHandler.updateUserEntry(byUuid);
                } catch (IllegalArgumentException | NullPointerException ignored) {
                } catch (Exception e){
                    e.printStackTrace();
                    MultiCore.getPlugin().getPluginLogger().severe("执行命令时出现异常");
                    sender.sendMessage(new TextComponent(ChatColor.RED + "执行命令时出现异常"));
                    return;
                }
                if (flag) {
                    sender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgDelWhitelist"), args[1])));
                } else {
                    sender.sendMessage(new TextComponent(String.format(PluginData.configurationConfig.getString("msgDelWhitelistAlready"), args[1])));
                }
            }, 0);
        }

    }

    /**
     * 处理命令“whitelist on”
     */
    public static void executeOn(ISender sender) {
        if (testPermission(sender, "multilogin.whitelist.on"))
            if (!PluginData.isWhitelist()) {
                PluginData.setWhitelist(true);
                sender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgOpenWhitelist")));
            } else {
                sender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgOpenWhitelistAlready")));
            }
    }

    /**
     * 处理命令“whitelist off”
     */
    public static void executeOff(ISender sender) {
        if (testPermission(sender, "multilogin.whitelist.off"))
            if (PluginData.isWhitelist()) {
                PluginData.setWhitelist(false);
                sender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgCloseWhitelist")));
            } else {
                sender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgCloseWhitelistAlready")));
            }
    }

    /**
     * 测试sender是否有permission权限
     * @param sender 指令发送者
     * @param permission 权限
     * @return 是否拥有该权限，若没有该权限将会自动回复
     */
    public static boolean testPermission(ISender sender, String permission) {
        if (sender.hasPermission(permission)){
            return true;
        }
        sender.sendMessage(new TextComponent(PluginData.configurationConfig.getString("msgNoPermission")));
        return false;
    }
}
