package space.arim.bans.env.bukkit;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import space.arim.bans.ArimBans;
import space.arim.bans.api.Subject;
import space.arim.bans.api.Subject.SubjectType;
import space.arim.bans.api.Tools;
import space.arim.bans.api.exception.InternalStateException;
import space.arim.bans.api.exception.InvalidSubjectException;
import space.arim.bans.env.Environment;

public class BukkitEnv implements Environment {
	
	private JavaPlugin plugin;
	private final Set<EnvLibrary> libraries = loadLibraries();
	private ArimBans center;
	private final BukkitEnforcer enforcer;
	private final BukkitResolver resolver;
	private final BukkitListener listener;
	private final BukkitCommands commands;
	
	private boolean registered = false;
	private boolean json;

	public BukkitEnv(JavaPlugin plugin) {
		this.plugin = plugin;
		refreshConfig();
		this.enforcer = new BukkitEnforcer(this);
		this.resolver = new BukkitResolver(this);
		this.listener = new BukkitListener(this);
		this.commands = new BukkitCommands(this);
	}
	
	public void setCenter(ArimBans center) {
		this.center = center;
		if (!registered) {
			plugin.getServer().getPluginManager().registerEvents(listener, plugin);
			plugin.getServer().getPluginCommand("arimban").setExecutor(commands);
		}
	}
	
	void json(Player target, String json) {
		target.spigot().sendMessage(Tools.parseJson(json));
	}

	@Override
	public void sendMessage(Subject subj, String jsonable) {
		if (json) {
			if (subj.getType().equals(SubjectType.PLAYER)) {
				Player target = plugin.getServer().getPlayer(subj.getUUID());
				if (target != null) {
					json(target.getPlayer(), jsonable);
					return;
				}
				throw new InvalidSubjectException("Subject " + center.subjects().display(subj) + " is not online or does not have a valid UUID.");
			} else if (subj.getType().equals(SubjectType.CONSOLE)) {
				plugin.getServer().getConsoleSender().sendMessage(Tools.encode(Tools.stripJson(jsonable)));
			} else if (subj.getType().equals(SubjectType.IP)) {
				for (Player target : applicable(subj)) {
					json(target, jsonable);
				}
			} else {
				throw new InvalidSubjectException("Subject type is completely missing!");
			}
			return;
		} else if (!json) {
			if (subj.getType().equals(SubjectType.PLAYER)) {
				Player target = plugin.getServer().getPlayer(subj.getUUID());
				if (target != null) {
					target.getPlayer().sendMessage(Tools.encode(jsonable));
					return;
				}
				throw new InvalidSubjectException("Subject " + center.subjects().display(subj) + " is not online or does not have a valid UUID.");
			} else if (subj.getType().equals(SubjectType.CONSOLE)) {
				plugin.getServer().getConsoleSender().sendMessage(Tools.encode(jsonable));
			} else if (subj.getType().equals(SubjectType.IP)) {
				for (Player target : applicable(subj)) {
					target.sendMessage(jsonable);
				}
			} else {
				throw new InvalidSubjectException("Subject type is completely missing!");
			}
			return;
		}
		throw new InternalStateException("Json setting is neither true nor false!");
	}
	
	@Override
	public boolean hasPermission(Subject subject, String...permissions) {
		if (subject.getType().equals(SubjectType.CONSOLE)) {
			return true;
		} else if (subject.getType().equals(SubjectType.PLAYER)) {
			Player target = plugin.getServer().getPlayer(subject.getUUID());
			if (target != null) {
				for (String perm : permissions) {
					if (!target.getPlayer().hasPermission(perm)) {
						return false;
					}
				}
				return true;
			}
			throw new InvalidSubjectException("Subject " + center.subjects().display(subject) + " is not online or does not have a valid UUID.");
		} else if (subject.getType().equals(SubjectType.IP)) {
			throw new InvalidSubjectException("Cannot invoke Environment#hasPermission(Subject, Permission[]) for IP-based subjects");
		}
		throw new InvalidSubjectException("Subject type is completely missing!");
	}
	
	public Set<? extends Player> applicable(Subject subject) {
		Set<Player> applicable = new HashSet<Player>();
		for (Player check : plugin.getServer().getOnlinePlayers()) {
			if (subject.getType().equals(SubjectType.PLAYER)) {
				if (subject.getUUID().equals(check.getUniqueId())) {
					applicable.add(check);
				}
			} else if (subject.getType().equals(SubjectType.IP)) {
				if (center.cache().hasIp(check.getUniqueId(), subject.getIP())) {
					applicable.add(check);
				}
			}
		}
		return applicable;
	}
	
	@Override
	public BukkitResolver resolver() {
		return resolver;
	}
	
	public JavaPlugin plugin() {
		return plugin;
	}
	
	public ArimBans center() {
		return center;
	}
	
	@Override
	public Logger logger() {
		return plugin.getLogger();
	}

	@Override
	public void close() throws Exception {
		HandlerList.unregisterAll(listener);
		commands.close();
		listener.close();
		resolver().close();
		enforcer().close();
	}

	@Override
	public boolean isLibrarySupported(EnvLibrary type) {
		return libraries.contains(type);
	}

	@Override
	public void refreshConfig() {
		json = center.config().parseBoolean("formatting.use-json");
	}

	@Override
	public BukkitEnforcer enforcer() {
		return enforcer;
	}

	@Override
	public String getVersion() {
		return plugin.getDescription().getVersion();
	}

}
