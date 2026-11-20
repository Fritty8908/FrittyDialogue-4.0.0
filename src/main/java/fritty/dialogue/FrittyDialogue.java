package fritty.dialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Main lifecycle and command entry point for FrittyDialogue. */
public final class FrittyDialogue extends JavaPlugin implements TabExecutor {
    public static final String VERSION = "4.0.0";
    public static final String ADMIN_PERMISSION = "frittydialogue.admin";

    private DialogueCore core;
    private DialogueRuntime runtime;
    private DialogueEditor editor;
    private boolean ready;

    @Override
    public void onEnable() {
        if (Runtime.version().feature() < 25) {
            getLogger().severe("FrittyDialogue requires Java 25 or newer.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs())
                throw new IllegalStateException("Could not create plugin data folder.");

            core = new DialogueCore(this);
            if (!core.initialize()) throw new IllegalStateException("DialogueCore initialization failed.");

            runtime = new DialogueRuntime(this, core);
            runtime.initialize();
            Bukkit.getPluginManager().registerEvents(runtime, this);

            editor = new DialogueEditor(this, core, runtime);
            // The editor registers its own listener inside initialize(). Do not register it twice.
            editor.initialize();

            PluginCommand command = Objects.requireNonNull(getCommand("frittydialogue"),
                    "Missing frittydialogue command in plugin.yml");
            command.setExecutor(this);
            command.setTabCompleter(this);
            ready = true;
            getLogger().info("FrittyDialogue " + VERSION + " enabled; loaded " + core.getDialogueCount() + " dialogue(s).");
        } catch (Exception ex) {
            getLogger().severe("FrittyDialogue failed to enable: " + ex.getMessage());
            ex.printStackTrace();
            shutdownComponents();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        ready = false;
        shutdownComponents();
        getLogger().info("FrittyDialogue disabled.");
    }

    private void shutdownComponents() {
        if (runtime != null) {
            runtime.shutdown();
            HandlerList.unregisterAll(runtime);
            runtime = null;
        }
        if (editor != null) {
            HandlerList.unregisterAll(editor);
            editor = null;
        }
        if (core != null) {
            core.shutdown();
            core = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!ready) {
            sender.sendMessage(Component.text("FrittyDialogue is not ready.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "edit" -> {
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Ця команда доступна лише гравцю."));
                    return true;
                }
                editor.openMainMenu(player, 0);
            }
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Використання: /fd start <діалог> [гравець]"));
                    return true;
                }
                Player target;
                if (args.length >= 3) {
                    if (!requireAdmin(sender)) return true;
                    target = Bukkit.getPlayerExact(args[2]);
                } else target = sender instanceof Player p ? p : null;
                if (target == null) sender.sendMessage(Component.text("Гравця не знайдено або не вказано."));
                else if (!runtime.start(target, args[1]))
                    sender.sendMessage(Component.text("Не вдалося запустити діалог.", NamedTextColor.RED));
            }
            case "stop" -> {
                Player target;
                if (args.length >= 2) {
                    if (!requireAdmin(sender)) return true;
                    target = Bukkit.getPlayerExact(args[1]);
                } else target = sender instanceof Player p ? p : null;
                if (target == null || !runtime.stop(target))
                    sender.sendMessage(Component.text("Для цього гравця немає активного діалогу."));
            }
            case "create" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Використання: /fd create <id> [копія]"));
                    return true;
                }
                String copyFrom = args.length >= 3 ? args[2] : null;
                if (core.createDialogue(args[1], copyFrom))
                    sender.sendMessage(Component.text("Діалог створено: " + args[1], NamedTextColor.GREEN));
                else sender.sendMessage(Component.text("Не вдалося створити діалог.", NamedTextColor.RED));
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                boolean success = args.length >= 2 && !args[1].equalsIgnoreCase("all")
                        ? core.reloadDialogue(args[1]) : core.reloadAll();
                sender.sendMessage(Component.text(success ? "Діалоги перезавантажено." : "Помилка перезавантаження.",
                        success ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "load-example" -> {
                if (!requireAdmin(sender)) return true;
                boolean success = core.writeExample();
                sender.sendMessage(Component.text(success ? "Приклад діалогу створено." : "Не вдалося створити приклад.",
                        success ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "set-camera-location" -> {
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player player) || args.length < 2) {
                    sender.sendMessage(Component.text("Використання: /fd set-camera-location <діалог>"));
                    return true;
                }
                Location feetLocation = player.getLocation().clone();
                boolean success = core.setCameraLocation(args[1], feetLocation);
                sender.sendMessage(Component.text(success ? "Позицію камери збережено." : "Не вдалося зберегти позицію камери.",
                        success ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) return true;
        sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
        return false;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("FrittyDialogue " + VERSION, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/fd edit — відкрити редактор"));
        sender.sendMessage(Component.text("/fd start <діалог> [гравець] — запустити діалог"));
        sender.sendMessage(Component.text("/fd stop [гравець] — зупинити діалог"));
        sender.sendMessage(Component.text("/fd create <id> [копія] — створити діалог"));
        sender.sendMessage(Component.text("/fd reload [id|all] — перезавантажити дані"));
        sender.sendMessage(Component.text("/fd set-camera-location <id> — зберегти позицію камери"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("edit", "start", "stop", "create", "reload", "load-example", "set-camera-location", "help")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("set-camera-location"))) {
            return core.getDialogueIds().stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            List<String> values = new ArrayList<>(core.getDialogueIds());
            values.add("all");
            return values.stream().filter(s -> s.toLowerCase(Locale.ROOT)
                    .startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
