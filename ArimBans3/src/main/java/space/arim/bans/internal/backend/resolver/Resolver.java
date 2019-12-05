/*
 * ArimBans, a punishment plugin for minecraft servers
 * Copyright © 2019 Anand Beh <https://www.arim.space>
 * 
 * ArimBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ArimBans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ArimBans. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU General Public License.
 */
package space.arim.bans.internal.backend.resolver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import space.arim.bans.ArimBans;
import space.arim.bans.api.exception.FetcherException;
import space.arim.bans.api.exception.HttpStatusException;
import space.arim.bans.api.exception.MissingCacheException;
import space.arim.bans.api.exception.NoGeoIpException;
import space.arim.bans.api.exception.PlayerNotFoundException;
import space.arim.bans.api.exception.RateLimitException;
import space.arim.bans.api.util.FetcherUtil;
import space.arim.bans.api.util.GeoIpInfo;
import space.arim.bans.api.util.ToolsUtil;
import space.arim.bans.internal.sql.SqlQuery;
import space.arim.registry.RegistryPriority;

public class Resolver implements ResolverMaster {
	
	private final ArimBans center;
	
	private final ConcurrentHashMap<UUID, CacheElement> cache = new ConcurrentHashMap<UUID, CacheElement>();

	private boolean internalFetcher = true;
	private boolean ashconFetcher = true;
	private boolean mojangFetcher = true;
	private boolean ipStack = false;
	private String ipStackKey = "nokey";
	private boolean freeGeoIp = true;
	private boolean ipApi = true;
	
	public Resolver(ArimBans center) {
		this.center = center;
	}

	@Override
	public void loadAll(ResultSet data) {
		try {
			while (data.next()) {
				
				try {
					cache.put(UUID.fromString(ToolsUtil.expandUUID(data.getString("uuid"))), new CacheElement(data.getString("name"), data.getString("iplist"), data.getLong("update-name"), data.getLong("update-iplist")));
				} catch (IllegalArgumentException ex) {
					center.logError(ex);
				}
				
			}
		} catch (SQLException ex) {
			center.logError(ex);
		}
	}

	@Override
	public List<String> getIps(UUID playeruuid) throws MissingCacheException {
		Objects.requireNonNull(playeruuid, "UUID must not be null!");
		if (cache.containsKey(playeruuid)) {
			return cache.get(playeruuid).getIps();
		}
		throw new MissingCacheException(playeruuid);
	}
	
	@Override
	public List<UUID> getPlayers(String address) {
		Objects.requireNonNull(address, "Address must not be null!");
		List<UUID> applicable = new ArrayList<UUID>();
		cache.forEach((uuid, element) -> {
			if (element.hasIp(address)) {
				applicable.add(uuid);
			}
		});
		return applicable;
	}
	
	@Override
	public String getName(UUID playeruuid) throws MissingCacheException {
		if (cache.containsKey(playeruuid)) {
			return cache.get(playeruuid).getName();
		}
		throw new MissingCacheException(playeruuid);
	}
	
	@Override
	public UUID getUUID(String name) throws MissingCacheException {
		for (UUID id : cache.keySet()) {
			if (cache.get(id).getName().equalsIgnoreCase(name)) {
				return id;
			}
		}
		throw new MissingCacheException(name);
	}
	
	@Override
	public void update(final UUID uuid, String name, String address) {
		
		if (cache.containsKey(uuid)) {
			final CacheElement element = cache.get(uuid);
			final boolean updateName = !element.getName().equalsIgnoreCase(name);
			final boolean updateIplist = !element.hasIp(address);
			if (updateName || updateIplist) {
				center.async(() -> {
					if (updateName && updateIplist) {
						center.sql().executeQuery(element.setNameAndAddIp(uuid, name, address));
					} else if (updateName) {
						center.sql().executeQuery(element.setName(uuid, name));
					} else if (updateIplist) {
						center.sql().executeQuery(element.addIp(uuid, address));
					}
				});
			}
		} else {
			final CacheElement element = new CacheElement(name, (address == null) ? CacheElement.EMPTY_IPLIST_STRING : address);
			center.async(() -> {
				cache.put(uuid, element);
				center.sql().executeQuery(element.getInsertionQuery(uuid));
			});
		}

	}
	
