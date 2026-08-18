package com.fabian.xclearlag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.java.JavaPlugin;
import com.fabian.xclearlag.commands.*;
import com.fabian.xclearlag.managers.*;
import com.fabian.xclearlag.managers.DependencyManager;

import com.fabian.xclearlag.utils.*;
import com.fabian.xclearlag.metrics.Metrics;
import com.fabian.xclearlag.hooks.XPlaceholderExpansion;
import com.fabian.xclearlag.utils.scheduler.*;
import com.fabian.xclearlag.utils.DebugLogger;
import com.fabian.xclearlag.api.XClearlagAPI;

/**
 * Main plugin class for X-Clearlag.
 * Refactored for modularity, custom events, and elite-level API.
 */
public class XClearlag extends JavaPlugin {

    private static XClearlag instance;

    public static XClearlag getInstance() {
        return instance;
    }

    public void logInfo(String message) {
        getLogger().info(message);
    }

    public void logWarning(String message) {
        getLogger().warning(message);
    }

    public void logError(String message) {
        getLogger().severe(message);
    }

    private ConfigManager configManager;
    private LanguageManager languageManager;
    private TaskManager taskManager;
    private UpdateChecker updateChecker;
    private TPSMonitor tpsMonitor;
    private Object tpsMonitorTask;
    private TpsCleanupService tpsCleanupService;
    private SchedulerAdapter schedulerAdapter;
    
    private Metrics metricsTracker;
    private CommandDispatcher commandDispatcher;
    private ClearExecutor clearExecutor;
    private BossBarManager bossBarManager;
    private CleanupNotifier cleanupNotifier;

