package fritty.dialogue;

import fritty.dialogue.DialogueCore.Answer;
import fritty.dialogue.DialogueCore.Condition;
import fritty.dialogue.DialogueCore.ConditionGroup;
import fritty.dialogue.DialogueCore.Dialogue;
import fritty.dialogue.DialogueCore.Node;
import fritty.dialogue.DialogueCore.Reward;
import fritty.dialogue.DialogueCore.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Executes dialogue graphs created by DialogueEditor and stored by DialogueCore.
 * All Bukkit-facing work is performed on the server thread.
 */
public final class DialogueRuntime implements Listener {

    private static final int CAMERA_TRANSITION_TICKS = 12;
    private static final double CAMERA_POSITION_EPSILON_SQUARED = 0.0001;
    private static final float CAMERA_ROTATION_EPSILON = 0.15f;

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private final FrittyDialogue plugin;
    private final DialogueCore core;
    private final Map<UUID, DialogueSession> sessions = new HashMap<>();

    private boolean initialized;
    private boolean shuttingDown;

    public DialogueRuntime(FrittyDialogue plugin, DialogueCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    // ---------------------------------------------------------------------
    // Lifecycle and session access
    // ---------------------------------------------------------------------

    public void initialize() {
        if (initialized) return;
        shuttingDown = false;
        initialized = true;
        plugin.getLogger().info("DialogueRuntime initialized.");
    }

    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;

        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                stop(player);
            } else {
                removeOfflineSession(playerId);
            }
        }

        initialized = false;
    }

    public boolean isRunning(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    public DialogueSession getSession(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    // ---------------------------------------------------------------------
    // Start and player preparation
    // ---------------------------------------------------------------------

    public boolean start(Player player, String dialogueId) {
        if (!initialized || shuttingDown || player == null || !player.isOnline()) {
            return false;
        }

        if (isRunning(player)) {
            stop(player);
        }

        Dialogue dialogue = core.getDialogue(dialogueId);
        if (dialogue == null) {
            player.sendMessage(Component.text("Діалог не знайдено.", NamedTextColor.RED));
            return false;
        }

        if (dialogue.getSettings().getBoolean("one-time", false)
                && core.isDialogueCompleted(player.getUniqueId(), dialogue.getId())) {
            player.sendMessage(Component.text(
                    "Цей діалог можна пройти лише один раз.", NamedTextColor.RED));
            return false;
        }

        Node startNode = resolveStartingNode(player, dialogue);
        if (startNode == null) {
            player.sendMessage(Component.text(
                    "Початкову гілку не знайдено або вона недоступна.", NamedTextColor.RED));
            return false;
        }

        Location originalLocation = player.getLocation().clone();
        DialogueSession session = new DialogueSession(
                player.getUniqueId(), dialogue, originalLocation);
        sessions.put(player.getUniqueId(), session);

        try {
            preparePlayer(player, session);
            session.setCurrentNode(startNode);
            displayCurrentNode(player, session, true);
            startCameraTask(player, session);
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not start dialogue '" + dialogueId + "' for " + player.getName(), exception);
            stop(player);
            return false;
        }
    }

    private Node resolveStartingNode(Player player, Dialogue dialogue) {
        String savedStage = core.getCurrentStoryStage(player.getUniqueId(), dialogue.getId());
        if (savedStage != null && !savedStage.isBlank()
                && !savedStage.equalsIgnoreCase("completed")) {
            Node savedNode = dialogue.getNode(savedStage);
            if (savedNode != null && isNodeAvailable(player, dialogue, savedNode)) {
                return savedNode;
            }
        }

        Node root = core.getRootNode(dialogue.getId());
        return root != null && isNodeAvailable(player, dialogue, root) ? root : null;
    }

    private void preparePlayer(Player player, DialogueSession session) {
        session.setPreviousAllowFlight(player.getAllowFlight());
        session.setPreviousFlying(player.isFlying());
        session.setPreviousWalkSpeed(player.getWalkSpeed());
        session.setPreviousFlySpeed(player.getFlySpeed());
        session.setPreviousGravity(player.hasGravity());

        session.setPreviousInvisible(player.isInvisible());

        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setGravity(false);

        if (session.getDialogue().getSettings().getBoolean("invisibility", false)) {
            player.setInvisible(true);
        }

        if (session.getDialogue().getSettings().getBoolean("hide-players", false)) {
            session.setHidePlayers(true);
            hideOtherPlayers(player, session);
        }
    }

    private void hideOtherPlayers(Player viewer, DialogueSession session) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer) || !viewer.canSee(other)) continue;
            viewer.hidePlayer(plugin, other);
            session.getHiddenPlayers().add(other.getUniqueId());
        }
    }

    // ---------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------

    private void setCameraTarget(Player player, DialogueSession session, Settings settings,
                                 boolean initial) {
        Location target = settings == null ? null : settings.getCameraLocation();
        if (target == null || target.getWorld() == null) {
            target = session.getOriginalLocation();
        }
        target = target.clone();

        Location current = player.getLocation().clone();
        boolean smooth = settings != null && settings.getBoolean("smooth-camera", false);
        session.setCameraLocation(target);

        if (smooth && current.getWorld() == target.getWorld()
                && current.distanceSquared(target) > CAMERA_POSITION_EPSILON_SQUARED) {
            session.setCameraTransitionStart(current);
            session.setCameraTransitionTick(0);
        } else {
            session.setCameraTransitionStart(null);
            player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    private void startCameraTask(Player player, DialogueSession session) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (sessions.get(playerId) != session) {
                cancelCameraTask(session);
                return;
            }
            if (!player.isOnline()) {
                removeOfflineSession(playerId);
                return;
            }
            maintainCamera(player, session);
        }, 1L, 1L);
        session.setCameraTask(task);
    }

    private void maintainCamera(Player player, DialogueSession session) {
        Location target = session.getCameraLocation();
        if (target == null || target.getWorld() == null) return;

        Location transitionStart = session.getCameraTransitionStart();
        if (transitionStart != null) {
            int tick = session.getCameraTransitionTick() + 1;
            session.setCameraTransitionTick(tick);
            double progress = Math.min(1.0, (double) tick / CAMERA_TRANSITION_TICKS);

            if (transitionStart.getWorld() != target.getWorld()) {
                player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
                session.setCameraTransitionStart(null);
            } else {
                Location interpolated = interpolate(transitionStart, target, progress);
                player.teleport(interpolated, PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (progress >= 1.0) session.setCameraTransitionStart(null);
            }
        } else {
            Location current = player.getLocation();
            boolean wrongWorld = current.getWorld() != target.getWorld();
            boolean wrongPosition = wrongWorld
                    || current.distanceSquared(target) > CAMERA_POSITION_EPSILON_SQUARED;
            boolean wrongRotation = Math.abs(wrapDegrees(current.getYaw() - target.getYaw()))
                    > CAMERA_ROTATION_EPSILON
                    || Math.abs(current.getPitch() - target.getPitch()) > CAMERA_ROTATION_EPSILON;

            if (wrongPosition || wrongRotation) {
                player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }

        player.setVelocity(new Vector(0, 0, 0));
    }

    private Location interpolate(Location from, Location to, double amount) {
        double x = from.getX() + (to.getX() - from.getX()) * amount;
        double y = from.getY() + (to.getY() - from.getY()) * amount;
        double z = from.getZ() + (to.getZ() - from.getZ()) * amount;
        float yaw = (float) (from.getYaw() + wrapDegrees(to.getYaw() - from.getYaw()) * amount);
        float pitch = (float) (from.getPitch() + (to.getPitch() - from.getPitch()) * amount);
        return new Location(to.getWorld(), x, y, z, yaw, pitch);
    }

    private float wrapDegrees(float angle) {
        float result = angle % 360.0f;
        if (result >= 180.0f) result -= 360.0f;
        if (result < -180.0f) result += 360.0f;
        return result;
    }

    // ---------------------------------------------------------------------
    // Node display and typewriter
    // ---------------------------------------------------------------------

    private void displayCurrentNode(Player player, DialogueSession session, boolean initial) {
        cancelNodeTasks(session);
        clearDisplays(session);

        Node node = session.getCurrentNode();
        if (node == null) {
            stop(player);
            return;
        }

        Settings settings = core.getEffectiveSettings(session.getDialogue(), node);
        session.setCurrentSettings(settings);
        setCameraTarget(player, session, settings, initial);
        core.setCurrentStoryStage(player.getUniqueId(), session.getDialogue().getId(), node.getId());

        String text = settings.getString("text", node.getName());
        Location textLocation = resolveTextLocation(player, session, settings);
        TextDisplay textDisplay = createTextDisplay(player, textLocation, text, settings);
        session.addDisplay(textDisplay);
        session.setTextDisplay(textDisplay);

        session.setSelectedAnswer(findFirstAvailableAnswer(player, session));
        session.setReadyForInput(false);
        startTyping(player, session, text, settings);
    }

    private Location resolveTextLocation(Player player, DialogueSession session, Settings settings) {
        Object configured = settings.get("text-location");
        Location parsed = parseLocation(configured);
        if (parsed != null && parsed.getWorld() != null) return parsed;

        Location camera = session.getCameraLocation();
        if (camera == null || camera.getWorld() == null) camera = player.getLocation();
        return camera.clone().add(0, player.getEyeHeight(), 0)
                .add(camera.getDirection().multiply(2.0));
    }

    private Location parseLocation(Object value) {
        if (value instanceof Location location) return location.clone();
        if (value instanceof Map<?, ?> map) {
            Object worldValue = map.get("world");
            World world = worldValue == null ? null : Bukkit.getWorld(String.valueOf(worldValue));
            if (world == null) return null;
            return new Location(world,
                    number(map.get("x"), 0),
                    number(map.get("y"), 0),
                    number(map.get("z"), 0),
                    (float) number(map.get("yaw"), 0),
                    (float) number(map.get("pitch"), 0));
        }
        if (!(value instanceof String string) || string.isBlank()) return null;

        String[] parts = string.trim().split(",");
        if (parts.length < 4) return null;
        World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) return null;
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private TextDisplay createTextDisplay(Player viewer, Location location,
                                          String text, Settings settings) {
        World world = location.getWorld();
        if (world == null) throw new IllegalStateException("Text display location has no world.");

        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(renderText(text, settings));
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setSeeThrough(settings.getBoolean("see-through-blocks", false));
            entity.setShadowed(settings.getBoolean("text-shadow", true));
            entity.setDefaultBackground(settings.getBoolean("default-background", false));
            entity.setBackgroundColor(parseBackground(settings));
            entity.setLineWidth(Math.max(1, Math.min(1000,
                    settings.getInt("window-width", settings.getInt("text-width", 200)))));
            entity.setViewRange((float) clamp(settings.getDouble("view-range", 32.0), 1.0, 128.0));
            entity.setTransformation(createTransformation(settings));
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
        });

        viewer.showEntity(plugin, display);
        return display;
    }

    private void startTyping(Player player, DialogueSession session, String fullText, Settings settings) {
        int[] codePoints = fullText == null ? new int[0] : fullText.codePoints().toArray();
        session.setTypewriterText(fullText == null ? "" : fullText);
        session.setTypewriterCodePoints(codePoints);
        session.setTypewriterIndex(0);

        boolean enabled = settings.getBoolean("character-printing", true);
        double charactersPerTick = settings.getDouble("typing-speed", 1.0);
        if (!enabled || codePoints.length == 0 || !Double.isFinite(charactersPerTick)
                || charactersPerTick <= 0.0) {
            setDisplayText(session, session.getTypewriterText(), settings);
            session.setTyping(false);
            afterTyping(player, session);
            return;
        }

        session.setTyping(true);
        session.setTypingAccumulator(0.0);
        updateActionBar(player, session);
        double typingRate = clamp(charactersPerTick, 0.05, 64.0);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
                cancelTypeTask(session);
                return;
            }

            double accumulator = session.getTypingAccumulator() + typingRate;
            int charactersToPrint = (int) Math.floor(accumulator);
            session.setTypingAccumulator(accumulator - charactersToPrint);
            if (charactersToPrint <= 0) return;

            int next = Math.min(session.getTypewriterCodePoints().length,
                    session.getTypewriterIndex() + charactersToPrint);
            session.setTypewriterIndex(next);
            String partial = new String(session.getTypewriterCodePoints(), 0, next);
            setDisplayText(session, partial, session.getCurrentSettings());
            playTypingSound(player, session.getCurrentSettings());

            if (next >= session.getTypewriterCodePoints().length) {
                cancelTypeTask(session);
                session.setTyping(false);
                afterTyping(player, session);
            }
        }, 1L, 1L);
        session.setTypeTask(task);
    }

    private void setDisplayText(DialogueSession session, String text, Settings settings) {
        TextDisplay display = session.getTextDisplay();
        if (display != null && display.isValid()) display.text(renderText(text, settings));
    }

    private Component renderText(String text, Settings settings) {
        Component component = LEGACY.deserialize(text == null ? "" : text);
        TextColor color = parseTextColor(settings.getString("text-color", "#FFFFFF"));
        return color == null ? component : component.color(color);
    }

    private void afterTyping(Player player, DialogueSession session) {
        if (sessions.get(player.getUniqueId()) != session) return;
        Node node = session.getCurrentNode();
        if (node == null) return;

        if (node.getAnswers().isEmpty()) {
            session.setReadyForInput(true);
            updateActionBar(player, session);
            return;
        }

        int delay = Math.max(0, session.getCurrentSettings().getInt("delay", 0));
        if (delay == 0) {
            showAnswers(player, session);
            return;
        }

        session.setReadyForInput(false);
        updateActionBar(player, session);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) == session && player.isOnline()) {
                showAnswers(player, session);
            }
        }, delay);
        session.setAnswerDelayTask(task);
    }

    private void showAnswers(Player player, DialogueSession session) {
        if (sessions.get(player.getUniqueId()) != session) return;
        session.setReadyForInput(true);
        createAnswerDisplays(player, session);
        updateActionBar(player, session);
    }

    private void cancelNodeTasks(DialogueSession session) {
        cancelTypeTask(session);
        BukkitTask delayTask = session.getAnswerDelayTask();
        if (delayTask != null) {
            delayTask.cancel();
            session.setAnswerDelayTask(null);
        }
    }

    private void cancelTypeTask(DialogueSession session) {
        BukkitTask task = session.getTypeTask();
        if (task != null) {
            task.cancel();
            session.setTypeTask(null);
        }
    }

    // ---------------------------------------------------------------------
    // Answer displays and selection
    // ---------------------------------------------------------------------

    private void createAnswerDisplays(Player player, DialogueSession session) {
        clearAnswerDisplays(session);
        Node node = session.getCurrentNode();
        Settings settings = session.getCurrentSettings();
        if (node == null || settings == null || node.getAnswers().isEmpty()) return;

        List<Answer> answers = node.getAnswers();
        Location base = resolveTextLocation(player, session, settings);
        double spacing = clamp(settings.getDouble("answer-spacing", 0.35), 0.05, 5.0);
        double startOffset = -((answers.size() - 1) * spacing) / 2.0;

        for (int index = 0; index < answers.size(); index++) {
            Answer answer = answers.get(index);
            boolean available = isAnswerAvailable(player, session, answer);
            String unavailableMode = answer.getSettings().getString(
                    "unavailable-answer-mode",
                    settings.getString("unavailable-answer-mode", "HIDE"));
            if (!available && unavailableMode.equalsIgnoreCase("HIDE")) continue;

            boolean selected = available && index == session.getSelectedAnswer();
            String prefix = selected
                    ? settings.getString("answer-selected-prefix", "» ")
                    : settings.getString("answer-prefix", "  ");
            String suffix = selected
                    ? settings.getString("answer-selected-suffix", " «")
                    : "";
            String text = prefix + answer.getText() + suffix;

            Settings answerSettings = settings.copy();
            answerSettings.applyOverrides(answer.getSettings());
            if (!available) {
                if (unavailableMode.equalsIgnoreCase("DISABLED")) {
                    text = settings.getString("unavailable-answer-prefix", "§8")
                            + answer.getText();
                } else {
                    answerSettings.set("text-color", "#777777");
                }
            }

            Location answerLocation = base.clone().add(
                    0,
                    startOffset - ((index + 1) * spacing),
                    0
            );
            answerLocation.add(parseOffset(answer.getSettings().getString("offset", "0 0 0")));

            TextDisplay display = createTextDisplay(player, answerLocation, text, answerSettings);
            session.addDisplay(display);
            session.addAnswerDisplay(display);
        }
    }

    private Vector parseOffset(String value) {
        if (value == null || value.isBlank()) return new Vector(0, 0, 0);
        String[] parts = value.trim().replace(',', ' ').split("\\s+");
        if (parts.length != 3) return new Vector(0, 0, 0);
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return new Vector(0, 0, 0);
            }
            return new Vector(x, y, z);
        } catch (NumberFormatException exception) {
            return new Vector(0, 0, 0);
        }
    }

    private int findFirstAvailableAnswer(Player player, DialogueSession session) {
        List<Answer> answers = session.getCurrentNode().getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            if (isAnswerAvailable(player, session, answers.get(i))) return i;
        }
        return -1;
    }

    private boolean hasAvailableAnswers(Player player, DialogueSession session) {
        Node node = session.getCurrentNode();
        if (node == null) return false;
        for (Answer answer : node.getAnswers()) {
            if (isAnswerAvailable(player, session, answer)) return true;
        }
        return false;
    }

    private boolean isAnswerAvailable(Player player, DialogueSession session, Answer answer) {
        if (answer == null || !answer.isEnabled()) return false;
        if (!evaluateConditionGroup(player, session.getDialogue(), session.getCurrentNode(),
                answer.getConditions())) return false;

        String targetId = answer.getTargetNode();
        if (targetId == null || targetId.isBlank()) {
            targetId = session.getCurrentNode().getNextNode();
        }
        if (targetId != null && !targetId.isBlank()) {
            Node target = session.getDialogue().getNode(targetId);
            return target != null && isNodeAvailable(player, session.getDialogue(), target);
        }
        return true;
    }

    private boolean isNodeAvailable(Player player, Dialogue dialogue, Node node) {
        return node != null && evaluateConditionGroup(
                player, dialogue, node, node.getConditionGroup());
    }

    private boolean evaluateConditionGroup(Player player, Dialogue dialogue, Node currentNode,
                                           ConditionGroup group) {
        if (group == null || group.isEmpty()) return true;
        Collection<Condition> conditions = group.getConditions();
        if (conditions.isEmpty()) return true;

        boolean or = group.getLogic().equalsIgnoreCase("OR");
        for (Condition condition : conditions) {
            boolean result = evaluateCondition(player, dialogue, currentNode, condition);
            if (or && result) return true;
            if (!or && !result) return false;
        }
        return !or;
    }

    private boolean evaluateCondition(Player player, Dialogue dialogue, Node currentNode,
                                      Condition condition) {
        if (condition == null) return false;
        String type = condition.getType().name().toLowerCase(Locale.ROOT);
        String value = condition.getValue() == null ? "" : condition.getValue().trim();
        String operator = condition.getOperator() == null
                ? "equals" : condition.getOperator().trim().toLowerCase(Locale.ROOT);

        return switch (type) {
            case "permission" -> compareBoolean(player.hasPermission(value), operator);
            case "tag" -> compareBoolean(player.getScoreboardTags().contains(value), operator);
            case "dialogue_completed" -> compareBoolean(
                    core.isDialogueCompleted(player.getUniqueId(), value), operator);
            case "branch_completed", "completed_branch" -> compareBoolean(
                    isBranchCompleted(player, dialogue, value), operator);
            case "answer_selected" -> compareBoolean(
                    isAnswerSelected(player, dialogue, currentNode, value), operator);
            case "item" -> compareBoolean(hasMaterial(player, value), operator);
            case "minecraft_tag" -> compareBoolean(hasItemTag(player, value), operator);
            case "luckperms_group" -> compareBoolean(hasLuckPermsGroup(player, value), operator);
            case "level" -> compareNumber(player.getLevel(), value, operator);
            default -> false;
        };
    }

    private boolean isBranchCompleted(Player player, Dialogue dialogue, String value) {
        if (value == null || value.isBlank()) return false;
        if (value.contains(":")) {
            return core.isBranchCompleted(player.getUniqueId(), value);
        }
        return core.isBranchCompleted(player.getUniqueId(), dialogue.getId(), value);
    }

    private boolean isAnswerSelected(Player player, Dialogue dialogue, Node currentNode,
                                     String value) {
        if (value == null || value.isBlank()) return false;
        int separator = value.indexOf(':');
        if (separator > 0 && separator < value.length() - 1) {
            return core.wasAnswerSelected(player.getUniqueId(), dialogue.getId(),
                    value.substring(0, separator), value.substring(separator + 1));
        }
        return currentNode != null && core.wasAnswerSelected(
                player.getUniqueId(), dialogue.getId(), currentNode.getId(), value);
    }

    private boolean compareBoolean(boolean actual, String operator) {
        return switch (operator) {
            case "not", "not_equals", "!=", "is_not" -> !actual;
            default -> actual;
        };
    }

    private boolean compareNumber(double actual, String expected, String operator) {
        try {
            double number = Double.parseDouble(expected);
            return switch (operator) {
                case "greater", ">" -> actual > number;
                case "greater_or_equal", ">=" -> actual >= number;
                case "less", "<" -> actual < number;
                case "less_or_equal", "<=" -> actual <= number;
                case "not_equals", "!=", "not" -> actual != number;
                default -> actual == number;
            };
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean hasMaterial(Player player, String materialName) {
        Material material = Material.matchMaterial(materialName);
        if (material == null && materialName != null) {
            String normalized = materialName.toUpperCase(Locale.ROOT);
            if (normalized.startsWith("MINECRAFT:")) normalized = normalized.substring(10);
            try { material = Material.valueOf(normalized); }
            catch (IllegalArgumentException ignored) { return false; }
        }
        return material != null && player.getInventory().contains(material);
    }

    private boolean hasItemTag(Player player, String input) {
        if (input == null || input.isBlank()) return false;
        String value = input.trim();
        if (value.startsWith("#")) value = value.substring(1);
        NamespacedKey key;
        try {
            key = NamespacedKey.fromString(value);
            if (key == null && !value.contains(":")) key = NamespacedKey.minecraft(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (key == null) return false;

        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        if (tag == null) return false;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir() && tag.isTagged(item.getType())) return true;
        }
        org.bukkit.inventory.ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir() && tag.isTagged(offHand.getType())) return true;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir() && tag.isTagged(item.getType())) return true;
        }
        return false;
    }

    private boolean hasLuckPermsGroup(Player player, String groupName) {
        if (groupName == null || groupName.isBlank()
                || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return false;
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) return false;
            Optional<QueryOptions> queryOptions = luckPerms.getContextManager().getQueryOptions(user);
            QueryOptions options = queryOptions.orElseGet(
                    () -> luckPerms.getContextManager().getStaticQueryOptions());
            String expected = groupName.trim();
            if (user.getPrimaryGroup().equalsIgnoreCase(expected)) return true;
            return user.getInheritedGroups(options).stream()
                    .anyMatch(group -> group.getName().equalsIgnoreCase(expected));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE,
                    "Could not evaluate LuckPerms group condition for " + player.getName(), exception);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Input: W / S / F
    // ---------------------------------------------------------------------

    public boolean selectAnswer(Player player, int index) {
        DialogueSession session = getSession(player);
        if (session == null || session.isTyping() || !session.isReadyForInput()) return false;
        List<Answer> answers = session.getCurrentNode().getAnswers();
        if (index < 0 || index >= answers.size() || !isAnswerAvailable(player, session, answers.get(index))) {
            playSound(player, session.getCurrentSettings().getString(
                    "unavailable-answer-sound", "BLOCK_NOTE_BLOCK_BASS"), 1.0f, 1.0f);
            return false;
        }
        session.setSelectedAnswer(index);
        refreshAnswerDisplays(player, session);
        return true;
    }

    /** direction < 0 selects the previous (upper) answer; direction > 0 selects the next (lower) answer. */
    public boolean moveSelection(Player player, int direction) {
        DialogueSession session = getSession(player);
        if (session == null || session.isTyping() || !session.isReadyForInput()) return false;
        List<Answer> answers = session.getCurrentNode().getAnswers();
        if (answers.isEmpty()) return false;

        int current = session.getSelectedAnswer();
        int step = direction < 0 ? -1 : 1;
        for (int i = 0; i < answers.size(); i++) {
            current = (current + step + answers.size()) % answers.size();
            if (isAnswerAvailable(player, session, answers.get(current))) {
                session.setSelectedAnswer(current);
                refreshAnswerDisplays(player, session);
                return true;
            }
        }
        return false;
    }

    public boolean confirmSelection(Player player) {
        DialogueSession session = getSession(player);
        if (session == null) return false;
        if (session.isTyping()) return skipTyping(player);
        if (!session.isReadyForInput()) return false;
        if (!hasAvailableAnswers(player, session)) return continueDialogue(player);

        int selected = session.getSelectedAnswer();
        if (selected < 0 || selected >= session.getCurrentNode().getAnswers().size()
                || !isAnswerAvailable(player, session,
                session.getCurrentNode().getAnswers().get(selected))) {
            selected = findFirstAvailableAnswer(player, session);
            session.setSelectedAnswer(selected);
        }
        return selected >= 0 && chooseAnswer(player, selected);
    }

    public boolean chooseAnswer(Player player, int index) {
        DialogueSession session = getSession(player);
        if (session == null || session.isTyping() || !session.isReadyForInput()) return false;

        List<Answer> answers = session.getCurrentNode().getAnswers();
        if (index < 0 || index >= answers.size()) return false;
        Answer answer = answers.get(index);
        if (!isAnswerAvailable(player, session, answer)) return false;

        String targetId = answer.getTargetNode();
        if ((targetId == null || targetId.isBlank())
                && session.getCurrentNode().getNextNode() != null) {
            targetId = session.getCurrentNode().getNextNode();
        }
        if (targetId != null && !targetId.isBlank()) {
            Node target = session.getDialogue().getNode(targetId);
            if (target == null || !isNodeAvailable(player, session.getDialogue(), target)) {
                return false;
            }
        }

        core.markAnswerSelected(player.getUniqueId(), session.getDialogue().getId(),
                session.getCurrentNode().getId(), answer.getId());
        playSound(player, answer.getSound(),
                (float) answer.getSettings().getDouble("sound-volume", 1.0),
                (float) answer.getSettings().getDouble("sound-pitch", 1.0));
        executeCommands(player, answer.getCommands(), session, answer.getId());
        giveRewards(player, answer.getRewards(), session, answer.getId());
        if (sessions.get(player.getUniqueId()) != session) return false;

        if (targetId != null && !targetId.isBlank()) return transitionToNode(player, targetId);
        return finishDialogue(player, session);
    }

    public boolean skipTyping(Player player) {
        DialogueSession session = getSession(player);
        if (session == null || !session.isTyping()) return false;
        cancelTypeTask(session);
        session.setTyping(false);
        setDisplayText(session, session.getTypewriterText(), session.getCurrentSettings());
        afterTyping(player, session);
        return true;
    }

    public boolean isTyping(Player player) {
        DialogueSession session = getSession(player);
        return session != null && session.isTyping();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        DialogueSession session = getSession(event.getPlayer());
        if (session == null) return;

        boolean forward = event.getInput().isForward();
        boolean backward = event.getInput().isBackward();
        boolean forwardPressed = forward && !session.wasForwardPressed();
        boolean backwardPressed = backward && !session.wasBackwardPressed();
        session.setForwardPressed(forward);
        session.setBackwardPressed(backward);

        if (session.isTyping() || !session.isReadyForInput()) return;
        if (forwardPressed && !backward) moveSelection(event.getPlayer(), -1);
        else if (backwardPressed && !forward) moveSelection(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        DialogueSession session = getSession(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        confirmSelection(event.getPlayer());
    }

    private void updateActionBar(Player player, DialogueSession session) {
        if (!player.isOnline()) return;
        if (session.isTyping()) {
            player.sendActionBar(Component.text("F — пропустити текст", NamedTextColor.YELLOW));
        } else if (!session.isReadyForInput()) {
            player.sendActionBar(Component.text("…", NamedTextColor.GRAY));
        } else if (!hasAvailableAnswers(player, session)) {
            player.sendActionBar(Component.text("F — продовжити", NamedTextColor.YELLOW));
        } else {
            player.sendActionBar(Component.text(
                    "W / S — вибрати відповідь    F — підтвердити вибір", NamedTextColor.YELLOW));
        }
    }

    private void refreshAnswerDisplays(Player player, DialogueSession session) {
        createAnswerDisplays(player, session);
        updateActionBar(player, session);
    }

    // ---------------------------------------------------------------------
    // Branch transitions and completion
    // ---------------------------------------------------------------------

    private boolean transitionToNode(Player player, String nodeId) {
        DialogueSession session = getSession(player);
        if (session == null) return false;

        Node next = session.getDialogue().getNode(nodeId);
        if (next == null) {
            plugin.getLogger().warning("Dialogue branch not found: "
                    + session.getDialogue().getId() + ":" + nodeId);
            player.sendMessage(Component.text("Наступну гілку не знайдено.", NamedTextColor.RED));
            return false;
        }
        if (!isNodeAvailable(player, session.getDialogue(), next)) {
            player.sendMessage(Component.text("Ця гілка поки недоступна.", NamedTextColor.RED));
            return false;
        }

        completeCurrentBranch(player, session);
        if (sessions.get(player.getUniqueId()) != session) return false;
        session.setCurrentNode(next);
        session.setSelectedAnswer(-1);
        session.setTyping(false);
        session.setReadyForInput(false);
        displayCurrentNode(player, session, false);
        return true;
    }

    private boolean continueDialogue(Player player) {
        DialogueSession session = getSession(player);
        if (session == null) return false;

        String nextNode = session.getCurrentNode().getNextNode();
        if (nextNode != null && !nextNode.isBlank()) {
            return transitionToNode(player, nextNode);
        }
        return finishDialogue(player, session);
    }

    private void completeCurrentBranch(Player player, DialogueSession session) {
        Node node = session.getCurrentNode();
        if (node == null) return;
        core.markBranchCompleted(player.getUniqueId(), session.getDialogue().getId(), node.getId());
        executeCommands(player, node.getCommands(), session, null);
        giveRewards(player, node.getRewards(), session, null);
    }

    private boolean finishDialogue(Player player, DialogueSession session) {
        if (sessions.get(player.getUniqueId()) != session) return false;
        core.markDialogueCompleted(player.getUniqueId(), session.getDialogue().getId());
        completeCurrentBranch(player, session);
        if (sessions.get(player.getUniqueId()) == session) stop(player);
        return true;
    }

    // ---------------------------------------------------------------------
    // Commands, rewards and sounds
    // ---------------------------------------------------------------------

    private void executeCommands(Player player, List<String> commands,
                                 DialogueSession session, String answerId) {
        if (commands == null) return;
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String command = raw.trim()
                    .replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{dialogue}", session.getDialogue().getId())
                    .replace("{branch}", session.getCurrentNode() == null
                            ? "" : session.getCurrentNode().getId())
                    .replace("{answer}", answerId == null ? "" : answerId);
            if (command.startsWith("/")) command = command.substring(1);
            if (!command.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private void giveRewards(Player player, List<Reward> rewards,
                             DialogueSession session, String answerId) {
        if (rewards == null) return;
        for (Reward reward : rewards) {
            if (reward == null) continue;
            String type = reward.getType() == null
                    ? "command" : reward.getType().trim().toLowerCase(Locale.ROOT);
            String value = reward.getValue() == null ? "" : reward.getValue();
            int amount = Math.max(1, reward.getAmount());

            switch (type) {
                case "command" -> executeCommands(player, List.of(value), session, answerId);
                case "item" -> giveItemReward(player, reward, value, amount);
                case "experience", "exp" -> player.giveExp(Math.max(0, amount));
                case "levels", "level" -> player.giveExpLevels(Math.max(0, amount));
                default -> plugin.getLogger().warning("Unknown dialogue reward type: " + type);
            }
        }
    }

    private void giveItemReward(Player player, Reward reward, String value, int amount) {
        org.bukkit.inventory.ItemStack item = reward.getItem();
        if (item == null || item.getType().isAir()) {
            Material material = Material.matchMaterial(value);
            if (material == null) {
                plugin.getLogger().warning("Unknown item reward material: " + value);
                return;
            }
            item = new org.bukkit.inventory.ItemStack(material);
        }
        item.setAmount(amount);
        Map<Integer, org.bukkit.inventory.ItemStack> leftovers =
                player.getInventory().addItem(item);
        for (org.bukkit.inventory.ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void playTypingSound(Player player, Settings settings) {
        String soundName = settings.getString("typing-sound", null);
        if (soundName == null || soundName.isBlank()) return;
        playSound(player, soundName,
                (float) settings.getDouble("sound-volume", 1.0),
                (float) settings.getDouble("sound-pitch", 1.0));
    }

    private void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundName == null || soundName.isBlank()) return;
        try {
            String keyText = soundName.trim().toLowerCase(Locale.ROOT);
            if (keyText.startsWith("minecraft:")) keyText = keyText.substring("minecraft:".length());
            NamespacedKey key = NamespacedKey.minecraft(keyText);
            Sound sound = Registry.SOUNDS.get(key);
            if (sound == null) return;
            player.playSound(player.getLocation(), sound,
                    (float) clamp(volume, 0.0, 2.0), (float) clamp(pitch, 0.5, 2.0));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Could not play dialogue sound '" + soundName + "'.", exception);
        }
    }

    private Color parseBackground(Settings settings) {
        String value = settings.getString("background-color", "#000000");
        int alpha = settings.getInt("background-alpha", settings.getInt("background-opacity", 120));
        alpha = (int) clamp(alpha, 0, 255);
        if (value == null || value.equalsIgnoreCase("none")) {
            return Color.fromARGB(0, 0, 0, 0);
        }
        TextColor color = parseTextColor(value);
        if (color == null) return Color.fromARGB(alpha, 0, 0, 0);
        return Color.fromARGB(alpha, color.red(), color.green(), color.blue());
    }

    private TextColor parseTextColor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.startsWith("#")) return TextColor.fromHexString(value);
            return NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return NamedTextColor.WHITE;
        }
    }

    private Transformation createTransformation(Settings settings) {
        float scale = (float) clamp(settings.getDouble("text-scale", 1.0), 0.1, 10.0);
        return new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
        );
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    // ---------------------------------------------------------------------
    // Cleanup and restore
    // ---------------------------------------------------------------------

    public boolean stop(Player player) {
        if (player == null) return false;
        UUID playerId = player.getUniqueId();
        DialogueSession session = sessions.remove(playerId);
        if (session == null) return false;
        cleanupSession(player, session);
        return true;
    }

    private void cleanupSession(Player player, DialogueSession session) {
        cancelCameraTask(session);
        cancelNodeTasks(session);
        clearDisplays(session);
        restoreInvisibility(player, session);
        if (!player.isOnline()) return;

        player.sendActionBar(Component.empty());
        restoreHiddenPlayers(player, session);

        player.setWalkSpeed(session.getPreviousWalkSpeed());
        player.setFlySpeed(session.getPreviousFlySpeed());
        player.setGravity(session.hasPreviousGravity());
        player.setAllowFlight(session.hadPreviousAllowFlight());
        if (session.hadPreviousAllowFlight() && session.wasPreviousFlying()) {
            player.setFlying(true);
        } else {
            player.setFlying(false);
        }

        boolean returnToStart = session.getDialogue().getSettings()
                .getBoolean("return-to-start", true);
        if (returnToStart) {
            Location original = session.getOriginalLocation();
            if (original.getWorld() != null) {
                player.teleport(original, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }
        player.setFallDistance(0);
    }

    private void restoreHiddenPlayers(Player viewer, DialogueSession session) {
        for (UUID hiddenId : new HashSet<>(session.getHiddenPlayers())) {
            Player hidden = Bukkit.getPlayer(hiddenId);
            if (hidden != null && hidden.isOnline()) viewer.showPlayer(plugin, hidden);
        }
        session.getHiddenPlayers().clear();
    }

    private void restoreInvisibility(Player player, DialogueSession session) {
        if (!session.getDialogue().getSettings().getBoolean("invisibility", false)) return;
        player.setInvisible(session.wasPreviousInvisible());
    }

    private void clearAnswerDisplays(DialogueSession session) {
        List<TextDisplay> oldDisplays = new ArrayList<>(session.getAnswerDisplays());
        for (TextDisplay display : oldDisplays) {
            if (display != null && display.isValid()) display.remove();
            session.getDisplays().remove(display);
        }
        session.clearAnswerDisplays();
    }

    private void clearDisplays(DialogueSession session) {
        for (Entity entity : new ArrayList<>(session.getDisplays())) {
            if (entity != null && entity.isValid()) entity.remove();
        }
        session.clearDisplays();
        session.clearAnswerDisplays();
        session.setTextDisplay(null);
    }

    private void cancelCameraTask(DialogueSession session) {
        BukkitTask task = session.getCameraTask();
        if (task != null) {
            task.cancel();
            session.setCameraTask(null);
        }
    }

    private void removeOfflineSession(UUID playerId) {
        DialogueSession session = sessions.remove(playerId);
        if (session == null) return;
        cancelCameraTask(session);
        cancelNodeTasks(session);
        clearDisplays(session);
    }

    // ---------------------------------------------------------------------
    // Player event protection
    // ---------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        DialogueSession session = sessions.remove(player.getUniqueId());
        if (session != null) cleanupSession(player, session);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        for (Map.Entry<UUID, DialogueSession> entry : sessions.entrySet()) {
            DialogueSession session = entry.getValue();
            if (!session.isHidePlayers()) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline() || viewer.equals(joining)) continue;
            if (viewer.canSee(joining)) {
                viewer.hidePlayer(plugin, joining);
                session.getHiddenPlayers().add(joining.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            if (event instanceof PlayerTeleportEvent teleport
                    && teleport.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------------
    // Session model
    // ---------------------------------------------------------------------

    public static final class DialogueSession {
        private final UUID playerId;
        private final Dialogue dialogue;
        private final Location originalLocation;

        private Location cameraLocation;
        private Location cameraTransitionStart;
        private int cameraTransitionTick;
        private Node currentNode;
        private Settings currentSettings;
        private int selectedAnswer = -1;
        private boolean typing;
        private boolean readyForInput;
        private String typewriterText = "";
        private int[] typewriterCodePoints = new int[0];
        private int typewriterIndex;
        private double typingAccumulator;
        private TextDisplay textDisplay;
        private final List<Entity> displays = new ArrayList<>();
        private final List<TextDisplay> answerDisplays = new ArrayList<>();
        private final Set<UUID> hiddenPlayers = new HashSet<>();
        private BukkitTask cameraTask;
        private BukkitTask typeTask;
        private BukkitTask answerDelayTask;

        private boolean previousAllowFlight;
        private boolean previousFlying;
        private float previousWalkSpeed;
        private float previousFlySpeed;
        private boolean previousGravity;
        private boolean previousInvisible;
        private boolean hidePlayers;
        private boolean forwardPressed;
        private boolean backwardPressed;

        public DialogueSession(UUID playerId, Dialogue dialogue, Location originalLocation) {
            this.playerId = playerId;
            this.dialogue = dialogue;
            this.originalLocation = originalLocation.clone();
        }

        public UUID getPlayerId() { return playerId; }
        public Dialogue getDialogue() { return dialogue; }
        public Location getOriginalLocation() { return originalLocation.clone(); }
        public Location getCameraLocation() { return cameraLocation == null ? null : cameraLocation.clone(); }
        public void setCameraLocation(Location location) { cameraLocation = location == null ? null : location.clone(); }
        public Node getCurrentNode() { return currentNode; }
        public void setCurrentNode(Node node) { currentNode = node; }
        public Settings getCurrentSettings() { return currentSettings; }
        public void setCurrentSettings(Settings settings) { currentSettings = settings; }
        public int getSelectedAnswer() { return selectedAnswer; }
        public void setSelectedAnswer(int index) { selectedAnswer = index; }
        public boolean isTyping() { return typing; }
        public void setTyping(boolean value) { typing = value; }
        public boolean isReadyForInput() { return readyForInput; }
        public void setReadyForInput(boolean value) { readyForInput = value; }
        public String getTypewriterText() { return typewriterText; }
        public void setTypewriterText(String value) { typewriterText = value == null ? "" : value; }
        public int[] getTypewriterCodePoints() { return typewriterCodePoints; }
        public void setTypewriterCodePoints(int[] value) { typewriterCodePoints = value == null ? new int[0] : value; }
        public int getTypewriterIndex() { return typewriterIndex; }
        public void setTypewriterIndex(int value) { typewriterIndex = Math.max(0, value); }
        public double getTypingAccumulator() { return typingAccumulator; }
        public void setTypingAccumulator(double value) { typingAccumulator = Math.max(0.0, value); }
        public TextDisplay getTextDisplay() { return textDisplay; }
        public void setTextDisplay(TextDisplay display) { textDisplay = display; }
        public void addDisplay(Entity entity) { if (entity != null) displays.add(entity); }
        public List<Entity> getDisplays() { return displays; }
        public void clearDisplays() { displays.clear(); }
        public void addAnswerDisplay(TextDisplay display) { if (display != null) answerDisplays.add(display); }
        public List<TextDisplay> getAnswerDisplays() { return answerDisplays; }
        public void clearAnswerDisplays() { answerDisplays.clear(); }
        public Set<UUID> getHiddenPlayers() { return hiddenPlayers; }
        public BukkitTask getCameraTask() { return cameraTask; }
        public void setCameraTask(BukkitTask task) { cameraTask = task; }
        public BukkitTask getTypeTask() { return typeTask; }
        public void setTypeTask(BukkitTask task) { typeTask = task; }
        public BukkitTask getAnswerDelayTask() { return answerDelayTask; }
        public void setAnswerDelayTask(BukkitTask task) { answerDelayTask = task; }
        public Location getCameraTransitionStart() { return cameraTransitionStart == null ? null : cameraTransitionStart.clone(); }
        public void setCameraTransitionStart(Location value) { cameraTransitionStart = value == null ? null : value.clone(); }
        public int getCameraTransitionTick() { return cameraTransitionTick; }
        public void setCameraTransitionTick(int value) { cameraTransitionTick = Math.max(0, value); }
        public boolean hadPreviousAllowFlight() { return previousAllowFlight; }
        public void setPreviousAllowFlight(boolean value) { previousAllowFlight = value; }
        public boolean wasPreviousFlying() { return previousFlying; }
        public void setPreviousFlying(boolean value) { previousFlying = value; }
        public float getPreviousWalkSpeed() { return previousWalkSpeed; }
        public void setPreviousWalkSpeed(float value) { previousWalkSpeed = value; }
        public float getPreviousFlySpeed() { return previousFlySpeed; }
        public void setPreviousFlySpeed(float value) { previousFlySpeed = value; }
        public boolean hasPreviousGravity() { return previousGravity; }
        public void setPreviousGravity(boolean value) { previousGravity = value; }
        public boolean wasPreviousInvisible() { return previousInvisible; }
        public void setPreviousInvisible(boolean value) { previousInvisible = value; }
        public boolean isHidePlayers() { return hidePlayers; }
        public void setHidePlayers(boolean value) { hidePlayers = value; }
        public boolean wasForwardPressed() { return forwardPressed; }
        public void setForwardPressed(boolean value) { forwardPressed = value; }
        public boolean wasBackwardPressed() { return backwardPressed; }
        public void setBackwardPressed(boolean value) { backwardPressed = value; }
    }
}