	@Override
	public boolean clearCachedIp(boolean async, String address) {
		Set<SqlQuery> exec = new HashSet<SqlQuery>();
		cache.forEach((uuid, element) -> {
			if (element.hasIp(address)) {
				exec.add(element.removeIp(uuid, address));
			}
		});
		if (exec.isEmpty()) {
			return false;
		}
		if (async) {
			center.async(() -> {
				center.sql().executeQuery(exec.toArray(new SqlQuery[] {}));
			});
		} else {
			center.sql().executeQuery(exec.toArray(new SqlQuery[] {}));
		}
		return true;
	}
	
	@Override
	public boolean uuidExists(UUID uuid) {
		return cache.containsKey(uuid);
	}

	@Override
	public boolean hasIp(UUID playeruuid, String address) {
		return cache.containsKey(playeruuid) && cache.get(playeruuid).hasIp(address);
	}
	
	@Override
	public GeoIpInfo lookupIp(final String address) throws NoGeoIpException {
		if (ipStack) {
			try {
				return FetcherUtil.ipStack(address, ipStackKey);
			} catch (FetcherException | RateLimitException | HttpStatusException ex) {
				center.logError(ex);
			}
		}
		if (freeGeoIp) {
			try {
				return FetcherUtil.freeGeoIp(address);
			} catch (FetcherException| RateLimitException | HttpStatusException ex) {
				center.logError(ex);
			}
		}
		if (ipApi) {
			try {
				return FetcherUtil.ipApi(address);
			} catch (FetcherException| RateLimitException | HttpStatusException ex) {
				center.logError(ex);
			}
		}
		throw new NoGeoIpException(address);
	}
	
	@Override
	public UUID resolveName(String name, boolean query) throws PlayerNotFoundException {
		try {
			return getUUID(name);
		} catch (MissingCacheException ex) {}
		if (internalFetcher) {
			try {
				UUID uuid2 = center.environment().uuidFromName(name);
				update(uuid2, name, null);
				return uuid2;
			} catch (PlayerNotFoundException ex) {}
		}
		if (query && center.environment().isOnlineMode()) {
			if (ashconFetcher) {
				try {
					UUID uuid3 = FetcherUtil.ashconApi(name);
					update(uuid3, name, null);
					return uuid3;
				} catch (FetcherException | HttpStatusException ex) {}
			}
			if (mojangFetcher) {
				try {
					UUID uuid4 = FetcherUtil.mojangApi(name);
					update(uuid4, name, null);
					return uuid4;
				} catch (FetcherException | HttpStatusException ex) {}
			}
		}
		throw new PlayerNotFoundException(name);
	}
	
	@Override
	public String resolveUUID(UUID uuid, boolean query) throws PlayerNotFoundException {
		try {
			return getName(uuid);
		} catch (MissingCacheException ex) {}
		if (internalFetcher) {
			try {
				String name2 = center.environment().nameFromUUID(uuid);
				update(uuid, name2, null);
				return name2;
			} catch (PlayerNotFoundException ex) {}
		}
		if (query && center.environment().isOnlineMode()) {
			if (ashconFetcher) {
				try {
					String name3 = FetcherUtil.ashconApi(uuid);
					update(uuid, name3, null);
					return name3;
				} catch (FetcherException | HttpStatusException ex) {}
			}
			if (mojangFetcher) {
				try {
					String name4 = FetcherUtil.mojangApi(uuid);
					update(uuid, name4, null);
					return name4;
				} catch (FetcherException | HttpStatusException ex) {}
			}
		}
		throw new PlayerNotFoundException(uuid);
	}

	@Override
	public String getName() {
		return center.getName();
	}
	
	@Override
	public String getAuthor() {
		return center.getAuthor();
	}

	@Override
	public String getVersion() {
		return center.getVersion();
	}

	@Override
	public byte getPriority() {
		return RegistryPriority.HIGHER;
	}
	
	@Override
	public void refreshConfig(boolean first) {
		internalFetcher = center.config().getConfigBoolean("fetchers.uuids.internal");
		ashconFetcher = center.config().getConfigBoolean("fetchers.uuids.ashcon");
		mojangFetcher = center.config().getConfigBoolean("fetchers.uuids.mojang");
		ipStack = center.config().getConfigBoolean("fetchers.ips.ipstack.enabled");
		ipStackKey = center.config().getConfigString("fetchers.ips.ipstack.key");
		freeGeoIp = center.config().getConfigBoolean("fetchers.ips.freegeoip");
		ipApi = center.config().getConfigBoolean("fetchers.ips.ipapi");
	}

}