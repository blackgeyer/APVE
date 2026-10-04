package org.apve.engine;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ScheduleManager {

    private static ScheduleManager instance;
    private final FoliaLib foliaLib;

    private ScheduleManager(JavaPlugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
    }

    public static void init(JavaPlugin plugin) {
        if (instance == null) {
            instance = new ScheduleManager(plugin);
        }
    }

    public static ScheduleManager get() {
        if (instance == null) {
            throw new IllegalStateException("ScheduleManager is not initialized.");
        }
        return instance;
    }

    public CompletableFuture<Void> runNextTick(Runnable task) {
        return foliaLib.getImpl().runNextTick(wrappedTask -> task.run());
    }

    public CompletableFuture<Void> runGlobal(Runnable task) {
        return foliaLib.getImpl().runNextTick(wrappedTask -> task.run());
    }

    public WrappedTask runGlobalLater(Runnable task, long delayTicks) {
        return foliaLib.getImpl().runLater(task, delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public WrappedTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runTimer(task, delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public WrappedTask runLater(Runnable task, long delayTicks) {
        return foliaLib.getImpl().runLater(task, delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public WrappedTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runTimer(task, delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> runAtEntity(Entity entity, Runnable task) {
        return foliaLib.getImpl().runAtEntity(entity, wrappedTask -> task.run()).thenAccept(ignored -> {});
    }

    public WrappedTask runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        return foliaLib.getImpl().runAtEntityLater(entity, task, delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public WrappedTask runAtEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runAtEntityTimer(entity, task, delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> runAtLocation(Location location, Runnable task) {
        return foliaLib.getImpl().runAtLocation(location, wrappedTask -> task.run());
    }

    public WrappedTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        return foliaLib.getImpl().runAtLocationLater(location, task, delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return foliaLib.getImpl().runAsync(wrappedTask -> task.run());
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }
}