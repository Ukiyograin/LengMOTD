package shaziawa.LengMOTD;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.CachedServerIcon;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class LengMOTD extends JavaPlugin implements Listener {
    
    private static class MotdEntry {
        String motd1;
        String motd2;
        
        MotdEntry(String motd1, String motd2) {
            this.motd1 = motd1;
            this.motd2 = motd2;
        }
        
        String getFormattedMotd() {
            String line1 = ChatColor.translateAlternateColorCodes('&', motd1);
            String line2 = ChatColor.translateAlternateColorCodes('&', motd2);
            return line1 + "\n" + line2;
        }
    }
    
    private List<MotdEntry> motdEntries;
    private List<CachedServerIcon> serverIcons;
    private int currentMotdIndex = 0;
    private boolean isCycling = true;
    private int cycleDelay = 20;
    private boolean whitelistMode = false;
    private String maintenanceKickMessage = "服务器正在维护中";
    private boolean useCustomPlayerList = false;
    private String customPlayerList = "CFC Powered by UKiyograin";
    private int maxPlayers = 100; // 默认最大玩家数
    private List<String> playerHoverTexts = new ArrayList<>(); // 玩家悬停文本
    
    // 一言相关配置
    private boolean useHitokoto = false; // 是否启用一言
    private String hitokotoCache = ""; // 一言缓存
    private long lastHitokotoFetchTime = 0; // 上次获取时间
    private long hitokotoCacheTime = 30000; // 缓存时间（30秒）
    private String hitokotoApiUrl = "https://v1.hitokoto.cn/"; // 一言API
    private String hitokotoCategory = ""; // 一言分类（空为随机）
    private List<String> hitokotoFallbackTexts = new ArrayList<>(); // 备用文本
    private String hitokotoColor = "&8";
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        loadServerIcons();
        getServer().getPluginManager().registerEvents(this, this);
        startMotdCycleTask();
        setupCommands();
        
        // 预获取一言（如果启用）
        if (useHitokoto) {
            fetchHitokotoAsync();
        }
        
        getLogger().info("LengMOTD 插件已启用!");
        getLogger().info("已加载 " + motdEntries.size() + " 个 MOTD 预设");
        getLogger().info("已加载 " + serverIcons.size() + " 个服务器图标");
        getLogger().info("白名单模式: " + (whitelistMode ? "启用" : "禁用"));
        getLogger().info("自定义玩家列表: " + (useCustomPlayerList ? "启用" : "禁用"));
        getLogger().info("最大玩家数: " + maxPlayers);
        getLogger().info("一言功能: " + (useHitokoto ? "启用" : "禁用"));
    }
    
    @Override
    public void onDisable() {
        getLogger().info("LengMOTD 插件已禁用!");
    }
    
    private void loadConfiguration() {
        reloadConfig();
        
        motdEntries = new ArrayList<>();
        if (getConfig().contains("motd")) {
            ConfigurationSection motdSection = getConfig().getConfigurationSection("motd");
            if (motdSection != null) {
                for (String key : motdSection.getKeys(false)) {
                    ConfigurationSection entrySection = motdSection.getConfigurationSection(key);
                    if (entrySection != null) {
                        String motd1 = entrySection.getString("motd1", "");
                        String motd2 = entrySection.getString("motd2", "");
                        if (!motd1.isEmpty() && !motd2.isEmpty()) {
                            motdEntries.add(new MotdEntry(motd1, motd2));
                        }
                    }
                }
            }
        }
        
        if (motdEntries.isEmpty()) {
            createDefaultMotdConfig();
            loadConfiguration();
        }
        
        isCycling = getConfig().getBoolean("settings.cycling", true);
        cycleDelay = getConfig().getInt("settings.cycle-delay-ticks", 20);
        whitelistMode = getConfig().getBoolean("settings.whitelist-mode", false);
        maintenanceKickMessage = ChatColor.translateAlternateColorCodes('&', 
            getConfig().getString("settings.maintenance-kick-message", "&c服务器正在维护中"));
        useCustomPlayerList = getConfig().getBoolean("settings.use-custom-player-list", false);
        customPlayerList = getConfig().getString("settings.custom-player-list", "CFC Powered by UKiyograin");
        maxPlayers = getConfig().getInt("settings.max-players", 100);
        
        // 加载玩家悬停文本
        playerHoverTexts.clear();
        if (getConfig().contains("settings.player-hover-texts")) {
            playerHoverTexts = getConfig().getStringList("settings.player-hover-texts");
        }
        if (playerHoverTexts.isEmpty()) {
            playerHoverTexts = Arrays.asList(
                "&a欢迎来到服务器!",
                "&bPowered by UKiyograin",
                "&eCFC 服务器"
            );
        }
        
    // 加载一言配置
    useHitokoto = getConfig().getBoolean("hitokoto.enabled", false);
    hitokotoCacheTime = getConfig().getLong("hitokoto.cache-time", 30000L);
    hitokotoApiUrl = getConfig().getString("hitokoto.api-url", "https://v1.hitokoto.cn/");
    hitokotoCategory = getConfig().getString("hitokoto.category", "");
    hitokotoColor = getConfig().getString("hitokoto.color", "&8");
    
    // 验证颜色格式
    if (!hitokotoColor.startsWith("&") || hitokotoColor.length() != 2) {
        getLogger().warning("一言颜色格式无效: " + hitokotoColor + "，将使用默认颜色 &8");
        hitokotoColor = "&8";
    }
        
            // 验证分类是否有效
    if (!hitokotoCategory.isEmpty()) {
        Set<String> validCategories = new HashSet<>(Arrays.asList(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"
        ));
        if (!validCategories.contains(hitokotoCategory)) {
            getLogger().warning("无效的一言分类: " + hitokotoCategory + "，将使用随机分类");
            getLogger().warning("有效分类: a(动画), b(漫画), c(游戏), d(文学), e(原创), f(来自网络), g(其他), h(影视), i(诗词), j(网易云), k(哲学), l(抖机灵)");
            hitokotoCategory = "";
        }
    }
        // 加载一言备用文本
        hitokotoFallbackTexts.clear();
        if (getConfig().contains("hitokoto.fallback-texts")) {
            hitokotoFallbackTexts = getConfig().getStringList("hitokoto.fallback-texts");
        }
        if (hitokotoFallbackTexts.isEmpty()) {
            hitokotoFallbackTexts = Arrays.asList(
                "人生如逆旅，我亦是行人",
                "山重水复疑无路，柳暗花明又一村",
                "愿我如星君如月，夜夜流光相皎洁",
                "长风破浪会有时，直挂云帆济沧海",
                "采菊东篱下，悠然见南山"
            );
        }
        
        saveConfig();
    }
    
    private void createDefaultMotdConfig() {
        ConfigurationSection motdSection = getConfig().createSection("motd");
        
        ConfigurationSection yiSection = motdSection.createSection("yi");
        yiSection.set("motd1", "&3 >> ColorFulCraft &6Beta 4.0.1   &d(*^▽^*) 584937263");
        yiSection.set("motd2", " &8 正见空江明月来，云水苍茫失江路  ");
        
        ConfigurationSection erSection = motdSection.createSection("er");
        erSection.set("motd1", "&3 >> ColorFulCraft &6Beta 4.0.1   &d(｀ﾍ´)=3 584937263");
        erSection.set("motd2", " &8 有情芍药含春泪，无力蔷薇卧晓枝 ");
        
        ConfigurationSection sanSection = motdSection.createSection("san");
        sanSection.set("motd1", "&3 >> ColorFulCraft &6Beta 4.0.1   &d(*╹▽╹*) 584937263");
        sanSection.set("motd2", " &8 今宵绝胜无人共，卧看星河尽意明 ");
        
        // 添加一言配置
        ConfigurationSection hitokotoSection = getConfig().createSection("hitokoto");
        hitokotoSection.set("enabled", false);
        hitokotoSection.set("cache-time", 30000);
        hitokotoSection.set("api-url", "https://v1.hitokoto.cn/");
        hitokotoSection.set("color", "&8"); 
        hitokotoSection.set("category", ""); // 可选值: a(动画), b(漫画), c(游戏), d(文学), e(原创), f(来自网络), g(其他), h(影视), i(诗词), j(网易云), k(哲学), l(抖机灵)
        
List<String> fallbackTexts = Arrays.asList(
    "> 人生如逆旅，我亦是行人",
    "> 山重水复疑无路，柳暗花明又一村",
    "> 愿我如星君如月，夜夜流光相皎洁",
    "> 长风破浪会有时，直挂云帆济沧海",
    "> 采菊东篱下，悠然见南山"
);
hitokotoSection.set("fallback-texts", fallbackTexts);
        
        saveConfig();
    }
    
    private void loadServerIcons() {
        serverIcons = new ArrayList<>();
        File iconFolder = new File(getDataFolder(), "icons");
        
        if (!iconFolder.exists()) {
            iconFolder.mkdirs();
            return;
        }
        
        File[] iconFiles = iconFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (iconFiles != null) {
            for (File iconFile : iconFiles) {
                try {
                    CachedServerIcon icon = Bukkit.loadServerIcon(iconFile);
                    serverIcons.add(icon);
                } catch (Exception e) {
                    getLogger().warning("无法加载图标 " + iconFile.getName() + ": " + e.getMessage());
                }
            }
        }
    }
    
    private void startMotdCycleTask() {
        if (!isCycling || motdEntries.size() <= 1) {
            return;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                currentMotdIndex = (currentMotdIndex + 1) % motdEntries.size();
            }
        }.runTaskTimer(this, 0L, cycleDelay);
    }
    
    private void setupCommands() {
        getCommand("lengmotd").setExecutor((sender, command, label, args) -> {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }
            
            if (!sender.hasPermission("lengmotd.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用此命令!");
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "reload":
                    loadConfiguration();
                    reloadIcons();
                    sender.sendMessage(ChatColor.GREEN + "LengMOTD 配置已重载!");
                    break;
                    
                case "wh":
                    if (args.length == 1) {
                        whitelistMode = !whitelistMode;
                        getConfig().set("settings.whitelist-mode", whitelistMode);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "维护模式已" + (whitelistMode ? "启用" : "禁用") + "!");
                    } else {
                        String state = args[1].toLowerCase();
                        if (state.equals("on")) {
                            whitelistMode = true;
                        } else if (state.equals("off")) {
                            whitelistMode = false;
                        } else {
                            sender.sendMessage(ChatColor.RED + "用法: /lengmotd wh [on|off]");
                            return true;
                        }
                        getConfig().set("settings.whitelist-mode", whitelistMode);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "维护模式已" + (whitelistMode ? "启用" : "禁用") + "!");
                    }
                    
                    if (whitelistMode) {
                        Bukkit.broadcastMessage(ChatColor.RED + "[LengMOTD] 服务器已进入维护模式!");
                    }
                    break;
                    
                case "playerlist":
                    if (args.length == 1) {
                        useCustomPlayerList = !useCustomPlayerList;
                        getConfig().set("settings.use-custom-player-list", useCustomPlayerList);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "自定义玩家列表已" + (useCustomPlayerList ? "启用" : "禁用") + "!");
                    } else {
                        String state = args[1].toLowerCase();
                        if (state.equals("on")) {
                            useCustomPlayerList = true;
                        } else if (state.equals("off")) {
                            useCustomPlayerList = false;
                        } else {
                            sender.sendMessage(ChatColor.RED + "用法: /lengmotd playerlist [on|off|set]");
                            return true;
                        }
                        getConfig().set("settings.use-custom-player-list", useCustomPlayerList);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "自定义玩家列表已" + (useCustomPlayerList ? "启用" : "禁用") + "!");
                    }
                    break;
                    
                case "setplayerlist":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /lengmotd setplayerlist <文本>");
                        return true;
                    }
                    String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    customPlayerList = text;
                    getConfig().set("settings.custom-player-list", text);
                    saveConfig();
                    sender.sendMessage(ChatColor.GREEN + "自定义玩家列表已设置为: " + text);
                    break;
                    
                case "setmaxplayers":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /lengmotd setmaxplayers <数量>");
                        return true;
                    }
                    try {
                        int newMax = Integer.parseInt(args[1]);
                        if (newMax < 0 || newMax > 999999) {
                            sender.sendMessage(ChatColor.RED + "最大玩家数必须在 0-999999 之间!");
                            return true;
                        }
                        maxPlayers = newMax;
                        getConfig().set("settings.max-players", maxPlayers);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "最大玩家数已设置为: " + maxPlayers);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "请输入有效的数字!");
                    }
                    break;
                    
                case "list":
                    sender.sendMessage(ChatColor.YELLOW + "===== MOTD 预设列表 =====");
                    for (int i = 0; i < motdEntries.size(); i++) {
                        MotdEntry entry = motdEntries.get(i);
                        sender.sendMessage(ChatColor.GOLD + "预设 #" + (i + 1) + ":");
                        sender.sendMessage("  " + ChatColor.translateAlternateColorCodes('&', entry.motd1));
                        sender.sendMessage("  " + ChatColor.translateAlternateColorCodes('&', entry.motd2));
                    }
                    break;
                    
                case "hitokoto":
                    if (args.length == 1) {
                        useHitokoto = !useHitokoto;
                        getConfig().set("hitokoto.enabled", useHitokoto);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "一言功能已" + (useHitokoto ? "启用" : "禁用") + "!");
                        if (useHitokoto) {
                            fetchHitokotoAsync();
                        }
                    } else {
                        String state = args[1].toLowerCase();
                        if (state.equals("on")) {
                            useHitokoto = true;
                        } else if (state.equals("off")) {
                            useHitokoto = false;
                        } else if (state.equals("refresh")) {
                            fetchHitokotoAsync();
                            sender.sendMessage(ChatColor.GREEN + "正在刷新一言内容...");
                            return true;
                        } else {
                            sender.sendMessage(ChatColor.RED + "用法: /lengmotd hitokoto [on|off|refresh]");
                            return true;
                        }
                        getConfig().set("hitokoto.enabled", useHitokoto);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "一言功能已" + (useHitokoto ? "启用" : "禁用") + "!");
                        if (useHitokoto) {
                            fetchHitokotoAsync();
                        }
                    }
                    break;
                    
                case "hitokotoinfo":
                    sender.sendMessage(ChatColor.YELLOW + "===== 一言信息 =====");
                    sender.sendMessage(ChatColor.GOLD + "状态: " + (useHitokoto ? ChatColor.GREEN + "启用" : ChatColor.RED + "禁用"));
                    sender.sendMessage(ChatColor.GOLD + "缓存内容: " + ChatColor.WHITE + hitokotoCache);
                    sender.sendMessage(ChatColor.GOLD + "上次获取时间: " + ChatColor.WHITE + 
                        new java.util.Date(lastHitokotoFetchTime).toString());
                    sender.sendMessage(ChatColor.GOLD + "缓存时间: " + ChatColor.WHITE + hitokotoCacheTime + "ms");
                    sender.sendMessage(ChatColor.GOLD + "API地址: " + ChatColor.WHITE + hitokotoApiUrl);
                    sender.sendMessage(ChatColor.GOLD + "分类: " + ChatColor.WHITE + 
                        (hitokotoCategory.isEmpty() ? "随机" : hitokotoCategory));
                    break;
                    
                default:
                    sendHelp(sender);
                    break;
            }
            return true;
        });
        
        getCommand("lengmotd").setTabCompleter((sender, command, alias, args) -> {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                String[] commands = {"reload", "wh", "playerlist", "setplayerlist", "setmaxplayers", "list", "hitokoto", "hitokotoinfo", "help"};
                for (String cmd : commands) {
                    if (cmd.startsWith(args[0].toLowerCase())) {
                        completions.add(cmd);
                    }
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("wh") || args[0].equalsIgnoreCase("playerlist")) {
                    String[] states = {"on", "off"};
                    for (String state : states) {
                        if (state.startsWith(args[1].toLowerCase())) {
                            completions.add(state);
                        }
                    }
                } else if (args[0].equalsIgnoreCase("hitokoto")) {
                    String[] states = {"on", "off", "refresh"};
                    for (String state : states) {
                        if (state.startsWith(args[1].toLowerCase())) {
                            completions.add(state);
                        }
                    }
                }
            }
            return completions;
        });
    }
    
    private void sendHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "===== LengMOTD 帮助 =====");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd reload" + ChatColor.GRAY + " - 重载配置");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd wh [on|off]" + ChatColor.GRAY + " - 开关维护模式");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd playerlist [on|off]" + ChatColor.GRAY + " - 开关自定义玩家列表");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd setplayerlist <文本>" + ChatColor.GRAY + " - 设置玩家列表文本");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd setmaxplayers <数量>" + ChatColor.GRAY + " - 设置最大玩家数");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd list" + ChatColor.GRAY + " - 显示MOTD预设");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd hitokoto [on|off|refresh]" + ChatColor.GRAY + " - 开关一言功能");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd hitokotoinfo" + ChatColor.GRAY + " - 显示一言信息");
        sender.sendMessage(ChatColor.GOLD + "/lengmotd help" + ChatColor.GRAY + " - 显示帮助");
    }
    
    @EventHandler
    public void onServerListPing(ServerListPingEvent event) {
        // 设置最大玩家数
        if (whitelistMode) {
            event.setMaxPlayers(0); // 维护模式显示0/0
        } else {
            event.setMaxPlayers(maxPlayers);
        }
        
        // 设置 MOTD
        if (!motdEntries.isEmpty()) {
            MotdEntry selectedEntry;
            if (isCycling && motdEntries.size() > 1) {
                selectedEntry = motdEntries.get(currentMotdIndex);
            } else {
                selectedEntry = motdEntries.get(ThreadLocalRandom.current().nextInt(motdEntries.size()));
            }
            
            String finalMotd;
            if (whitelistMode) {
                String line1 = "&c&l[维护中]&r " + selectedEntry.motd1;
                String line2 = selectedEntry.motd2;
                finalMotd = ChatColor.translateAlternateColorCodes('&', line1) + "\n" + 
                            ChatColor.translateAlternateColorCodes('&', line2);
            } else {
    // 如果启用了一言功能，使用一言作为第二行
    String line1 = ChatColor.translateAlternateColorCodes('&', selectedEntry.motd1);
    String line2;
    
    if (useHitokoto) {
        // 检查缓存是否过期
        if (System.currentTimeMillis() - lastHitokotoFetchTime > hitokotoCacheTime) {
            // 异步更新一言，并立即使用"更新中"提示
            fetchHitokotoAsync();
            line2 = ChatColor.translateAlternateColorCodes('&', hitokotoColor + "> 正在获取一言... ");
        } else {
            // 使用缓存的一言，如果为空则使用原第二行
            if (hitokotoCache != null && !hitokotoCache.isEmpty()) {
                // 在一言前面加上 > 符号
                line2 = ChatColor.translateAlternateColorCodes('&', hitokotoColor + "> " + hitokotoCache + " ");
            } else {
                line2 = ChatColor.translateAlternateColorCodes('&', selectedEntry.motd2);
            }
        }
    } else {
        line2 = ChatColor.translateAlternateColorCodes('&', selectedEntry.motd2);
    }
    
    finalMotd = line1 + "\n" + line2;
            }
            
            // 如果启用了自定义玩家列表，将文本添加到MOTD中
            if (useCustomPlayerList && !whitelistMode) {
                String playerListText = ChatColor.translateAlternateColorCodes('&', customPlayerList);
                // 在MOTD下方添加玩家列表文本
                finalMotd = finalMotd + "\n" + ChatColor.GRAY + playerListText;
            }
            
            event.setMotd(finalMotd);
        }
        
        // 设置服务器图标
        if (!serverIcons.isEmpty()) {
            CachedServerIcon icon = serverIcons.get(ThreadLocalRandom.current().nextInt(serverIcons.size()));
            try {
                event.setServerIcon(icon);
            } catch (Exception e) {
                // 忽略错误
            }
        }
        
        // 尝试设置玩家悬停文本（使用反射，因为不同版本API不同）
        try {
            if (useCustomPlayerList && !whitelistMode && !playerHoverTexts.isEmpty()) {
                setPlayerHoverText(event);
            }
        } catch (Exception e) {
            // 反射失败，使用备用方案
        }
    }
    
    /**
     * 异步获取一言内容
     */
    private void fetchHitokotoAsync() {
        if (!useHitokoto) return;
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = hitokotoApiUrl;
                    if (!hitokotoCategory.isEmpty()) {
                        apiUrl += "?c=" + hitokotoCategory;
                    }
                    
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "LengMOTD/" + getDescription().getVersion());
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        
                        // 解析JSON响应
                        String json = response.toString();
                        // 简单解析JSON获取hitokoto字段
                        int startIndex = json.indexOf("\"hitokoto\":\"");
                        if (startIndex != -1) {
                            startIndex += "\"hitokoto\":\"".length();
                            int endIndex = json.indexOf("\"", startIndex);
                            if (endIndex != -1) {
                                String hitokoto = json.substring(startIndex, endIndex);
                                // 转义Unicode字符
                                hitokoto = unescapeUnicode(hitokoto);
                                hitokotoCache = hitokoto;
                                lastHitokotoFetchTime = System.currentTimeMillis();
                                
                                return;
                            }
                        }
                    }
                    
                    // 如果API调用失败，使用备用文本
                    getLogger().warning("一言API调用失败，使用备用文本");
                    hitokotoCache = hitokotoFallbackTexts.get(
                        ThreadLocalRandom.current().nextInt(hitokotoFallbackTexts.size()));
                    lastHitokotoFetchTime = System.currentTimeMillis();
                    
                } catch (Exception e) {
                    getLogger().warning("获取一言失败: " + e.getMessage());
                    // 使用备用文本
                    hitokotoCache = hitokotoFallbackTexts.get(
                        ThreadLocalRandom.current().nextInt(hitokotoFallbackTexts.size()));
                    lastHitokotoFetchTime = System.currentTimeMillis();
                }
            }
        }.runTaskAsynchronously(this);
    }
    
    /**
     * 转换Unicode转义序列
     */
    private String unescapeUnicode(String str) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == '\\' && i + 1 < str.length() && str.charAt(i + 1) == 'u') {
                if (i + 5 < str.length()) {
                    String hex = str.substring(i + 2, i + 6);
                    try {
                        int codePoint = Integer.parseInt(hex, 16);
                        sb.append((char) codePoint);
                        i += 6;
                    } catch (NumberFormatException e) {
                        sb.append(c);
                        i++;
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
    
    private void setPlayerHoverText(ServerListPingEvent event) {
        try {
            // 尝试Paper/Spigot 1.13+ 的方法
            Class<?> eventClass = event.getClass();
            
            // 尝试获取玩家样本方法
            Method setSampleTextMethod = null;
            for (Method method : eventClass.getMethods()) {
                if (method.getName().equals("setSampleText") || 
                    method.getName().equals("setPlayerSample")) {
                    setSampleTextMethod = method;
                    break;
                }
            }
            
            if (setSampleTextMethod != null) {
                // 创建玩家样本数组
                String sampleText = playerHoverTexts.get(ThreadLocalRandom.current().nextInt(playerHoverTexts.size()));
                sampleText = ChatColor.translateAlternateColorCodes('&', sampleText);
                
                // 根据方法参数类型设置
                Class<?> paramType = setSampleTextMethod.getParameterTypes()[0];
                if (paramType.isArray() && paramType.getComponentType().equals(String.class)) {
                    // String[] 类型
                    String[] samples = {sampleText};
                    setSampleTextMethod.invoke(event, (Object) samples);
                } else if (paramType.equals(String.class)) {
                    // String 类型（部分版本）
                    setSampleTextMethod.invoke(event, sampleText);
                } else if (List.class.isAssignableFrom(paramType)) {
                    // List<String> 类型
                    List<String> samples = Collections.singletonList(sampleText);
                    setSampleTextMethod.invoke(event, samples);
                }
            }
        } catch (Exception e) {
            // 反射失败，使用备用方案：在MOTD中添加
            String currentMotd = event.getMotd();
            String hoverText = playerHoverTexts.get(ThreadLocalRandom.current().nextInt(playerHoverTexts.size()));
            hoverText = ChatColor.translateAlternateColorCodes('&', hoverText);
            event.setMotd(currentMotd + "\n" + ChatColor.ITALIC + hoverText);
        }
    }
    
    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (whitelistMode) {
            Player player = event.getPlayer();
            if (player.isOp() || player.hasPermission("lengmotd.whitelist.bypass")) {
                event.allow();
                return;
            }
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, maintenanceKickMessage);
        }
    }
    
    public void reloadIcons() {
        serverIcons.clear();
        loadServerIcons();
    }
}