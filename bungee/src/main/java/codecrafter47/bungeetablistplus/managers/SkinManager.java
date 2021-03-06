/*
 * BungeeTabListPlus - a BungeeCord plugin to customize the tablist
 *
 * Copyright (C) 2014 - 2015 Florian Stober
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package codecrafter47.bungeetablistplus.managers;

import codecrafter47.bungeetablistplus.BungeeTabListPlus;
import codecrafter47.bungeetablistplus.api.bungee.Skin;
import codecrafter47.bungeetablistplus.skin.PlayerSkin;
import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import lombok.SneakyThrows;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SkinManager {

    private final Plugin plugin;
    private static final Gson gson = new Gson();

    private final Cache<String, Skin> cache = CacheBuilder.newBuilder().expireAfterAccess(35, TimeUnit.MINUTES).build();
    public static final Skin defaultSkin = new PlayerSkin(UUID.randomUUID(), null);

    private final Set<String> fetchingSkins = Sets.newConcurrentHashSet();

    private final static Pattern PATTERN_VALID_USERNAME = Pattern.compile("(?:\\p{Alnum}|_){1,16}");

    public SkinManager(Plugin plugin) {
        this.plugin = plugin;
    }

    @SneakyThrows
    public Skin getSkin(String nameOrUUID) {
        Skin skin = cache.getIfPresent(nameOrUUID);
        if (skin != null) return skin;
        if (!fetchingSkins.contains(nameOrUUID)) {
            fetchingSkins.add(nameOrUUID);
            ProxyServer.getInstance().getScheduler().schedule(plugin, new SkinFetchTask(nameOrUUID), 0, TimeUnit.MILLISECONDS);
        }
        return defaultSkin;
    }

    private String fetchUUID(final String player) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://api.mojang.com/profiles/minecraft").
                    openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            try (DataOutputStream out = new DataOutputStream(connection.
                    getOutputStream())) {
                out.write(("[\"" + player + "\"]").getBytes(Charsets.UTF_8));
                out.flush();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), Charsets.UTF_8));
            Profile[] profiles = gson.fromJson(reader, Profile[].class);
            if (profiles != null && profiles.length >= 1) {
                return profiles[0].id;
            }
            return null;
        } catch (Throwable e) {
            if (e instanceof ConnectException || e instanceof UnknownHostException) {
                // generic connection error, retry in 30 seconds
                plugin.getLogger().warning("An error occurred while connecting to mojang servers: " + e.getMessage() + ". Will retry in 30 Seconds");
                plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
                    @Override
                    public void run() {
                        fetchingSkins.remove(player);
                    }
                }, 30, TimeUnit.SECONDS);
            } else if (e instanceof IOException && e.getMessage().contains("429")) {
                // mojang rate limit; try again later
                plugin.getLogger().warning("Hit mojang rate limits while fetching uuid for " + player + ".");
                String headerField = connection.getHeaderField("Retry-After");
                plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
                    @Override
                    public void run() {
                        fetchingSkins.remove(player);
                    }
                }, headerField == null ? 300 : Integer.valueOf(headerField), TimeUnit.SECONDS);
            } else {
                BungeeTabListPlus.getInstance().reportError(e);
            }
        }
        return null;

    }

    private Skin fetchSkin(String uuid) {
        try {
            uuid = uuid.replace("-", "");
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false").
                    openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), Charsets.UTF_8));
            SkinProfile skin = gson.fromJson(reader, SkinProfile.class);
            if (skin != null && skin.properties != null && !skin.properties.isEmpty()) {
                return new PlayerSkin(UUID.fromString(uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20, 32)), new String[]{"textures", skin.properties.get(0).value, skin.properties.
                        get(0).signature});
            }
        } catch (Throwable e) {
            if (e instanceof ConnectException || e instanceof UnknownHostException) {
                // generic connection error
                plugin.getLogger().warning("An error occurred while connecting to mojang servers(" + e.getMessage() + "). Couldn't fetch skin for " + uuid + ". Will retry in 5 minutes.");
            } else if (e instanceof IOException && e.getMessage().contains("429")) {
                // mojang rate limit; try again later
                plugin.getLogger().info("Hit mojang rate limits while fetching skin for " + uuid + ". Will retry in 5 minutes. (This is not an error)");
            } else {
                // this will spam some users logs, but we can ignore more exceptions later
                BungeeTabListPlus.getInstance().reportError(new Exception("Unable to resolve skin for " + uuid, e));
            }
        }
        return null;

    }

    private static class Profile {

        private String id;
        private String name;
    }

    private static class SkinProfile {

        private String id;
        private String name;

        final List<Property> properties = new ArrayList<>();

        private static class Property {

            private String name, value, signature;
        }
    }

    private class SkinFetchTask implements Runnable {

        final String nameOrUUID;

        public SkinFetchTask(String nameOrUUID) {
            this.nameOrUUID = nameOrUUID;
        }

        @Override
        public void run() {
            String uuid = null;
            if (PATTERN_VALID_USERNAME.matcher(nameOrUUID).matches()) {
                uuid = fetchUUID(nameOrUUID);
            } else if(nameOrUUID.replace("-", "").length() == 32){
                uuid = nameOrUUID;
            }

            if (uuid != null) {
                Skin skin = fetchSkin(uuid);
                if (skin != null) {
                    cache.put(nameOrUUID, skin);
                    fetchingSkins.remove(nameOrUUID);

                    // we received a new skin -> update tab to all players
                    BungeeTabListPlus.getInstance().resendTabLists();
                } else {
                    plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
                        @Override
                        public void run() {
                            fetchingSkins.remove(nameOrUUID);
                        }
                    }, 5, TimeUnit.MINUTES);
                }
            } else {
                BungeeTabListPlus.getInstance().getLogger().warning("can't fetch skin for '" + nameOrUUID + "', is neither a name nor an uuid");
            }
        }
    }
}
