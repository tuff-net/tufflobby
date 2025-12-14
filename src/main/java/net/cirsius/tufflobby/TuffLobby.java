package net.cirsius.tufflobby;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.*;

public class TuffLobby extends JavaPlugin implements Listener, PluginMessageListener {
    private Map<UUID, Location> frozenPlayers = new HashMap<>();
    private Map<String, Integer> serverCounts = new HashMap<>();
    private List<ServerData> servers = new ArrayList<>();
    private Map<UUID, Set<String>> pendingCounts = new HashMap<>();
    private String guiTitle;
    private String playerCountFormat;
    private String leaveButtonName;
    private String leaveKickMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadServers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", this);
    }

    private void loadServers() {
        servers.clear();
        guiTitle = getConfig().getString("gui.title");
        playerCountFormat = getConfig().getString("gui.player-count-format");
        leaveButtonName = getConfig().getString("gui.leave-button-name");
        leaveKickMessage = getConfig().getString("gui.leave-kick-message");
        ConfigurationSection sec = getConfig().getConfigurationSection("servers");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String name = sec.getString(key + ".name");
            String mat = sec.getString(key + ".material");
            String srv = sec.getString(key + ".server");
            int slot = sec.getInt(key + ".slot");
            Material m = Material.getMaterial(mat);
            if (m == null) m = Material.GRASS;
            servers.add(new ServerData(name, m, srv, slot));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location spawnLocation = player.getLocation().clone();
        spawnLocation.setY(60);
        player.teleport(spawnLocation);
        
        UUID playerId = player.getUniqueId();
        frozenPlayers.put(playerId, spawnLocation);
        
        Set<String> expectedServers = new HashSet<>();
        for (ServerData server : servers) {
            expectedServers.add(server.server);
        }
        pendingCounts.put(playerId, expectedServers);
        
        openGui(player);
        requestCounts(player);
    }

    private void requestCounts(Player player) {
        for (int i = 0; i < servers.size(); i++) {
            ServerData server = servers.get(i);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    DataOutputStream output = new DataOutputStream(stream);
                    output.writeUTF("PlayerCount");
                    output.writeUTF(server.server);
                    output.close();
                    player.sendPluginMessage(this, "BungeeCord", stream.toByteArray());
                } catch (Exception ignored) {}
            }, i * 2L);
        }
    }

    private void openGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, guiTitle);
        
        for (ServerData server : servers) {
            ItemStack item = new ItemStack(server.material);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(server.name);
            
            String playerCount = serverCounts.containsKey(server.server) ? 
                String.valueOf(serverCounts.get(server.server)) : "?";
            
            List<String> description = new ArrayList<>();
            description.add(playerCountFormat + playerCount);
            meta.setLore(description);
            item.setItemMeta(meta);
            gui.setItem(server.slot, item);
        }
        
        ItemStack exitButton = new ItemStack(Material.BARRIER);
        ItemMeta exitMeta = exitButton.getItemMeta();
        exitMeta.setDisplayName(leaveButtonName);
        exitButton.setItemMeta(exitMeta);
        gui.setItem(8, exitButton);
        
        player.openInventory(gui);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!frozenPlayers.containsKey(player.getUniqueId())) return;
        
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        
        boolean hasMoved = Math.abs(from.getX() - to.getX()) > 0.1 || 
                          Math.abs(from.getY() - to.getY()) > 0.1 || 
                          Math.abs(from.getZ() - to.getZ()) > 0.1;
        
        if (hasMoved) {
            event.setTo(frozenPlayers.get(player.getUniqueId()));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        
        Player player = (Player) event.getPlayer();
        if (!frozenPlayers.containsKey(player.getUniqueId())) return;
        
        if (event.getInventory().getName().equals(guiTitle)) {
            Bukkit.getScheduler().runTaskLater(this, () -> 
                player.openInventory(event.getInventory()), 1L);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        if (!frozenPlayers.containsKey(player.getUniqueId())) return;
        if (!event.getInventory().getName().equals(guiTitle)) return;
        
        event.setCancelled(true);
        int clickedSlot = event.getRawSlot();
        
        if (clickedSlot == 8) {
            player.kickPlayer(leaveKickMessage);
            return;
        }
        
        for (ServerData server : servers) {
            if (server.slot == clickedSlot) {
                connectToServer(player, server.server);
                break;
            }
        }
    }

    private void connectToServer(Player player, String serverName) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeUTF("Connect");
            output.writeUTF(serverName);
            output.close();
            player.sendPluginMessage(this, "BungeeCord", stream.toByteArray());
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        frozenPlayers.remove(playerId);
        pendingCounts.remove(playerId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tufflobbyreload")) {
            if (!sender.isOp()) return true;
            
            reloadConfig();
            loadServers();
            sender.sendMessage("config reloaded");
            return true;
        }
        return false;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("BungeeCord")) return;
        
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(message));
            String subChannel = input.readUTF();
            
            if (subChannel.equals("PlayerCount")) {
                String serverName = input.readUTF();
                int playerCount = input.readInt();
                serverCounts.put(serverName, playerCount);
                
                UUID playerId = player.getUniqueId();
                if (pendingCounts.containsKey(playerId)) {
                    Set<String> pending = pendingCounts.get(playerId);
                    pending.remove(serverName);
                    
                    if (pending.isEmpty()) {
                        pendingCounts.remove(playerId);
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            if (player.isOnline() && frozenPlayers.containsKey(playerId)) {
                                openGui(player);
                            }
                        }, 1L);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private class ServerData {
        String name;
        Material material;
        String server;
        int slot;
        
        ServerData(String name, Material material, String server, int slot) {
            this.name = name;
            this.material = material;
            this.server = server;
            this.slot = slot;
        }
    }
}