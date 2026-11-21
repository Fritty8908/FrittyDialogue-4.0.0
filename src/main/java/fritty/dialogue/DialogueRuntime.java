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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    private static final int CAMERA_TRANSITION_TICKS = 20;
    private static final int ANSWER_SELECTION_ANIMATION_TICKS = 5;
    private static final double CAMERA_POSITION_EPSILON_SQUARED = 0.0001;
    private static final float CAMERA_ROTATION_EPSILON = 0.15f;

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private final FrittyDialogue plugin;
    private final DialogueCore core;
    private final Map<UUID, DialogueSession> sessions = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();

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
        session.setPreviousInvisibilityEffect(player.getPotionEffect(PotionEffectType.INVISIBILITY));
        session.setPreviousInvulnerable(player.isInvulnerable());

        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setGravity(false);
        player.setInvulnerable(true);

        // This is the invisibility of the player reading the dialogue, not hide-players.
        if (session.getDialogue().getSettings().getBoolean("invisibility", false)) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0,
                    false, false, false), true);
        }

        // Default is ON. The Editor can explicitly save false to disable it.
        if (session.getDialogue().getSettings().getBoolean("hide-players", true)) {
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

    /** Allows only this runtime's own camera/restore teleports through the movement lock. */
    private boolean teleportInternally(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) return false;
        UUID id = player.getUniqueId();
        internalTeleports.add(id);
        try {
            return player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(id);
        }
    }

    private void setCameraTarget(Player player, DialogueSession session, Settings settings,
                                 boolean initial) {
        Location target = settings == null ? null : settings.getCameraLocation();
        if (target == null || target.getWorld() == null) {
            target = session.getOriginalLocation();
        }
        target = target.clone();

        Location current = player.getLocation().clone();
        boolean smooth = settings == null || settings.getBoolean("smooth-camera", true);
        session.setCameraLocation(target);

        boolean needsMove = current.getWorld() != target.getWorld()
                || current.distanceSquared(target) > CAMERA_POSITION_EPSILON_SQUARED
                || Math.abs(wrapDegrees(current.getYaw() - target.getYaw())) > CAMERA_ROTATION_EPSILON
                || Math.abs(current.getPitch() - target.getPitch()) > CAMERA_ROTATION_EPSILON;
        if (smooth && current.getWorld() == target.getWorld() && needsMove) {
            session.setCameraTransitionStart(current);
            session.setCameraTransitionTick(0);
        } else {
            session.setCameraTransitionStart(null);
            teleportInternally(player, target);
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
            double linear = Math.min(1.0, (double) tick / CAMERA_TRANSITION_TICKS);
            double progress = linear * linear * (3.0 - 2.0 * linear);

            if (transitionStart.getWorld() != target.getWorld()) {
                teleportInternally(player, target);
                session.setCameraTransitionStart(null);
            } else {
                Location interpolated = interpolate(transitionStart, target, progress);
                teleportInternally(player, interpolated);
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
                teleportInternally(player, target);
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
        return camera.clone().add(0, player.getEyeHeight(), 0);
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
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setSeeThrough(settings.getBoolean("see-through-blocks", true));
            entity.setShadowed(settings.getBoolean("text-shadow", false));
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

    /** typing-speed is the total time in seconds for the complete, character-by-character text. */
    private void startTyping(Player player, DialogueSession session, String fullText, Settings settings) {
        int[] codePoints = fullText == null ? new int[0] : fullText.codePoints().toArray();
        session.setTypewriterText(fullText == null ? "" : fullText);
        session.setTypewriterCodePoints(codePoints);
        session.setTypewriterIndex(0);

        boolean characterPrinting = settings.getBoolean("character-printing", true);
        double seconds = settings.getDouble("typing-speed", 5.0);
        if (!characterPrinting || codePoints.length == 0 || !Double.isFinite(seconds) || seconds <= 0.0) {
            setDisplayText(session, session.getTypewriterText(), settings);
            session.setTyping(false);
            afterTyping(player, session);
            return;
        }

        // At most one code point per tick, so every character is visibly printed.
        long durationTicks = Math.max(codePoints.length,
                Math.max(1L, Math.round(seconds * 20.0)));
        final long[] elapsedTicks = {0L};
        session.setTyping(true);
        session.setTypingAccumulator(0.0);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
                cancelTypeTask(session);
                return;
            }

            elapsedTicks[0]++;
            int target = (int) Math.min(codePoints.length,
                    Math.ceil((double) elapsedTicks[0] * codePoints.length / durationTicks));
            if (target > session.getTypewriterIndex()) {
                session.setTypewriterIndex(target);
                String partial = new String(codePoints, 0, target);
                setDisplayText(session, partial, session.getCurrentSettings());
                playTypingSound(player, session.getCurrentSettings());
            }

            if (elapsedTicks[0] >= durationTicks || session.getTypewriterIndex() >= codePoints.length) {
                cancelTypeTask(session);
                session.setTyping(false);
                setDisplayText(session, session.getTypewriterText(), session.getCurrentSettings());
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
        String source = text == null ? "" : text;
        source = source.replaceAll("(?i)&([0-9a-fk-or])", "§$1");
        Component component = LEGACY.deserialize(source);
        TextColor color = parseTextColor(settings.getString("text-color", "#FFFFFF"));
        return color == null ? component : component.color(color);
    }

    private void afterTyping(Player player, DialogueSession session) {
        if (sessions.get(player.getUniqueId()) != session) return;
        int delayTicks = Math.max(0, session.getCurrentSettings().getInt("delay", 0));
        if (delayTicks == 0) {
            showAnswers(player, session);
            return;
        }

        session.setReadyForInput(false);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) == session && player.isOnline()) {
                showAnswers(player, session);
            }
        }, delayTicks);
        session.setAnswerDelayTask(task);
    }

    private void showAnswers(Player player, DialogueSession session) {
        if (sessions.get(player.getUniqueId()) != session) return;
        session.setReadyForInput(true);
        createAnswerDisplays(player, session);
    }

    private void cancelNodeTasks(DialogueSession session) {
        cancelTypeTask(session);
        BukkitTask delayTask = session.getAnswerDelayTask();
        if (delayTask != null) {
            delayTask.cancel();
            session.setAnswerDelayTask(null);
        }
        BukkitTask animationTask = session.getAnswerAnimationTask();
        if (animationTask != null) {
            animationTask.cancel();
            session.setAnswerAnimationTask(null);
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

            // Answer styling is fully independent from dialogue and branch styling.
            // Only the answer's own settings are applied; unset values use answer defaults.
            Settings answerSettings = new Settings();
            answerSettings.applyOverrides(answer.getSettings());
            if (!available) {
                if (unavailableMode.equalsIgnoreCase("DISABLED")) {
                    text = "§8§m" + answer.getText();
                    answerSettings.set("text-color", "#777777");
                    answerSettings.set("background-color", "#777777");
                    answerSettings.set("background-alpha", 100);
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

            float baseScale = (float) clamp(answerSettings.getDouble("text-scale", 1.0), 0.1, 10.0);
            answerSettings.set("text-scale", baseScale);
            TextDisplay display = createTextDisplay(player, answerLocation, text, answerSettings);
            session.addDisplay(display);
            session.addAnswerDisplay(display);
            session.getAnswerDisplaysByIndex().put(index, display);
            session.getAnswerBaseScales().put(index, baseScale);
        }
        animateAnswerSelection(session);
    }

    private void animateAnswerSelection(DialogueSession session) {
        BukkitTask previous = session.getAnswerAnimationTask();
        if (previous != null) previous.cancel();
        if (session.getAnswerDisplaysByIndex().isEmpty()) return;

        Map<Integer, Float> targets = new HashMap<>();
        for (Map.Entry<Integer, Float> entry : session.getAnswerBaseScales().entrySet()) {
            targets.put(entry.getKey(), entry.getValue() * (entry.getKey() == session.getSelectedAnswer() ? 1.2f : 1.0f));
        }
        final int[] tick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            double linear = Math.min(1.0, (double) tick[0] / ANSWER_SELECTION_ANIMATION_TICKS);
            double progress = linear * linear * (3.0 - 2.0 * linear);
            for (Map.Entry<Integer, Float> entry : targets.entrySet()) {
                TextDisplay display = session.getAnswerDisplaysByIndex().get(entry.getKey());
                Float base = session.getAnswerBaseScales().get(entry.getKey());
                if (display == null || !display.isValid() || base == null) continue;
                float scale = (float) (base + (entry.getValue() - base) * progress);
                setDisplayScale(display, scale);
            }
            if (tick[0] >= ANSWER_SELECTION_ANIMATION_TICKS) {
                BukkitTask current = session.getAnswerAnimationTask();
                if (current != null) current.cancel();
                session.setAnswerAnimationTask(null);
            }
        }, 1L, 1L);
        session.setAnswerAnimationTask(task);
    }

    private void setDisplayScale(TextDisplay display, float scale) {
        display.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
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
        if (materialName == null || materialName.isBlank()) return false;

        String input = materialName.trim();
        String materialText = input;
        int required = 1;

        // Optional amount can be written as "diamond 3" or "diamond:3".
        // A namespaced ID such as "minecraft:diamond" remains intact.
        String[] words = input.split("\\s+", 2);
        if (words.length == 2) {
            materialText = words[0].trim();
            try {
                required = Math.max(1, Integer.parseInt(words[1].trim()));
            } catch (NumberFormatException ignored) {
                return false;
            }
        } else {
            int lastColon = input.lastIndexOf(':');
            if (lastColon > 0 && lastColon < input.length() - 1) {
                String possibleAmount = input.substring(lastColon + 1).trim();
                if (possibleAmount.matches("\\d+")) {
                    materialText = input.substring(0, lastColon).trim();
                    try {
                        required = Math.max(1, Integer.parseInt(possibleAmount));
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }
            }
        }

        Material material = Material.matchMaterial(materialText);
        if (material == null || material.isAir()) return false;

        int count = 0;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }

        org.bukkit.inventory.ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == material) count += offHand.getAmount();

        for (org.bukkit.inventory.ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }

        return count >= required;
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

    private void refreshAnswerDisplays(Player player, DialogueSession session) {
        createAnswerDisplays(player, session);
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
        completeCurrentBranch(player, session);
        if (sessions.get(player.getUniqueId()) != session) return false;
        core.markDialogueCompleted(player.getUniqueId(), session.getDialogue().getId());
        core.setCurrentStoryStage(player.getUniqueId(), session.getDialogue().getId(), "completed");
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
        String soundName = settings.getString("typing-sound", "UI_BUTTON_CLICK");
        if (soundName == null || soundName.isBlank()) soundName = "UI_BUTTON_CLICK";
        playSound(player, soundName,
                (float) settings.getDouble("sound-volume", 1.0),
                (float) settings.getDouble("sound-pitch", 1.0));
    }

    private void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundName == null || soundName.isBlank()) return;
        try {
            String normalized = soundName.trim();
            if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
            Sound sound = null;
            try {
                sound = Sound.valueOf(normalized.replace('.', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
            if (sound == null) {
                String keyText = normalized.toLowerCase(Locale.ROOT).replace('_', '.');
                NamespacedKey key = NamespacedKey.fromString(keyText.contains(":") ? keyText : "minecraft:" + keyText);
                if (key != null) sound = Registry.SOUNDS.get(key);
            }
            if (sound == null) return;
            player.playSound(player.getLocation(), sound,
                    (float) clamp(volume, 0.0, 2.0), (float) clamp(pitch, 0.5, 2.0));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Could not play dialogue sound '" + soundName + "'.", exception);
        }
    }

    private Color parseBackground(Settings settings) {
        String value = settings.getString("background-color", "#000000");
        int alpha = settings.getInt("background-alpha", settings.getInt("background-opacity", 110));
        alpha = (int) clamp(alpha, 0, 255);
        if (value == null || value.equalsIgnoreCase("none")) {
            return Color.fromARGB(0, 0, 0, 0);
        }
        TextColor color = parseTextColor(value);
        if (color == null) return Color.fromARGB(alpha, 0, 0, 0);
        return Color.fromARGB(alpha, color.red(), color.green(), color.blue());
    }

    private TextColor parseTextColor(String value) {
        if (value == null || value.isBlank()) return NamedTextColor.WHITE;
        String input = value.trim();
        try {
            if (input.startsWith("#")) return TextColor.fromHexString(input);
            if (input.matches("(?i)<(?:color:)?#[0-9a-f]{6}>")) {
                String hex = input.replaceAll("(?i)<(?:color:)?", "").replace(">", "");
                return TextColor.fromHexString(hex);
            }
            if (input.matches("(?i)<(?:color:)?[a-z_]+>")) {
                String namedTag = input.substring(1, input.length() - 1).toLowerCase(Locale.ROOT);
                if (namedTag.startsWith("color:")) namedTag = namedTag.substring(6);
                TextColor named = NamedTextColor.NAMES.value(namedTag);
                if (named != null) return named;
            }
            if (input.matches("(?i)[&§][0-9a-f]")) {
                String code = input.substring(1).toLowerCase(Locale.ROOT);
                String[] colors = {"#000000", "#0000AA", "#00AA00", "#00AAAA", "#AA0000", "#AA00AA",
                        "#FFAA00", "#AAAAAA", "#555555", "#5555FF", "#55FF55", "#55FFFF",
                        "#FF5555", "#FF55FF", "#FFFF55", "#FFFFFF"};
                return TextColor.fromHexString(colors["0123456789abcdef".indexOf(code.charAt(0))]);
            }
            TextColor named = NamedTextColor.NAMES.value(input.toLowerCase(Locale.ROOT));
            if (named != null) return named;
        } catch (Exception ignored) { }
        return NamedTextColor.WHITE;
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
        if (player.isOnline()) restoreHiddenPlayers(player, session);

        player.setWalkSpeed(session.getPreviousWalkSpeed());
        player.setFlySpeed(session.getPreviousFlySpeed());
        player.setGravity(session.hasPreviousGravity());
        player.setInvulnerable(session.wasPreviousInvulnerable());
        player.setAllowFlight(session.hadPreviousAllowFlight());
        if (session.hadPreviousAllowFlight() && session.wasPreviousFlying()) {
            player.setFlying(true);
        } else {
            player.setFlying(false);
        }

        boolean returnToStart = session.getDialogue().getSettings()
                .getBoolean("return-to-start", true);
        Location destination = returnToStart
                ? session.getOriginalLocation()
                : session.getCameraLocation();
        if (player.isOnline() && destination != null && destination.getWorld() != null) {
            teleportInternally(player, destination);
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
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect previous = session.getPreviousInvisibilityEffect();
        if (previous != null) player.addPotionEffect(previous, true);
        player.setInvisible(session.wasPreviousInvisible());
    }

    private void clearAnswerDisplays(DialogueSession session) {
        BukkitTask animation = session.getAnswerAnimationTask();
        if (animation != null) animation.cancel();
        session.setAnswerAnimationTask(null);
        List<TextDisplay> oldDisplays = new ArrayList<>(session.getAnswerDisplays());
        for (TextDisplay display : oldDisplays) {
            if (display != null && display.isValid()) display.remove();
            session.getDisplays().remove(display);
        }
        session.clearAnswerDisplays();
        session.getAnswerDisplaysByIndex().clear();
        session.getAnswerBaseScales().clear();
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
        UUID playerId = event.getPlayer().getUniqueId();
        DialogueSession session = sessions.remove(playerId);
        if (session != null) cleanupSession(event.getPlayer(), session);
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
        UUID id = event.getPlayer().getUniqueId();
        if (!sessions.containsKey(id)) return;
        if (event instanceof PlayerTeleportEvent && internalTeleports.contains(id)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!sessions.containsKey(id) || internalTeleports.contains(id)) return;
        event.setCancelled(true);
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
        private PotionEffect previousInvisibilityEffect;
        private boolean previousInvulnerable;
        private boolean hidePlayers;
        private final Map<Integer, TextDisplay> answerDisplaysByIndex = new HashMap<>();
        private final Map<Integer, Float> answerBaseScales = new HashMap<>();
        private BukkitTask answerAnimationTask;
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
        public PotionEffect getPreviousInvisibilityEffect() { return previousInvisibilityEffect; }
        public void setPreviousInvisibilityEffect(PotionEffect effect) { previousInvisibilityEffect = effect; }
        public boolean wasPreviousInvulnerable() { return previousInvulnerable; }
        public void setPreviousInvulnerable(boolean value) { previousInvulnerable = value; }
        public Map<Integer, TextDisplay> getAnswerDisplaysByIndex() { return answerDisplaysByIndex; }
        public Map<Integer, Float> getAnswerBaseScales() { return answerBaseScales; }
        public BukkitTask getAnswerAnimationTask() { return answerAnimationTask; }
        public void setAnswerAnimationTask(BukkitTask task) { answerAnimationTask = task; }
        public boolean isHidePlayers() { return hidePlayers; }
        public void setHidePlayers(boolean value) { hidePlayers = value; }
        public boolean wasForwardPressed() { return forwardPressed; }
        public void setForwardPressed(boolean value) { forwardPressed = value; }
        public boolean wasBackwardPressed() { return backwardPressed; }
        public void setBackwardPressed(boolean value) { backwardPressed = value; }
    }
}
