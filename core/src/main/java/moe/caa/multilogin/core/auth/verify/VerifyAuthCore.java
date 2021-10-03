package moe.caa.multilogin.core.auth.verify;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.var;
import moe.caa.multilogin.core.auth.yggdrasil.YggdrasilAuthResult;
import moe.caa.multilogin.core.impl.BaseUserLogin;
import moe.caa.multilogin.core.impl.IPlayer;
import moe.caa.multilogin.core.impl.ISender;
import moe.caa.multilogin.core.logger.LoggerLevel;
import moe.caa.multilogin.core.main.MultiCore;
import moe.caa.multilogin.core.user.User;
import moe.caa.multilogin.core.util.FormatContent;
import moe.caa.multilogin.core.util.ValueUtil;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Pattern;

/**
 * 代表后置验证核心类<br>
 * 服务端用户登入校验阶段处理类
 */
@AllArgsConstructor
@Getter
public class VerifyAuthCore {
    private final MultiCore core;

    /**
     * 进行登入校验
     *
     * @param result        在线认证结果返回
     * @param baseUserLogin 用户数据
     */
    public VerifyAuthResult verifyAuth(YggdrasilAuthResult result, BaseUserLogin baseUserLogin) throws SQLException, ExecutionException, InterruptedException {
        core.getLogger().log(LoggerLevel.DEBUG, String.format("Start information check. (username: %s, serverId: %s, ip: %s, service: %s)",
                baseUserLogin.getUsername(), baseUserLogin.getServerId(), baseUserLogin.getIp() == null ? "unknown" : baseUserLogin.getIp(), result.getService().getPath()
        ));

        // 获取缓存的数据
        var user = core.getSqlManager().getUserDataHandler().getUserEntryByOnlineUuid(result.getResult().getId());

        // 验证服务器检查（二次身份验证检查）
        if (user != null) {
            if (!Objects.equals(user.getYggdrasilService(), result.getService().getPath())) {
                String name;
                var service = core.getYggdrasilServicesHandler().getYggdrasilService(user.getYggdrasilService());
                if (service != null) {
                    name = service.getName();
                } else {
                    name = user.getYggdrasilService();
                }
                return VerifyAuthResult.generateKickResult(core.getLanguageHandler().getMessage("auth_verify_failed_path_mismatch", FormatContent.createContent(
                        FormatContent.FormatEntry.builder().name("yggdrasil_server_name").content(name).build()
                )));
            }
        }

        // 名称规范检查
        var reg = result.getService().getNameAllowedRegular();
        if (ValueUtil.isEmpty(reg)) {
            reg = core.getConfig().getNameAllowedRegular();
        }
        if (!ValueUtil.isEmpty(reg)) {
            if (!Pattern.matches(reg, result.getResult().getName())) {
                return VerifyAuthResult.generateKickResult(core.getLanguageHandler().getMessage("auth_verify_failed_username_mismatch", FormatContent.createContent(
                        FormatContent.FormatEntry.builder().name("current_name").content(result.getResult().getName()).build(),
                        FormatContent.FormatEntry.builder().name("regular").content(reg).build()
                )));
            }
        }

        // 重名检查
        if (!result.getService().isSafeId()) {
            var repeatedNameUserEntries = core.getSqlManager().getUserDataHandler().getUserEntryByCurrentName(result.getResult().getName());
            for (User repeatedNameUserEntry : repeatedNameUserEntries) {
                if (repeatedNameUserEntry.equals(user)) continue;
                return VerifyAuthResult.generateKickResult(core.getLanguageHandler().getMessage("auth_verify_failed_username_repeated", FormatContent.createContent(
                        FormatContent.FormatEntry.builder().name("current_name").content(result.getResult().getName()).build()
                )));
            }
        }

        // 是不是新的玩家数据
        boolean newUserData = user == null;

        // 新建玩家数据
        if (newUserData) user = new User(result.getResult().getId(), result.getResult().getName(),
                result.getService().getConvUuid().getResultUuid(result.getResult().getId(),
                        result.getResult().getName()), result.getService().getPath(), false);

        // 处理玩家改名操作
        if (!user.getCurrentName().equals(result.getResult().getName())) {
            core.getLogger().log(LoggerLevel.DEBUG, String.format("Rename, The old user name of the user is %s. (username: %s, serverId: %s, ip: %s, service: %s)",
                    user.getCurrentName(), baseUserLogin.getUsername(), baseUserLogin.getServerId(), baseUserLogin.getIp() == null ? "unknown" : baseUserLogin.getIp(), result.getService().getPath()
            ));
            user.setCurrentName(result.getResult().getName());
        }
        user.setCurrentName(result.getResult().getName());


        // 处理新用户 UUID 冲突
        if (newUserData && result.getService().isConvRepeat()) {
            user.setRedirectUuid(getRepeatUuid(user));
        }

        // 白名单检查
        if (core.getConfig().isWhitelist() || result.getService().isWhitelist()) {
            if (!user.isWhitelist()) {
                if (core.getSqlManager().getCacheWhitelistDataHandler().removeCacheWhitelist(result.getResult().getName())) {
                    user.setWhitelist(true);
                }
                if (core.getSqlManager().getCacheWhitelistDataHandler().removeCacheWhitelist(result.getResult().getId().toString())) {
                    user.setWhitelist(true);
                }
                if (!user.isWhitelist()) {
                    return VerifyAuthResult.generateKickResult(core.getLanguageHandler().getMessage("auth_verify_failed_no_whitelist", FormatContent.empty()));
                }
            }
        }

        // 写入
        if (newUserData) {
            core.getSqlManager().getUserDataHandler().writeNewUserEntry(user);
        } else {
            core.getSqlManager().getUserDataHandler().updateUserEntry(user);
        }

        // 重名踢出（强制登入）
        User finalUser = user;
        FutureTask<IPlayer> task = new FutureTask<>(() -> {
            for (IPlayer player : core.getPlugin().getRunServer().getPlayerManager().getPlayer(result.getResult().getName())) {
                if (!player.getUniqueId().equals(result.getResult().getId())) {
                    player.kickPlayer(core.getLanguageHandler().getMessage("in_game_busy_username", FormatContent.empty()));
                }
            }
            return core.getPlugin().getRunServer().getPlayerManager().getPlayer(finalUser.getRedirectUuid());
        });

        // 等待主线程任务
        core.getPlugin().getRunServer().getScheduler().runTask(task);
        IPlayer player = task.get();


        // 重复登入处理
        if (player != null) {
            if (result.getService().isRefuseRepeatedLogin()) {
                return VerifyAuthResult.generateKickResult(core.getLanguageHandler().getMessage("auth_verify_failed_repeat_login", FormatContent.empty()));
            } else {
                FutureTask<ISender> kick = new FutureTask<>(() -> {
                    player.kickPlayer(core.getLanguageHandler().getMessage("in_game_busy_login", FormatContent.empty()));
                    return null;
                });
                core.getPlugin().getRunServer().getScheduler().runTask(kick);
                kick.get();
            }
        }

        return VerifyAuthResult.generateAllowResult(user);
    }

    private UUID getRepeatUuid(User userData) throws SQLException {
        UUID ret = userData.getRedirectUuid();
        if (core.getSqlManager().getUserDataHandler().getUserEntryByRedirectUuid(ret).size() == 0) {
            return ret;
        }
        userData.setRedirectUuid(UUID.randomUUID());
        return getRepeatUuid(userData);
    }
}
