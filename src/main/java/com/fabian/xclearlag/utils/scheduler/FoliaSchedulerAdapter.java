package com.fabian.xclearlag.utils.scheduler;

import com.fabian.xclearlag.XClearlag;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import com.fabian.xclearlag.utils.DebugLogger;

/**
 * Folia-specific implementation of the SchedulerAdapter using Reflection.
 * This allows the plugin to compile against legacy Spigot APIs (like 1.8.8)
 * while still functioning correctly on Folia/Canvas servers at runtime.
 *
 * Supports both standard Folia (Consumer&lt;ScheduledTask&gt;) and Canvas/other forks
 * that may use Runnable-based method signatures.
 */
public class FoliaSchedulerAdapter implements SchedulerAdapter {

    private final XClearlag plugin;
    private Object globalScheduler;
    private Object asyncScheduler;

    private Method runMethod;
    private Method runDelayedMethod;
    private Method runAtFixedRateMethod;
    private Method runNowAsyncMethod;
    private boolean initialized = false;

    public FoliaSchedulerAdapter(XClearlag plugin) {
        this.plugin = plugin;
        try {
            Class<?> serverClass = Bukkit.getServer().getClass();

            // Get Global Scheduler — try both instance method and static Bukkit method
            try {
                Method getGlobalMethod = serverClass.getMethod("getGlobalRegionScheduler");
                this.globalScheduler = getGlobalMethod.invoke(Bukkit.getServer());
            } catch (NoSuchMethodException e1) {
                // Canvas or other forks may expose it differently
                try {
                    Method getGlobalMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");
                    this.globalScheduler = getGlobalMethod.invoke(null);
                } catch (Exception e2) {
                    throw new RuntimeException("Cannot find getGlobalRegionScheduler on server or Bukkit class", e2);
                }
            }

            // Get Async Scheduler — try both instance method and static Bukkit method
            try {
                Method getAsyncMethod = serverClass.getMethod("getAsyncScheduler");
                this.asyncScheduler = getAsyncMethod.invoke(Bukkit.getServer());
            } catch (NoSuchMethodException e1) {
                try {
                    Method getAsyncMethod = Bukkit.class.getMethod("getAsyncScheduler");
                    this.asyncScheduler = getAsyncMethod.invoke(null);
                } catch (Exception e2) {
                    throw new RuntimeException("Cannot find getAsyncScheduler on server or Bukkit class", e2);
                }
            }

            Class<?> globalClass = globalScheduler.getClass();
            Class<?> asyncClass = asyncScheduler.getClass();

            // Try Consumer<ScheduledTask> signatures first (standard Folia API),
            // then fall back to Runnable signatures (Canvas / other forks)

            this.runMethod = findMethod(globalClass, "run", Plugin.class, Consumer.class);
            if (this.runMethod == null) {
                this.runMethod = findMethod(globalClass, "run", Plugin.class, Runnable.class);
                DebugLogger.debug("Scheduler", "Using Runnable-based run() (Canvas/fork compat).");
            }

            this.runDelayedMethod = findMethod(globalClass, "runDelayed", Plugin.class, Consumer.class, long.class);
            if (this.runDelayedMethod == null) {
                this.runDelayedMethod = findMethod(globalClass, "runDelayed", Plugin.class, Runnable.class, long.class);
                DebugLogger.debug("Scheduler", "Using Runnable-based runDelayed() (Canvas/fork compat).");
            }

            this.runAtFixedRateMethod = findMethod(globalClass, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            if (this.runAtFixedRateMethod == null) {
                this.runAtFixedRateMethod = findMethod(globalClass, "runAtFixedRate", Plugin.class, Runnable.class, long.class, long.class);
                DebugLogger.debug("Scheduler", "Using Runnable-based runAtFixedRate() (Canvas/fork compat).");
            }

            this.runNowAsyncMethod = findMethod(asyncClass, "runNow", Plugin.class, Consumer.class);
            if (this.runNowAsyncMethod == null) {
                this.runNowAsyncMethod = findMethod(asyncClass, "runNow", Plugin.class, Runnable.class);
                DebugLogger.debug("Scheduler", "Using Runnable-based async runNow() (Canvas/fork compat).");
            }

            // Validate all critical methods were found
            if (this.runMethod == null) throw new RuntimeException("GlobalRegionScheduler.run() not found");
            if (this.runDelayedMethod == null) throw new RuntimeException("GlobalRegionScheduler.runDelayed() not found");
            if (this.runAtFixedRateMethod == null) throw new RuntimeException("GlobalRegionScheduler.runAtFixedRate() not found");
            if (this.runNowAsyncMethod == null) throw new RuntimeException("AsyncScheduler.runNow() not found");

            this.initialized = true;
            DebugLogger.debug("Scheduler", "FoliaSchedulerAdapter fully initialized.");

        } catch (Exception e) {
            this.initialized = false;
            plugin.logWarning("Failed to initialize Folia Scheduler Adapter: " + e.getMessage());
            DebugLogger.debug("Scheduler", "FoliaSchedulerAdapter reflection init failed.", e);
            e.printStackTrace();
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Object wrapRunnable(Runnable runnable) {
        // If the method expects Consumer, wrap the Runnable as a Consumer<ScheduledTask>
        // If it expects Runnable, pass the Runnable directly
        if (runMethod != null && runMethod.getParameterTypes().length == 2
                && runMethod.getParameterTypes()[1] == Consumer.class) {
            return (Consumer<Object>) t -> runnable.run();
        }
        return runnable;
    }

    @Override
    public void runTask(Runnable runnable) {
        if (!initialized) return;
        try {
            runMethod.invoke(globalScheduler, plugin, wrapRunnable(runnable));
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "runTask failed", e);
        }
    }

    @Override
    public void runTaskLater(Runnable runnable, long delay) {
        if (!initialized) return;
        try {
            runDelayedMethod.invoke(globalScheduler, plugin, wrapRunnable(runnable), delay);
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "runTaskLater failed", e);
        }
    }

    @Override
    public Object runTaskTimer(Runnable runnable, long delay, long period) {
        if (!initialized) return null;
        try {
            return runAtFixedRateMethod.invoke(globalScheduler, plugin, wrapRunnable(runnable), delay, period);
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "runTaskTimer failed", e);
            return null;
        }
    }

