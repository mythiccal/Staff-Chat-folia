/*
 * The MIT License
 * Copyright © 2017-2026 RezzedUp and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.rezzedup.discordsrv.staffchat.scheduling;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class BukkitServerScheduler implements ServerScheduler {
	private static final long MILLIS_PER_TICK = 50L;
	
	private final Plugin plugin;
	
	BukkitServerScheduler(Plugin plugin) {
		this.plugin = plugin;
	}
	
	@Override
	public void runGlobal(Runnable task) {
		runOnMainThread(task);
	}
	
	@Override
	public void runGlobalDelayed(long delayTicks, Runnable task) {
		Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
	}
	
	@Override
	public void runAsync(Runnable task) {
		Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
	}
	
	@Override
	public TaskHandle runAsyncRepeating(long initialDelay, long period, TimeUnit unit, Runnable task) {
		TaskHandle handle = new TaskHandle();
		BukkitTask scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(
			plugin,
			task,
			toTicks(initialDelay, unit),
			toTicks(period, unit)
		);
		handle.bind(scheduled::cancel);
		return handle;
	}
	
	@Override
	public void runEntity(Entity entity, Runnable task) {
		runOnMainThread(task);
	}
	
	@Override
	public void runEntityDelayed(Entity entity, long delayTicks, Runnable task) {
		runGlobalDelayed(delayTicks, task);
	}
	
	@Override
	public TaskHandle runEntityRepeating(
		Entity entity,
		long initialDelayTicks,
		long periodTicks,
		Consumer<TaskHandle> task
	) {
		TaskHandle handle = new TaskHandle();
		BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(
			plugin,
			() -> task.accept(handle),
			Math.max(1L, initialDelayTicks),
			Math.max(1L, periodTicks)
		);
		handle.bind(scheduled::cancel);
		return handle;
	}
	
	private void runOnMainThread(Runnable task) {
		if (Bukkit.isPrimaryThread()) {
			task.run();
			return;
		}
		Bukkit.getScheduler().runTask(plugin, task);
	}
	
	private static long toTicks(long duration, TimeUnit unit) {
		long ticks = unit.toMillis(duration) / MILLIS_PER_TICK;
		return Math.max(1L, ticks);
	}
}