    @Override
    public void onEnable() {
        instance = this;

        try {
            // Initialize config managers first
            this.configManager = new ConfigManager(this);
            configManager.load();
            DebugLogger.debug("Config", "ConfigManager initialized");
        } catch (Exception e) {
            DebugLogger.debug("Config", "Failed to initialize config managers", e);
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Load libraries before anything else
        DebugLogger.debug("Dependency", "Initializing DependencyManager...");
        new DependencyManager(this).loadDependencies();

        // Initialize remaining managers
        try {
            DebugLogger.debug("Init", "Instance set, initializing API...");
            XClearlagAPI.init(this);

            DebugLogger.debug("Init", "Initializing scheduler...");
            initScheduler();

            DebugLogger.debug("Init", "Initializing LanguageManager...");
            languageManager = new LanguageManager(this);
            languageManager.load();
            DebugLogger.debug("Init", "LanguageManager initialized");

            DebugLogger.debug("Init", "Initializing TaskManager...");
            taskManager = new TaskManager(this);

            DebugLogger.debug("Init", "Initializing services...");
            initServices();

            DebugLogger.debug("Init", "Initializing commands...");
            initCommands();

            // PlaceholderAPI Integration
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                new XPlaceholderExpansion(this).register();
                DebugLogger.debug("PAPI", "PlaceholderAPI expansion registered");
            } else {
                DebugLogger.debug("PAPI", "PlaceholderAPI not found, skipping expansion");
            }

        } catch (Exception e) {
            DebugLogger.debug("Init", "Failed to initialize managers", e);
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Check for updates
        if (configManager.get().general.checkUpdates) {
            DebugLogger.debug("Update", "Update checker enabled, scheduling check");
            this.updateChecker = new UpdateChecker(this);
            schedulerAdapter.runTaskLater(() -> updateChecker.checkForUpdates(), 100L);
        }

        // Hide own namespaced commands from tab-completion (1.13+)
        try {
            Class<?> eventClass = Class.forName("org.bukkit.event.player.PlayerCommandSendEvent");
            org.bukkit.event.HandlerList handlers = (org.bukkit.event.HandlerList) eventClass
                    .getMethod("getHandlerList").invoke(null);
            com.fabian.xclearlag.listeners.CommandHideListener listener = new com.fabian.xclearlag.listeners.CommandHideListener();
            handlers.register(new org.bukkit.plugin.RegisteredListener(listener, (l, event) -> {
                if (eventClass.isInstance(event)) {
                    listener.onCommandSend(event);
                }
            }, org.bukkit.event.EventPriority.NORMAL, this, false));
            DebugLogger.debug("Init", "CommandHideListener registered (reflection)");
        } catch (Exception ignored) {}

        // Register update notification listener
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                org.bukkit.entity.Player player = event.getPlayer();
                if (!player.isOp() && !player.hasPermission("xclearlag.admin")) return;
                if (!getConfig().getBoolean("updates.notify-on-join", true)) return;
                if (updateChecker == null) return;
                if (updateChecker.isUpdateAvailable()) {
                    DebugLogger.debug("UpdateListener", "Notifying admin " + player.getName() + " about update");
                    String current = getDescription().getVersion();
                    String latest = updateChecker.getLatestVersion();
                    player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            "&8[&bX-Clearlag&8] &eA new version is available: &a" + latest + " &e(current: &c" + current + "&e)"));
                    player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            "&8[&bX-Clearlag&8] &7Download it at: &f" + updateChecker.getDownloadUrl()));
                }
            }
        }, this);

        // Initialize bStats Metrics
        setupMetrics();

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8] &7----------------------------------------------"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8]   &aEnabled v" + getDescription().getVersion() + "! Lag is now managed."));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8]   &7Language: &f" + configManager.get().general.language.toUpperCase()));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8] &7----------------------------------------------"));
    }

    private void initServices() {
        DebugLogger.debug("Services", "Creating TPSMonitor...");
        tpsMonitor = new TPSMonitor();
        tpsMonitorTask = schedulerAdapter.runTaskTimer(tpsMonitor, 1L, 1L);
        DebugLogger.debug("Services", "TPSMonitor scheduled (every tick).");
        
        commandDispatcher = new CommandDispatcher(this);
        bossBarManager = new BossBarManager(configManager);
        cleanupNotifier = new CleanupNotifier(languageManager, bossBarManager, getLogger());
        
        // 2. Functional Services
        metricsTracker = new Metrics(tpsMonitor);
        clearExecutor = new ClearExecutor(this, configManager);
        DebugLogger.debug("Services", "ClearExecutor and Metrics created.");

        // 3. Lifecycle Managers
        DebugLogger.debug("Services", "Loading tasks...");
        taskManager.loadTasks();
        
        tpsCleanupService = new TpsCleanupService(this, configManager, tpsMonitor, taskManager, schedulerAdapter);
        tpsCleanupService.start();
        DebugLogger.debug("Services", "TpsCleanupService started.");

    }

    private void initCommands() {
        DebugLogger.debug("Command", "Registering commands...");
        XClearlagCommand commandHandler = new XClearlagCommand(this);
        org.bukkit.command.PluginCommand xclCmd = getCommand("xcl");
        if (xclCmd != null) {
            xclCmd.setExecutor(commandHandler);
            xclCmd.setTabCompleter(commandHandler);
        }
    }

    @Override
    public void onDisable() {
        DebugLogger.debug("Init", "Plugin disabling...");
        if (taskManager != null) { taskManager.stopAll(); }
        if (tpsCleanupService != null) { tpsCleanupService.stop(); }
        if (tpsMonitorTask != null) { schedulerAdapter.cancelTask(tpsMonitorTask); }
        if (bossBarManager != null) { bossBarManager.hide(); }

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8] &7----------------------------------------------"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8]   &cDisabled v" + getDescription().getVersion() + "! Out."));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&bX-Clearlag&8] &7----------------------------------------------"));
    }

    private void setupMetrics() {
        if (configManager.get().general.metrics) {
            try {
                new org.bstats.bukkit.Metrics(this, 31665);
            } catch (Exception e) {
                logWarning("Could not start bStats Metrics: " + e.getMessage());
            }
        }
    }

    public void reload() {
        try {
            DebugLogger.debug("Reload", "Reloading X-Clearlag...");
            reloadConfig();
            configManager.load();
            languageManager.load();
            metricsTracker.reset();
            taskManager.loadTasks();
            if (tpsCleanupService != null) {
                tpsCleanupService.stop();
                tpsCleanupService.start();
            }
            DebugLogger.debug("Reload", "Reload complete.");
            logInfo("&aX-Clearlag reloaded successfully.");
        } catch (Exception e) {
            logError("Failed to reload plugin: " + e.getMessage());
        }
    }

    private void initScheduler() {
        DebugLogger.debug("Scheduler", "Detecting server type...");
        boolean isFolia = false;
        try {
            // Method 1: Check for getGlobalRegionScheduler on server (standard Folia/Canvas)
            try {
                Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
                isFolia = true;
                DebugLogger.debug("Scheduler", "Folia detected via getGlobalRegionScheduler method.");
            } catch (NoSuchMethodException ignored) {
                // Method 2: Check for getGlobalRegionScheduler as static Bukkit method
                try {
                    Bukkit.class.getMethod("getGlobalRegionScheduler");
                    isFolia = true;
                    DebugLogger.debug("Scheduler", "Folia detected via static Bukkit.getGlobalRegionScheduler method.");
                } catch (NoSuchMethodException ignored2) {
                    // Method 3: Check for RegionizedServer class (legacy detection)
                    try {
                        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                        isFolia = true;
                        DebugLogger.debug("Scheduler", "Folia detected via RegionizedServer class.");
                    } catch (ClassNotFoundException ignored3) {
                        // Method 4: Check for the Folia ScheduledTask class
                        try {
                            Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
                            isFolia = true;
                            DebugLogger.debug("Scheduler", "Folia detected via ScheduledTask class.");
                        } catch (ClassNotFoundException ignored4) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (isFolia) {
            schedulerAdapter = new FoliaSchedulerAdapter(this);
            if (!((FoliaSchedulerAdapter) schedulerAdapter).isInitialized()) {
                logWarning("Folia scheduler adapter failed to initialize! The plugin will not function correctly.");
                logWarning("Your server fork (" + Bukkit.getServer().getName() + " " + Bukkit.getServer().getVersion() + ") may not be fully Folia-compatible.");
            }
            logInfo("&bFolia/Canvas &fdetected&a! Using &fregional scheduler adapter&a.");
            DebugLogger.debug("Scheduler", "Using FoliaSchedulerAdapter.");
        } else {
            schedulerAdapter = new BukkitSchedulerAdapter(this);
            logInfo("&fStandard Bukkit/Paper &fdetected&7! Using &fstandard scheduler adapter&7.");
            DebugLogger.debug("Scheduler", "Standard Bukkit/Paper detected, using BukkitSchedulerAdapter.");
        }
    }

    public SchedulerAdapter getSchedulerAdapter() { return schedulerAdapter; }
    public ConfigManager getConfigManager() { return configManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public TaskManager getTaskManager() { return taskManager; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public TPSMonitor getTpsMonitor() { return tpsMonitor; }
    public Metrics getMetricsTracker() { return metricsTracker; }
    public CommandDispatcher getCommandDispatcher() { return commandDispatcher; }
    public ClearExecutor getClearExecutor() { return clearExecutor; }
    public BossBarManager getBossBarManager() { return bossBarManager; }
    public CleanupNotifier getCleanupNotifier() { return cleanupNotifier; }
}
