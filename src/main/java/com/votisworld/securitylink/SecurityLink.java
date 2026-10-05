package com.votisworld.securitylink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SecurityLink extends JavaPlugin implements Listener {

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, String> pendingLinks = new HashMap<>();
    private final Set<String> approvedNicks = new HashSet<>();
    private Location hubLocation;
    private final String PYTHON_BOT_URL = "http://127.0.0.1:8080/api/mc_link";

    @Override
    public void onEnable() {
        loadApproved();
        hubLocation = new Location(Bukkit.getWorld("world"), 0.5, 100, 0.5);
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("link").setExecutor(new LinkCommand(this));
        startHttpServer();
        getLogger().info("SecurityLink включен!");
    }

    @Override
    public void onDisable() {
        saveApproved();
    }

    private void loadApproved() {
        File file = new File(getDataFolder(), "approved.txt");
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                approvedNicks.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveApproved() {
        File file = new File(getDataFolder(), "approved.txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String nick : approvedNicks) {
                bw.write(nick);
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String nick = player.getName();
        if (approvedNicks.contains(nick)) {
            return;
        }
        player.teleport(hubLocation);
        frozenPlayers.add(player.getUniqueId());
        player.sendMessage("§c§lВы не привязали аккаунт! Введите /link <ник> <код>, чтобы получить код в Telegram.");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            if (frozenPlayers.contains(event.getEntity().getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (frozenPlayers.contains(player.getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            if (!msg.startsWith("/link")) {
                event.setCancelled(true);
                player.sendMessage("§cВы должны привязать аккаунт! Используйте /link <ник> <код>");
            }
        }
    }

    private void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
            server.createContext("/api/approve", new ApproveHandler());
            server.setExecutor(null);
            server.start();
            getLogger().info("HTTP API сервер запущен на порту 8081");
        } catch (IOException e) {
            getLogger().severe("Не удалось запустить HTTP сервер: " + e.getMessage());
        }
    }

    class ApproveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseReader(isr).getAsJsonObject();
                String nick = json.get("nick").getAsString();

                Bukkit.getScheduler().runTask(SecurityLink.this, () -> {
                    Player player = Bukkit.getPlayer(nick);
                    if (player != null && player.isOnline()) {
                        approvedNicks.add(nick);
                        saveApproved();
                        frozenPlayers.remove(player.getUniqueId());
                        player.teleport(Bukkit.getWorld("world").getSpawnLocation());
                        player.sendMessage("§a§lАккаунт успешно привязан! Добро пожаловать на сервер.");
                    }
                });

                String response = "{\"status\":\"success\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }

    public class LinkCommand implements org.bukkit.command.CommandExecutor {
        private final SecurityLink plugin;
        public LinkCommand(SecurityLink plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (args.length < 2) {
                player.sendMessage("§cИспользование: /link <ник> <код>");
                return true;
            }

            String nick = args[0];
            String code = args[1];

            if (!nick.equals(player.getName())) {
                player.sendMessage("§cВы должны ввести свой ник!");
                return true;
            }

            player.sendMessage("§eОтправка кода на проверку...");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    URL url = new URL(PYTHON_BOT_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    String jsonInputString = "{\"nick\": \"" + nick + "\", \"code\": \"" + code + "\"}";

                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        player.sendMessage("§aКод отправлен! Проверьте Telegram и нажмите 'Принять'.");
                    } else {
                        player.sendMessage("§cОшибка: Неверный код или бот недоступен.");
                    }
                } catch (Exception e) {
                    player.sendMessage("§cОшибка соединения с ботом. Сообщите администрации.");
                    e.printStackTrace();
                }
            });

            return true;
        }
    }
}
