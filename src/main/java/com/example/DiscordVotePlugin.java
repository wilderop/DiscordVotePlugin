package com.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class DiscordVotePlugin extends JavaPlugin {

    private JDA jda;
    private String token;
    private long guildId;
    private long channelId;
    private long permanentRoleId;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<String, PendingVerify> pendingVerifies = new HashMap<>();
    private final Map<UUID, Long> links = new HashMap<>();
    private final Map<Long, Integer> credits = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        token = getConfig().getString("discord.token");
        guildId = getConfig().getLong("discord.guild_id");
        channelId = getConfig().getLong("discord.channel_id");
        permanentRoleId = getConfig().getLong("discord.permanent_role_id", 0L);

        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Failed to create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadData();

        try {
            jda = JDABuilder.createLight(token,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .addEventListeners(new DiscordListener(this))
                    .build();
            jda.awaitReady();
            getLogger().info("Discord bot connected.");

            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                TextChannel channel = guild.getTextChannelById(channelId);
                if (channel != null) {
                    // Ensure @everyone cannot send messages
                    var everyoneOverride = channel.getPermissionOverride(guild.getPublicRole());
                    if (everyoneOverride == null || everyoneOverride.getAllowed().contains(Permission.MESSAGE_SEND)) {
                        channel.upsertPermissionOverride(guild.getPublicRole())
                                .setDenied(Permission.MESSAGE_SEND)
                                .queue(null, e ->
                                        getLogger().warning("Failed to deny @everyone send: " + e.getMessage()));
                    }

                    // Periodic enforcement (every 5 min)
                    Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                        TextChannel ch = guild.getTextChannelById(channelId);
                        if (ch != null) {
                            var ov = ch.getPermissionOverride(guild.getPublicRole());
                            if (ov == null || ov.getAllowed().contains(Permission.MESSAGE_SEND)) {
                                ch.upsertPermissionOverride(guild.getPublicRole())
                                        .setDenied(Permission.MESSAGE_SEND)
                                        .queue(null, e ->
                                                getLogger().warning("Periodic deny failed: " + e.getMessage()));
                            }
                        }
                    }, 0L, 6000L);
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to start Discord bot: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }

        Objects.requireNonNull(getCommand("discordlink")).setExecutor(new LinkCommand(this));
        Objects.requireNonNull(getCommand("verify")).setExecutor(new VerifyCommand(this));
        Objects.requireNonNull(getCommand("grantdiscordchat")).setExecutor(new GrantCommand(this));
    }

    @Override
    public void onDisable() {
        saveData();
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    private void loadData() {
        if (data.contains("links")) {
            for (String key : data.getConfigurationSection("links").getKeys(false)) {
                links.put(UUID.fromString(key), data.getLong("links." + key));
            }
        }
        if (data.contains("credits")) {
            for (String key : data.getConfigurationSection("credits").getKeys(false)) {
                credits.put(Long.parseLong(key), data.getInt("credits." + key));
            }
        }
    }

    private void saveData() {
        links.forEach((uuid, discId) -> data.set("links." + uuid.toString(), discId));
        credits.forEach((discId, cred) -> data.set("credits." + discId, cred));
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    public void requestVerify(Player player, String discordUsername) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            player.sendMessage("§cGuild not found.");
            return;
        }

        List<Member> matches = guild.getMembers().stream()
                .filter(m -> m.getUser().getName().equalsIgnoreCase(discordUsername) ||
                        (m.getNickname() != null && m.getNickname().equalsIgnoreCase(discordUsername)))
                .limit(2)
                .collect(Collectors.toList());

        if (matches.size() != 1) {
            player.sendMessage("§cFound " + matches.size() + " matching users. Try a more specific name.");
            return;
        }

        Member member = matches.get(0);
        String code = String.format("%06d", new Random().nextInt(1000000));
        pendingVerifies.put(code, new PendingVerify(player.getUniqueId(), member.getIdLong()));

        member.getUser().openPrivateChannel()
                .flatMap(ch -> ch.sendMessage("Your verification code: **" + code + "**"))
                .queue(
                        v -> player.sendMessage("§aCode sent to your Discord DM."),
                        err -> player.sendMessage("§cFailed to send DM.")
                );
    }

    public void verify(Player player, String code) {
        PendingVerify pending = pendingVerifies.remove(code);
        if (pending == null || !pending.uuid.equals(player.getUniqueId())) {
            player.sendMessage("§cInvalid or expired code.");
            return;
        }

        links.put(player.getUniqueId(), pending.discId);
        saveData();
        player.sendMessage("§aLinked successfully!");
    }

    public void grantChat(String playerName) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        UUID uuid = op.getUniqueId();

        if (!links.containsKey(uuid)) {
            getLogger().warning("No Discord link for " + playerName);
            return;
        }

        long discId = links.get(uuid);
        int current = credits.getOrDefault(discId, 0);
        int newAmount = current + 1;
        credits.put(discId, newAmount);
        saveData();
        updatePermission(discId);

        getLogger().info("Granted chat credit to " + playerName + " → now " + newAmount);

        Player p = op.getPlayer();
        if (p != null) {
            p.sendMessage("§a+1 chat credit! You now have " + newAmount + ".");
        }
    }

    public void updatePermission(long discId) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;

        Member member = guild.getMemberById(discId);
        if (member == null) return;

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) return;

        boolean isPermanent = permanentRoleId != 0 && member.getRoles().stream()
                .anyMatch(r -> r.getIdLong() == permanentRoleId);

        int cred = credits.getOrDefault(discId, 0);

        if (isPermanent) {
            var ov = channel.getPermissionOverride(member);
            if (ov != null) {
                ov.delete().queue(null, e ->
                        getLogger().warning("Failed to remove override for permanent user: " + e.getMessage()));
            }
            return;
        }

        if (cred > 0) {
            channel.upsertPermissionOverride(member)
                    .setAllowed(Permission.MESSAGE_SEND)
                    .queue(null, e ->
                            getLogger().warning("Failed to allow send: " + e.getMessage()));
        } else {
            channel.upsertPermissionOverride(member)
                    .setDenied(Permission.MESSAGE_SEND)
                    .queue(null, e ->
                            getLogger().warning("Failed to deny send: " + e.getMessage()));
        }
    }

    private static class PendingVerify {
        final UUID uuid;
        final long discId;

        PendingVerify(UUID uuid, long discId) {
            this.uuid = uuid;
            this.discId = discId;
        }
    }

    private static class DiscordListener extends ListenerAdapter {
        private final DiscordVotePlugin plugin;

        DiscordListener(DiscordVotePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (!event.isFromGuild() || event.getAuthor().isBot()) return;
            if (event.getChannel().getIdLong() != plugin.channelId) return;

            long discId = event.getAuthor().getIdLong();
            Guild guild = event.getGuild();
            Member member = guild.getMemberById(discId);

            if (member == null) {
                guild.retrieveMemberById(discId).queue(
                        m -> processMessage(m),
                        err -> {}
                );
                return;
            }

            processMessage(member);
        }

        private void processMessage(Member member) {
            boolean isPermanent = plugin.permanentRoleId != 0 &&
                    member.getRoles().stream().anyMatch(r -> r.getIdLong() == plugin.permanentRoleId);

            if (isPermanent) return;

            long discId = member.getIdLong();
            int cred = plugin.credits.getOrDefault(discId, 0);

            if (cred > 0) {
                plugin.credits.put(discId, cred - 1);
                plugin.saveData();
                plugin.updatePermission(discId);
                if (cred - 1 <= 1) {
                    plugin.getLogger().info(member.getEffectiveName() + " low on credits: " + (cred - 1));
                }
            } else {
                plugin.updatePermission(discId);
            }
        }
    }
}

class LinkCommand implements CommandExecutor {
    private final DiscordVotePlugin plugin;

    LinkCommand(DiscordVotePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can link.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§cUsage: /discordlink <discord_username>");
            return true;
        }
        plugin.requestVerify(player, args[0]);
        return true;
    }
}

class VerifyCommand implements CommandExecutor {
    private final DiscordVotePlugin plugin;

    VerifyCommand(DiscordVotePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can verify.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§cUsage: /verify <code>");
            return true;
        }
        plugin.verify(player, args[0]);
        return true;
    }
}

class GrantCommand implements CommandExecutor {
    private final DiscordVotePlugin plugin;

    GrantCommand(DiscordVotePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cOnly operators can use this.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("§cUsage: /grantdiscordchat <player_name>");
            return true;
        }

        String target = args[0];
        plugin.grantChat(target);
        sender.sendMessage("§aGranted 1 credit to " + target + ".");
        return true;
    }
}
