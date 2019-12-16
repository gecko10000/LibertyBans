/* 
 * ArimBansLib, an API for ArimBans
 * Copyright © 2019 Anand Beh <https://www.arim.space>
 * 
 * ArimBansLib is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ArimBansLib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ArimBansLib. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU General Public License.
 */
package space.arim.bans.api.util.web;

import java.io.IOException;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import space.arim.bans.api.exception.FetcherException;
import space.arim.bans.api.exception.HttpStatusException;
import space.arim.bans.api.exception.RateLimitException;
import space.arim.bans.api.util.minecraft.MinecraftUtil;

public final class FetcherUtil {
	
	private static final String ASHCON_API = "https://api.ashcon.app/mojang/v2/user/";
	private static final String MOJANG_API_FROM_NAME = "https://api.mojang.com/users/profiles/minecraft/";
	private static final String MOJANG_API_FROM_UUID = "https://api.mojang.com/user/profiles/";
	
    private static final String SPIGOT_UPDATE_API = "https://api.spigotmc.org/legacy/update.php?resource=";
    
    private static final RateLimitedService IPSTACK = new RateLimitedService("IpStack", "http://api.ipstack.com/$ADDR?access_key=$KEY&fields=country_code,country_name,region_code,region_name,city,zip,latitude,longitude", 2592000L);
    private static final RateLimitedService FREEGEOIP = new RateLimitedService("FreeGeoIp", "https://freegeoip.app/json/$ADDR", 3600L);
    private static final RateLimitedService IPAPI = new RateLimitedService("IpApi", "https://ipapi.co/$ADDR/json/", 86400L);
    
	private FetcherUtil() {}
	
	private static JSONObject getJsonFromUrl(final String url) throws FetcherException, HttpStatusException {
		try (FetcherConnection conn = new FetcherConnection(url)) {
			return conn.connect().toJson();
		} catch (IOException | ParseException ex) {
			throw new FetcherException("Failed to connect to and parse JSON from " + url, ex);
		}
	}
	
	public static UUID mojangApi(final String name) throws FetcherException, HttpStatusException {
		return UUID.fromString(MinecraftUtil.expandUUID(getJsonFromUrl(MOJANG_API_FROM_NAME + Objects.requireNonNull(name, "Name must not be null!")).get("id").toString()));
	}
	
	public static String mojangApi(final UUID playeruuid) throws FetcherException, HttpStatusException {
		final String url = MOJANG_API_FROM_UUID + Objects.requireNonNull(playeruuid, "UUID must not be null!").toString().replace("-", "") + "/names";
		try (FetcherConnection conn = new FetcherConnection(url); Scanner scanner = new Scanner(conn.connect().inputStream())) {
			String s = scanner.useDelimiter("\\A").next();
			return ((JSONObject) JSONValue.parseWithException(s.substring(s.lastIndexOf('{'), s.lastIndexOf('}') + 1))).get("name").toString();
		} catch (IOException | ParseException ex) {
			throw new FetcherException("Could not connect to " + url, ex);
		}
	}
	
	public static UUID ashconApi(final String name) throws FetcherException, HttpStatusException {
		return UUID.fromString(getJsonFromUrl(ASHCON_API + Objects.requireNonNull(name, "Name must not be null!")).get("uuid").toString());
	}
	
	public static String ashconApi(final UUID playeruuid) throws FetcherException, HttpStatusException {
		return getJsonFromUrl(ASHCON_API + Objects.requireNonNull(playeruuid, "UUID must not be null!")).get("username").toString();
	}
	
	public static String getLatestPluginVersion(final int resourceId) throws FetcherException, HttpStatusException {
		final String url = SPIGOT_UPDATE_API + resourceId;
		try (FetcherConnection conn = new FetcherConnection(url); Scanner scanner = new Scanner(conn.connect().inputStream())) {
			if (scanner.hasNext()) {
				return scanner.next();
			}
			throw new FetcherException("Scanner has no input data!");
		} catch (IOException ex) {
			throw new FetcherException("Could not connect to url " + url, ex);
		}
	}
	
	public static GeoIpInfo ipStack(final String address, final String key) throws FetcherException, RateLimitException, HttpStatusException {
		final String url = IPSTACK.getUrl(address).replace("$KEY", Objects.requireNonNull(key, "Key must not be null!"));
		try {
			JSONObject json = getJsonFromUrl(url);
			return new GeoIpInfo(address, json.get("country_code").toString(), json.get("country_name").toString(), json.get("region_code").toString(), json.get("region_name").toString(), json.get("city").toString(), json.get("zip").toString(), Double.parseDouble(json.get("latitude").toString()), Double.parseDouble(json.get("longitude").toString()));
		} catch (HttpStatusException ex) {
			if (ex.status == HttpStatus.UNASSIGNED_104) {
				IPSTACK.expire();
			}
			throw ex;
		} catch (NumberFormatException ex) {
			throw new FetcherException("Could not parse JSON from " + url, ex);
		}
	}
	
	public static GeoIpInfo freeGeoIp(final String address) throws FetcherException, RateLimitException, HttpStatusException {
		final String url = FREEGEOIP.getUrl(address);
		try {
			JSONObject json = getJsonFromUrl(url);
			return new GeoIpInfo(address, json.get("country_code").toString(), json.get("country_name").toString(), json.get("region_code").toString(), json.get("region_name").toString(), json.get("city").toString(), json.get("zip_code").toString(), Double.parseDouble(json.get("latitude").toString()), Double.parseDouble(json.get("longitude").toString()));
		} catch (HttpStatusException ex) {
			if (ex.status == HttpStatus.FORBIDDEN) {
				FREEGEOIP.expire();
			}
			throw ex;
		} catch (NumberFormatException ex) {
			throw new FetcherException("Could not parse GeoIpInfo from url " + url, ex);
		}
	}
	
	public static GeoIpInfo ipApi(final String address) throws FetcherException, RateLimitException, HttpStatusException {
		final String url = IPAPI.getUrl(address);
		try {
			JSONObject json = getJsonFromUrl(url);
			return new GeoIpInfo(address, json.get("country").toString(), json.get("country_name").toString(), json.get("region_code").toString(), json.get("region").toString(), json.get("city").toString(), json.get("postal").toString(), Double.parseDouble(json.get("latitude").toString()), Double.parseDouble(json.get("longitude").toString()));
		} catch (HttpStatusException ex) {
			if (ex.status == HttpStatus.TOO_MANY_REQUESTS) {
				IPAPI.expire();
			}
			throw ex;
		} catch (NumberFormatException ex) {
			throw new FetcherException("Could not parse GeoIpInfo from url " + url, ex);
		}
	}
	
}