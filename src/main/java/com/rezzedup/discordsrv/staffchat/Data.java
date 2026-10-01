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
package com.rezzedup.discordsrv.staffchat;

import com.rezzedup.discordsrv.staffchat.config.StaffChatConfig;
import com.rezzedup.discordsrv.staffchat.events.AutoStaffChatToggleEvent;
import com.rezzedup.discordsrv.staffchat.events.ReceivingStaffChatToggleEvent;
import com.rezzedup.discordsrv.staffchat.scheduling.TaskHandle;
import community.leaf.configvalues.bukkit.YamlValue;
import community.leaf.configvalues.bukkit.data.YamlDataFile;
import community.leaf.configvalues.bukkit.util.Sections;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import pl.tlinkowski.annotation.basic.NullOr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Data extends YamlDataFile implements StaffChatData {
	private static final String PROFILES_PATH = "staff-chat.profiles";
	
	private final Map<UUID, Profile> profilesByUuid = new HashMap<>();
	
	private final Object lock = new Object();
	
	private final StaffChatPlugin plugin;
	
	private @NullOr TaskHandle task = null;
	
	Data(StaffChatPlugin plugin) {
		super(plugin.directory().resolve("data"), "staff-chat.data.yml");
		this.plugin = plugin;
		
		// Load persistent toggles.
		if (plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) {
			Sections.get(data(), PROFILES_PATH).ifPresent(section ->
			{
				for (String key : section.getKeys(false)) {
					try {
						getOrCreateProfile(UUID.fromString(key));
					} catch (IllegalArgumentException ignored) {
					}
				}
			});
		}
		
		// Start the save task.
		task = plugin.scheduler().runAsyncRepeating(2, 2, TimeUnit.MINUTES, this::saveIfUpdated);
		
		// Update profiles of all online players when reloaded.
		reloadsWith(this::refreshOnlineProfiles);
	}
	
	public void reloadSynced() {
		synchronized (lock) {
			reload();
		}
	}
	
	@Override
	public void save() {
		synchronized (lock) {
			super.save();
		}
	}
	
	protected void end() {
		if (task != null) {
			task.cancel();
		}
		saveIfUpdated();
	}
	
	private void saveIfUpdated() {
		synchronized (lock) {
			if (isUpdated()) {
				super.save();
			}
		}
	}
	
	private void refreshOnlineProfiles() {
		for (Player player : new ArrayList<>(plugin.getServer().getOnlinePlayers())) {
			plugin.scheduler().runEntity(player, () -> updateProfile(player));
		}
	}
	
	public boolean hasAutomaticStaffChat(UUID uuid) {
		synchronized (lock) {
			@NullOr Profile profile = profilesByUuid.get(uuid);
			return profile != null && profile.automaticStaffChat();
		}
	}
	
	@Override
	public StaffChatProfile getOrCreateProfile(UUID uuid) {
		synchronized (lock) {
			return profilesByUuid.computeIfAbsent(uuid, k -> new Profile(plugin, this, k));
		}
	}
	
	@Override
	public Optional<StaffChatProfile> getProfile(UUID uuid) {
		synchronized (lock) {
			return Optional.ofNullable(profilesByUuid.get(uuid));
		}
	}
	
	public void updateProfile(Player player) {
		synchronized (lock) {
			updateProfileLocked(player);
		}
	}
	
	private void updateProfileLocked(Player player) {
		@NullOr Profile profile = profilesByUuid.get(player.getUniqueId());
		
		if (Permissions.ACCESS.allows(player)) {
			// Ensure that this staff member has an active profile.
			if (profile == null) {
				profile = (Profile) getOrCreateProfile(player);
			}
			
			// If leaving the staff chat is disabled...
			if (!plugin.config().getOrDefault(StaffChatConfig.LEAVING_STAFFCHAT_ENABLED)) {
				// ... and this staff member previously left the staff chat ...
				if (profile.left != null) {
					// Bring them back.
					profile.receivesStaffChatMessages(true);
				}
			}
		} else {
			// Not a staff member but has a loaded profile...
			if (profile != null) {
				// Notify that they're no longer talking in staff chat.
				if (profile.automaticStaffChat()) {
					profile.automaticStaffChat(false);
				}
				
				// No longer staff, delete data.
				profile.clearStoredProfileData();
				
				// Remove from the map.
				profilesByUuid.remove(player.getUniqueId());
			}
		}
	}
	
	static class Profile implements StaffChatProfile {
		static final YamlValue<Instant> AUTO_TOGGLE_DATE = YamlValue.ofInstant("toggles.auto").maybe();
		
		static final YamlValue<Instant> LEFT_TOGGLE_DATE = YamlValue.ofInstant("toggles.left").maybe();
		
		static final YamlValue<Boolean> MUTED_SOUNDS_TOGGLE = YamlValue.ofBoolean("toggles.muted-sounds").maybe();
		
		private final StaffChatPlugin plugin;
		private final Data data;
		private final UUID uuid;
		
		private @NullOr Instant auto;
		private @NullOr Instant left;
		private boolean mutedSounds = false;
		
		Profile(StaffChatPlugin plugin, Data data, UUID uuid) {
			this.plugin = plugin;
			this.data = data;
			this.uuid = uuid;
			
			if (plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) {
				Sections.get(data.data(), path()).ifPresent(section ->
				{
					auto = AUTO_TOGGLE_DATE.get(section).orElse(null);
					left = LEFT_TOGGLE_DATE.get(section).orElse(null);
					mutedSounds = MUTED_SOUNDS_TOGGLE.get(section).orElse(false);
				});
			}
		}
		
		String path() {
			return PROFILES_PATH + "." + uuid;
		}
		
		@Override
		public UUID uuid() {
			return uuid;
		}
		
		@Override
		public Optional<Instant> sinceEnabledAutoChat() {
			synchronized (data.lock) {
				return Optional.ofNullable(auto);
			}
		}
		
		@Override
		public boolean automaticStaffChat() {
			synchronized (data.lock) {
				return auto != null;
			}
		}
		
		@Override
		public void automaticStaffChat(boolean enabled) {
			if (plugin.events().call(new AutoStaffChatToggleEvent(this, enabled)).isCancelled()) {
				return;
			}
			
			synchronized (data.lock) {
				auto = (enabled) ? Instant.now() : null;
				updateStoredProfileData();
			}
		}
		
		@Override
		public Optional<Instant> sinceLeftStaffChat() {
			synchronized (data.lock) {
				return Optional.ofNullable(left);
			}
		}
		
		@Override
		public boolean receivesStaffChatMessages() {
			synchronized (data.lock) {
				// hasn't left the staff chat or leaving is disabled outright
				return left == null || !plugin.config().getOrDefault(StaffChatConfig.LEAVING_STAFFCHAT_ENABLED);
			}
		}
		
		@Override
		public void receivesStaffChatMessages(boolean enabled) {
			if (plugin.events().call(new ReceivingStaffChatToggleEvent(this, enabled)).isCancelled()) {
				return;
			}
			
			synchronized (data.lock) {
				left = (enabled) ? null : Instant.now();
				updateStoredProfileData();
			}
		}
		
		@Override
		public boolean receivesStaffChatSounds() {
			synchronized (data.lock) {
				return !mutedSounds;
			}
		}
		
		@Override
		public void receivesStaffChatSounds(boolean enabled) {
			synchronized (data.lock) {
				mutedSounds = !enabled;
			}
		}
		
		boolean hasDefaultSettings() {
			return auto == null && left == null && !mutedSounds;
		}
		
		void clearStoredProfileData() {
			synchronized (data.lock) {
				if (!plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) {
					return;
				}
				
				data.data().set(path(), null);
				data.updated(true);
			}
		}
		
		void updateStoredProfileData() {
			synchronized (data.lock) {
				writeStoredProfileData();
			}
		}
		
		private void writeStoredProfileData() {
			if (!plugin.config().getOrDefault(StaffChatConfig.PERSIST_TOGGLES)) {
				return;
			}
			
			if (hasDefaultSettings()) {
				data.data().set(path(), null);
				data.updated(true);
				return;
			}
			
			ConfigurationSection section = Sections.getOrCreate(data.data(), path());
			
			AUTO_TOGGLE_DATE.set(section, auto);
			LEFT_TOGGLE_DATE.set(section, left);
			MUTED_SOUNDS_TOGGLE.set(section, mutedSounds);
			
			data.updated(true);
		}
	}
}
