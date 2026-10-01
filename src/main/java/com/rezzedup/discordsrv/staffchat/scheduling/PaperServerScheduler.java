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

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class PaperServerScheduler implements ServerScheduler {
	private final Plugin plugin;
	
	PaperServerScheduler(Plugin plugin) {
		this.plugin = plugin;
	}
	
	@Override
	public void runGlobal(Runnable task) {
		if (Bukkit.isGlobalTickThread()) {
			task.run();
			return;
		}
		Bukkit.getGlobalRegionScheduler().execute(plugin, task);
	}
	
	@Override
	public void runGlobalDelayed(long delayTicks, Runnable task) {
		Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), Math.max(1L, delayTicks));
	}
	
	@Override
	public void runAsync(Runnable task) {
		Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
	}
	
	@Override
	public TaskHandle runAsyncRepeating(long initialDelay, long period, TimeUnit unit, Runnable task) {
		TaskHandle handle = new TaskHandle();
		AtomicReference<ScheduledTask> scheduledTask = new AtomicReference<>();
		handle.bind(() -> cancel(scheduledTask.get()));
		ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(
			plugin,
			ignored -> task.run(),
			Math.max(0L, initialDelay),
			Math.max(1L, period),
			unit
		);
		scheduledTask.set(scheduled);
		if (handle.isCancelled()) {
			scheduled.cancel();
		}
		return handle;
	}
	
	@Override
	public void runEntity(Entity entity, Runnable task) {
		if (Bukkit.isOwnedByCurrentRegion(entity)) {
			task.run();
			return;
		}
		entity.getScheduler().execute(plugin, task, null, 1L);
	}
	
	@Override
	public void runEntityDelayed(Entity entity, long delayTicks, Runnable task) {
		entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, Math.max(1L, delayTicks));
	}
	
	@Override
	public TaskHandle runEntityRepeating(
		Entity entity,
		long initialDelayTicks,
		long periodTicks,
		Consumer<TaskHandle> task
	) {
		TaskHandle handle = new TaskHandle();
		AtomicReference<ScheduledTask> scheduledTask = new AtomicReference<>();
		handle.bind(() -> cancel(scheduledTask.get()));
		ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
			plugin,
			ignored -> {
				if (!handle.isCancelled()) {
					task.accept(handle);
				}
			},
			handle::cancel,
			Math.max(1L, initialDelayTicks),
			Math.max(1L, periodTicks)
		);
		if (scheduled == null) {
			handle.cancel();
			return handle;
		}
		scheduledTask.set(scheduled);
		if (handle.isCancelled()) {
			scheduled.cancel();
		}
		return handle;
	}
	
	private static void cancel(ScheduledTask scheduled) {
		if (scheduled != null) {
			scheduled.cancel();
		}
	}
}
