
package fritty.dialogue;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * FrittyDialogue 4.0.0
 *
 * Target:
 * Minecraft 26.2
 * Paper 26.2
 * Java 25
 *
 * Main plugin class.
 *
 * The plugin is implemented using four main Java files:
 *
 * FrittyDialogue.java
 * DialogueCore.java
 * DialogueRuntime.java
 * DialogueEditor.java
 *
 * No external camera plugin is required.
 */
public final class FrittyDialogue extends JavaPlugin
        implements TabExecutor {

    public static final String VERSION = "4.0.0";

    public static final String ADMIN_PERMISSION =
            "frittydialogue.admin";

    private static FrittyDialogue instance;

    private DialogueCore core;
    private DialogueRuntime runtime;
    private DialogueEditor editor;

    private boolean initialized;
    private boolean shuttingDown;

    /*
     * ============================================================
     * LIFECYCLE
     * ============================================================
     */

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("Starting FrittyDialogue " + VERSION);
        getLogger().info("Target: Paper 26.2 / Java 25");

        if (!validateEnvironment()) {
            disablePlugin();
            return;
        }

        if (!getDataFolder().exists()
                && !getDataFolder().mkdirs()) {

            getLogger().severe(
                    "Could not create plugin data directory."
            );

            disablePlugin();
            return;
        }

        try {
            initializeCore();
            initializeRuntime();
            initializeEditor();

            registerListeners();
            registerCommands();

            initialized = true;

            getLogger().info(
                    "FrittyDialogue successfully enabled."
            );

            getLogger().info(
                    "Loaded dialogues: " + core.getDialogueCount()
            );

            getLogger().info(
                    "Citizens integration: "
                            + (isPluginEnabled("Citizens")
                            ? "available" : "unavailable")
            );

            getLogger().info(
                    "LuckPerms integration: "
                            + (isPluginEnabled("LuckPerms")
                            ? "available" : "unavailable")
            );

        } catch (Exception exception) {
            getLogger().severe(
                    "FrittyDialogue initialization failed."
            );

            exception.printStackTrace();

            shutdownComponents();
            disablePlugin();
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;

        shutdownComponents();

        initialized = false;

        if (instance == this) {
            instance = null;
        }

        getLogger().info("FrittyDialogue disabled.");
    }

    /*
     * ============================================================
     * ENVIRONMENT
     * ============================================================
     */

    private boolean validateEnvironment() {
        String version = Bukkit.getMinecraftVersion();

        if (!"26.2".equals(version)) {
            getLogger().severe(
                    "This build targets Minecraft 26.2. "
                            + "Detected: " + version
            );

            return false;
        }

        int javaVersion = Runtime.version().feature();

        if (javaVersion < 25) {
            getLogger().severe(
                    "Java 25 or newer is required. Detected: "
                            + javaVersion
            );

            return false;
        }

        return true;
    }

    private boolean isPluginEnabled(String name) {
        org.bukkit.plugin.Plugin plugin =
                Bukkit.getPluginManager().getPlugin(name);

        return plugin != null && plugin.isEnabled();
    }

    /*
     * ============================================================
     * COMPONENT INITIALIZATION
     * ============================================================
     */

    private void initializeCore() {
        core = new DialogueCore(this);

        if (!core.initialize()) {
            throw new IllegalStateException(
                    "DialogueCore could not initialize."
            );
        }

        getLogger().info("DialogueCore initialized.");
    }

    private void initializeRuntime() {
        runtime = new DialogueRuntime(
                this,
                core
        );

        runtime.initialize();

        getLogger().info("DialogueRuntime initialized.");
    }

    private void initializeEditor() {
        editor = new DialogueEditor(
                this,
                core,
                runtime
        );

        editor.initialize();

        getLogger().info("DialogueEditor initialized.");
    }

    /*
     * ============================================================
     * LISTENERS
     * ============================================================
     */

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                runtime,
                this
        );

        Bukkit.getPluginManager().registerEvents(
                editor,
                this
        );
    }

    /*
     * ============================================================
     * COMMAND REGISTRATION
     * ============================================================
     */

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("frittydialogue"),
                "Command frittydialogue is missing from plugin.yml"
        );

        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    /*
     * ============================================================
     * COMMAND EXECUTION
     * ============================================================
     */

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!initialized || shuttingDown) {
            sender.sendMessage(
                    Component.text(
                            "FrittyDialogue is not ready."
                    )
            );

            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "edit" -> commandEdit(sender);

            case "start" -> commandStart(sender, args);

            case "stop" -> commandStop(sender, args);

            case "create" -> commandCreate(sender, args);

            case "reload" -> commandReload(sender, args);

            case "load-example" -> commandLoadExample(sender);

            case "set-camera-location" ->
                    commandSetCameraLocation(sender, args);

            case "help" -> sendHelp(sender);

            default -> sendHelp(sender);
        }

        return true;
    }

    /*
     * ============================================================
     * EDIT
     * ============================================================
     */

    private void commandEdit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    Component.text(
                            "This command requires a player."
                    )
            );

            return;
        }

        if (!hasAdminPermission(sender)) {
            sendNoPermission(sender);
            return;
        }

        editor.openMainMenu(player, 0);
    }

    /*
     * ============================================================
     * START
     * ============================================================
     */

    private void commandStart(
            CommandSender sender,
            String[] args
    ) {
        if (args.length < 2) {
            sender.sendMessage(
                    Component.text(
                            "Usage: /fd start <dialogue> [player]"
                    )
            );

            return;
        }

        Player target;

        if (args.length >= 3) {
            if (!hasAdminPermission(sender)) {
                sendNoPermission(sender);
                return;
            }

            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(
                    Component.text(
                            "Specify a player: /fd start <dialogue> <player>"
                    )
            );

            return;
        }

        if (target == null) {
            sender.sendMessage(
                    Component.text("Player not found.")
            );

            return;
        }

        if (!runtime.start(target, args[1])) {
            sender.sendMessage(
                    Component.text(
                            "Could not start dialogue: " + args[1]
                    )
            );
        }
    }

    /*
     * ============================================================
     * STOP
     * ============================================================
     */

    private void commandStop(
            CommandSender sender,
            String[] args
    ) {
        Player target;

        if (args.length >= 2) {
            if (!hasAdminPermission(sender)) {
                sendNoPermission(sender);
                return;
            }

            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(
                    Component.text(
                            "Specify a player: /fd stop <player>"
                    )
            );

            return;
        }

        if (target == null) {
            sender.sendMessage(
                    Component.text("Player not found.")
            );

            return;
        }

        if (!runtime.stop(target)) {
            sender.sendMessage(
                    Component.text(
                            "The player has no active dialogue."
                    )
            );
        }
    }

    /*
     * ============================================================
     * CREATE
     * ============================================================
     */

    private void commandCreate(
            CommandSender sender,
            String[] args
    ) {
        if (!hasAdminPermission(sender)) {
            sendNoPermission(sender);
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(
                    Component.text(
                            "Usage: /fd create <name> [copy-from]"
                    )
            );

            return;
        }

        String name = args[1];

        String copyFrom = args.length >= 3
                ? args[2]
                : null;

        if (!core.createDialogue(name, copyFrom)) {
            sender.sendMessage(
                    Component.text(
                            "Could not create dialogue: " + name
                    )
            );

            return;
        }

        sender.sendMessage(
                Component.text(
                        "Dialogue created: " + name
                )
        );
    }

    /*
     * ============================================================
     * RELOAD
     * ============================================================
     */

    private void commandReload(
            CommandSender sender,
            String[] args
    ) {
        if (!hasAdminPermission(sender)) {
            sendNoPermission(sender);
            return;
        }

        boolean success;

        if (args.length >= 2
                && !args[1].equalsIgnoreCase("all")) {

            success = core.reloadDialogue(args[1]);

        } else {
            success = core.reloadAll();
        }

        sender.sendMessage(
                Component.text(
                        success
                                ? "FrittyDialogue reloaded."
                                : "Reload failed. Check the console."
                )
        );
    }

    /*
     * ============================================================
     * EXAMPLE
     * ============================================================
     */

    private void commandLoadExample(CommandSender sender) {
        if (!hasAdminPermission(sender)) {
            sendNoPermission(sender);
            return;
        }

        boolean success = core.writeExample();

        sender.sendMessage(
                Component.text(
                        success
                                ? "Example dialogue created."
                                : "Could not create example dialogue."
                )
        );
    }

    /*
     * ============================================================
     * CAMERA LOCATION
     * ============================================================
     *
     * Important:
     *
     * Camera location is captured from the player's FEET.
     *
     * It is deliberately NOT getEyeLocation().
     *
     * The saved location includes:
     * X, Y, Z, yaw, pitch and world.
     *
     * The editor's camera-location button must use
     * exactly the same coordinate rule.
     */

    private void commandSetCameraLocation(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    Component.text(
                            "This command requires a player."
                    )
            );

            return;
        }

        if (!hasAdminPermission(sender)) {
            sendNoPermission(sender);
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(
                    Component.text(
                            "Usage: /fd set-camera-location <dialogue>"
                    )
            );

            return;
        }

        Location cameraLocation = player.getLocation().clone();

        boolean success = core.setCameraLocation(
                args[1],
                cameraLocation
        );

        if (success) {
            sender.sendMessage(
                    Component.text(
                            "Camera location saved for " + args[1]
                    )
            );
        } else {
            sender.sendMessage(
                    Component.text(
                            "Could not save camera location."
                    )
            );
        }
    }

    /*
     * ============================================================
     * TAB COMPLETION
     * ============================================================
     */

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return filter(
                    List.of(
                            "edit",
                            "start",
                            "stop",
                            "create",
                            "reload",
                            "load-example",
                            "set-camera-location",
                            "help"
                    ),
                    args[0]
            );
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("start")
                    || sub.equals("reload")
                    || sub.equals("set-camera-location")) {

                if (sub.equals("start")
                        || sender.hasPermission(ADMIN_PERMISSION)) {

                    return filter(
                            core.getDialogueIds(),
                            args[1]
                    );
                }
            }

            if (sub.equals("stop")
                    && sender.hasPermission(ADMIN_PERMISSION)) {

                return filter(
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .toList(),
                        args[1]
                );
            }
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("start")
                && sender.hasPermission(ADMIN_PERMISSION)) {

            return filter(
                    Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .toList(),
                    args[2]
            );
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("create")
                && sender.hasPermission(ADMIN_PERMISSION)) {

            return filter(
                    core.getDialogueIds(),
                    args[2]
            );
        }

        return Collections.emptyList();
    }

    private List<String> filter(
            List<String> values,
            String input
    ) {
        String search = input.toLowerCase(Locale.ROOT);

        List<String> result = new ArrayList<>();

        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT)
                    .startsWith(search)) {

                result.add(value);
            }
        }

        return result;
    }

    /*
     * ============================================================
     * MESSAGES
     * ============================================================
     */

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION);
    }

    private void sendNoPermission(CommandSender sender) {
        sender.sendMessage(
                Component.text(
                        "You do not have permission to use this command."
                )
        );
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(
                Component.text("FrittyDialogue " + VERSION)
        );

        sender.sendMessage(
                Component.text("/fd edit")
        );

        sender.sendMessage(
                Component.text("/fd start <dialogue> [player]")
        );

        sender.sendMessage(
                Component.text("/fd stop [player]")
        );

        sender.sendMessage(
                Component.text("/fd create <name> [copy-from]")
        );

        sender.sendMessage(
                Component.text("/fd reload [dialogue]")
        );

        sender.sendMessage(
                Component.text("/fd load-example")
        );

        sender.sendMessage(
                Component.text("/fd set-camera-location <dialogue>")
        );
    }

    /*
     * ============================================================
     * SHUTDOWN
     * ============================================================
     */

    private void shutdownComponents() {
        if (editor != null) {
            try {
                editor.shutdown();
            } catch (Exception exception) {
                getLogger().severe(
                        "DialogueEditor shutdown failed."
                );

                exception.printStackTrace();
            }
        }

        if (runtime != null) {
            try {
                runtime.shutdown();
            } catch (Exception exception) {
                getLogger().severe(
                        "DialogueRuntime shutdown failed."
                );

                exception.printStackTrace();
            }
        }

        if (core != null) {
            try {
                core.shutdown();
            } catch (Exception exception) {
                getLogger().severe(
                        "DialogueCore shutdown failed."
                );

                exception.printStackTrace();
            }
        }

        editor = null;
        runtime = null;
        core = null;
    }

    private void disablePlugin() {
        Bukkit.getPluginManager().disablePlugin(this);
    }

    /*
     * ============================================================
     * ACCESSORS
     * ============================================================
     */

    public static FrittyDialogue getInstance() {
        return instance;
    }

    public DialogueCore getCore() {
        return core;
    }

    public DialogueRuntime getRuntime() {
        return runtime;
    }

    public DialogueEditor getEditor() {
        return editor;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