    @Override
    public void runTaskAsync(Runnable runnable) {
        if (!initialized) return;
        try {
            runNowAsyncMethod.invoke(asyncScheduler, plugin, wrapRunnable(runnable));
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "runTaskAsync failed", e);
        }
    }

    @Override
    public void runTaskOnEntity(org.bukkit.entity.Entity entity, Runnable runnable) {
        if (!initialized) return;
        try {
            // Get the EntityScheduler via reflection
            Method getSchedulerMethod = entity.getClass().getMethod("getScheduler");
            Object entityScheduler = getSchedulerMethod.invoke(entity);

            // Try Consumer<ScheduledTask> signature first, then Runnable
            Method entityRunMethod = findMethod(entityScheduler.getClass(), "run", Plugin.class, Consumer.class, Runnable.class);
            if (entityRunMethod != null) {
                entityRunMethod.invoke(entityScheduler, plugin, (Consumer<Object>) t -> runnable.run(), (Runnable) null);
            } else {
                // Canvas/fork fallback
                entityRunMethod = findMethod(entityScheduler.getClass(), "run", Plugin.class, Runnable.class, Runnable.class);
                if (entityRunMethod != null) {
                    entityRunMethod.invoke(entityScheduler, plugin, runnable, (Runnable) null);
                } else {
                    DebugLogger.debug("Scheduler", "Entity scheduler run() not found, falling back to global.");
                    runTask(runnable);
                }
            }
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "Entity scheduler failed, falling back to global.", e);
            runTask(runnable);
        }
    }

    @Override
    public void cancelTask(Object task) {
        if (task == null) return;
        try {
            Method cancelMethod = task.getClass().getMethod("cancel");
            cancelMethod.invoke(task);
        } catch (Exception e) {
            DebugLogger.debug("Scheduler", "cancelTask failed", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }
}