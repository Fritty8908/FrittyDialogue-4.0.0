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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class DialogueRuntime implements Listener {

    private final FrittyDialogue plugin;
    private final DialogueCore core;

    private final Map<UUID, DialogueSession> sessions =
            new HashMap<>();

    private boolean initialized;
    private boolean shuttingDown;

    public DialogueRuntime(
            FrittyDialogue plugin,
            DialogueCore core
    ) {
        this.plugin = plugin;
        this.core = core;
    }

    /*
     * ============================================================
     * INITIALIZATION
     * ============================================================
     */

    public void initialize() {
        if (initialized) {
            return;
        }

        shuttingDown = false;
        initialized = true;

        plugin.getLogger().info(
                "DialogueRuntime initialized."
        );
    }

    public void shutdown() {
        if (shuttingDown) {
            return;
        }

        shuttingDown = true;

        for (UUID playerId :
                new ArrayList<>(sessions.keySet())) {

            Player player = Bukkit.getPlayer(playerId);

            if (player != null) {
                stop(player);
            } else {
                removeSession(playerId);
            }
        }

        initialized = false;
    }

    /*
     * ============================================================
     * SESSION ACCESS
     * ============================================================
     */

    public boolean isRunning(Player player) {
        return player != null
                && sessions.containsKey(player.getUniqueId());
    }

    public DialogueSession getSession(Player player) {
        if (player == null) {
            return null;
        }

        return sessions.get(player.getUniqueId());
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    /*
     * ============================================================
     * START DIALOGUE
     * ============================================================
     */

    public boolean start(
            Player player,
            String dialogueId
    ) {
        if (!initialized || shuttingDown
                || player == null
                || !player.isOnline()) {
            return false;
        }

        if (isRunning(player)) {
            stop(player);
        }

        Dialogue dialogue = core.getDialogue(dialogueId);

        if (dialogue == null) {
            player.sendMessage(
                    Component.text(
                            "Діалог не знайдено.",
                            NamedTextColor.RED
                    )
            );

            return false;
        }

        Node root = core.getRootNode(dialogueId);

        if (root == null) {
            player.sendMessage(
                    Component.text(
                            "У цього діалогу немає початкової гілки.",
                            NamedTextColor.RED
                    )
            );

            return false;
        }

        Settings settings = core.getEffectiveSettings(
                dialogue,
                root
        );

        Location originalLocation =
                player.getLocation().clone();

        DialogueSession session = new DialogueSession(
                player.getUniqueId(),
                dialogue,
                originalLocation
        );

        session.setCurrentNode(root);
        session.setCurrentSettings(settings);

        sessions.put(
                player.getUniqueId(),
                session
        );

        try {
            preparePlayer(player, session);
            createCamera(player, session);
            displayCurrentNode(player, session);
            startCameraTask(player, session);

            return true;

        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start dialogue "
                            + dialogueId + ".",
                    exception
            );

            stop(player);
            return false;
        }
    }

    /*
     * ============================================================
     * PLAYER PREPARATION
     * ============================================================
     */

    private void preparePlayer(
            Player player,
            DialogueSession session
    ) {
        session.setPreviousAllowFlight(
                player.getAllowFlight()
        );

        session.setPreviousFlying(
                player.isFlying()
        );

        session.setPreviousWalkSpeed(
                player.getWalkSpeed()
        );

        session.setPreviousFlySpeed(
                player.getFlySpeed()
        );

        session.setPreviousGravity(
                player.hasGravity()
        );

        player.setVelocity(
                player.getVelocity().zero()
        );

        player.setFallDistance(0);

        player.setWalkSpeed(0);
        player.setFlySpeed(0);

        player.setAllowFlight(true);
        player.setFlying(true);

        player.setGravity(false);
    }

    /*
     * ============================================================
     * CAMERA
     * ============================================================
     */

    private void createCamera(
            Player player,
            DialogueSession session
    ) {
        Settings settings = session.getCurrentSettings();

        Location cameraLocation =
                settings.getCameraLocation();

        if (cameraLocation == null) {
            cameraLocation =
                    session.getOriginalLocation().clone();
        }

        if (cameraLocation.getWorld() == null) {
            throw new IllegalStateException(
                    "Camera location has no world."
            );
        }

        session.setCameraLocation(
                cameraLocation.clone()
        );

        player.teleport(
                cameraLocation,
                PlayerTeleportEvent.TeleportCause.PLUGIN
        );
    }

    private void startCameraTask(
            Player player,
            DialogueSession session
    ) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    if (!player.isOnline()
                            || !isRunning(player)) {
                        stop(player);
                        return;
                    }

                    DialogueSession current =
                            getSession(player);

                    if (current != session) {
                        return;
                    }

                    maintainCamera(player, session);
                },
                1L,
                1L
        );

        session.setCameraTask(task);
    }

    private void maintainCamera(
            Player player,
            DialogueSession session
    ) {
        Location camera = session.getCameraLocation();

        if (camera == null || camera.getWorld() == null) {
            return;
        }

        Location current = player.getLocation();

        boolean wrongWorld =
                current.getWorld() != camera.getWorld();

        boolean wrongPosition =
                current.distanceSquared(camera) > 0.0001;

        boolean wrongRotation =
                Math.abs(current.getYaw() - camera.getYaw()) > 0.1
                        || Math.abs(
                                current.getPitch() - camera.getPitch()
                        ) > 0.1;

        if (wrongWorld || wrongPosition || wrongRotation) {
            player.teleport(
                    camera,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );
        }

        player.setVelocity(
                player.getVelocity().zero()
        );
    }

    /*
     * ============================================================
     * DISPLAY CURRENT NODE
     * ============================================================
     */

    private void displayCurrentNode(
            Player player,
            DialogueSession session
    ) {
        clearDisplays(session);

        Node node = session.getCurrentNode();

        if (node == null) {
            stop(player);
            return;
        }

        Settings settings = core.getEffectiveSettings(
                session.getDialogue(),
                node
        );

        session.setCurrentSettings(settings);

        createDialogueText(
                player,
                session,
                node,
                settings
        );

        createAnswerDisplays(
                player,
                session,
                node,
                settings
        );
    }

    /*
     * ============================================================
     * TEXT DISPLAY
     * ============================================================
     */

    private void createDialogueText(
            Player player,
            DialogueSession session,
            Node node,
            Settings settings
    ) {
        Location textLocation =
                resolveTextLocation(player, settings);

        String text = settings.getString(
                "text",
                node.getName()
        );

        TextDisplay display = createTextDisplay(
                textLocation,
                text,
                settings
        );

        session.addDisplay(display);
        session.setTextDisplay(display);
    }

    private Location resolveTextLocation(
            Player player,
            Settings settings
    ) {
        Object configured = settings.get("location");

        if (configured instanceof Location location
                && location.getWorld() != null) {
            return location.clone();
        }

        return player.getEyeLocation()
                .add(player.getLocation().getDirection()
                        .multiply(2.0));
    }

    private TextDisplay createTextDisplay(
            Location location,
            String text,
            Settings settings
    ) {
        World world = location.getWorld();

        if (world == null) {
            throw new IllegalStateException(
                    "Text display world is missing."
            );
        }

        TextDisplay display = world.spawn(
                location,
                TextDisplay.class,
                entity -> {
                    entity.text(
                            parseText(text, settings)
                    );

                    entity.setBillboard(
                            Display.Billboard.FIXED
                    );

                    entity.setAlignment(
                            TextDisplay.TextAlignment.CENTER
                    );

                    entity.setSeeThrough(
                            settings.getBoolean(
                                    "see-through-blocks",
                                    false
                            )
                    );

                    entity.setShadowed(
                            settings.getBoolean(
                                    "text-shadow",
                                    true
                            )
                    );

                    entity.setDefaultBackground(
                            settings.getBoolean(
                                    "default-background",
                                    false
                            )
                    );

                    entity.setBackgroundColor(
                            parseBackground(settings)
                    );

                    entity.setLineWidth(
                            settings.getInt(
                                    "text-width",
                                    300
                            )
                    );

                    entity.setViewRange(
                            (float) settings.getDouble(
                                    "view-range",
                                    32.0
                            )
                    );

                    entity.setTransformation(
                            createTransformation(settings)
                    );

                    entity.setPersistent(false);
                }
        );

        return display;
    }

    /*
     * ============================================================
     * TEXT FORMATTING
     * ============================================================
     */

    private Component parseText(
            String text,
            Settings settings
    ) {
        if (text == null) {
            text = "";
        }

        String color = settings.getString(
                "text-color",
                "#FFFFFF"
        );

        TextColor textColor = parseColor(color);

        Component component = Component.text(text);

        if (textColor != null) {
            component = component.color(textColor);
        }

        return component;
    }

    private TextColor parseColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            if (value.startsWith("#")) {
                return TextColor.fromHexString(value);
            }

            NamedTextColor named =
                    NamedTextColor.NAMES.value(
                            value.toLowerCase()
                    );

            return named;

        } catch (Exception ignored) {
            return NamedTextColor.WHITE;
        }
    }

    private org.bukkit.Color parseBackground(
            Settings settings
    ) {
        String value = settings.getString(
                "background-color",
                "#000000"
        );

        int opacity = settings.getInt(
                "background-opacity",
                120
        );

        opacity = Math.max(0, Math.min(255, opacity));

        if (value == null
                || value.equalsIgnoreCase("none")) {
            return org.bukkit.Color.fromARGB(
                    0,
                    0,
                    0,
                    0
            );
        }

        try {
            TextColor color = parseColor(value);

            if (color == null) {
                return org.bukkit.Color.fromARGB(
                        opacity,
                        0,
                        0,
                        0
                );
            }

            return org.bukkit.Color.fromARGB(
                    opacity,
                    color.red(),
                    color.green(),
                    color.blue()
            );

        } catch (Exception ignored) {
            return org.bukkit.Color.fromARGB(
                    opacity,
                    0,
                    0,
                    0
            );
        }
    }

    private Transformation createTransformation(
            Settings settings
    ) {
        float scale = (float) settings.getDouble(
                "text-scale",
                1.0
        );

        scale = Math.max(0.1f, Math.min(10.0f, scale));

        return new Transformation(
                new Vector3f(0, 0, 0),
                new org.joml.AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new org.joml.AxisAngle4f()
        );
    }
    /*
     * ============================================================
     * ANSWER DISPLAYS
     * ============================================================
     */

    private void createAnswerDisplays(
            Player player,
            DialogueSession session,
            Node node,
            Settings settings
    ) {
        List<Answer> answers = node.getAnswers();

        if (answers.isEmpty()) {
            return;
        }

        Location base = resolveTextLocation(
                player,
                settings
        );

        double spacing = settings.getDouble(
                "answer-spacing",
                0.35
        );

        double startOffset = -(
                (answers.size() - 1) * spacing
        ) / 2.0;

        for (int i = 0; i < answers.size(); i++) {
            Answer answer = answers.get(i);

            Location answerLocation = base.clone().add(
                    0,
                    startOffset - ((i + 1) * spacing),
                    0
            );

            boolean available = isAnswerAvailable(
                    player,
                    session,
                    answer
            );

            TextDisplay display = createAnswerDisplay(
                    answerLocation,
                    answer,
                    settings,
                    available,
                    i == session.getSelectedAnswer()
            );

            session.addDisplay(display);
            session.addAnswerDisplay(display);
        }
    }

    private TextDisplay createAnswerDisplay(
            Location location,
            Answer answer,
            Settings settings,
            boolean available,
            boolean selected
    ) {
        String prefix = selected
                ? settings.getString(
                        "answer-selected-prefix",
                        "» "
                )
                : settings.getString(
                        "answer-prefix",
                        "  "
                );

        String suffix = selected
                ? settings.getString(
                        "answer-selected-suffix",
                        " «"
                )
                : "";

        String text = prefix + answer.getText() + suffix;

        if (!available) {
            String unavailableMode = settings.getString(
                    "unavailable-answer-mode",
                    "DIM"
            );

            if (unavailableMode.equalsIgnoreCase("HIDE")) {
                text = "";
            } else if (unavailableMode.equalsIgnoreCase("DISABLED")) {
                text = settings.getString(
                        "unavailable-answer-prefix",
                        "§8"
                ) + text;
            }
        }

        TextDisplay display = createTextDisplay(
                location,
                text,
                settings
        );

        display.setSeeThrough(
                settings.getBoolean(
                        "see-through-blocks",
                        false
                )
        );

        return display;
    }

    /*
     * ============================================================
     * ANSWER AVAILABILITY
     * ============================================================
     */

    private boolean isAnswerAvailable(
            Player player,
            DialogueSession session,
            Answer answer
    ) {
        if (!answer.isEnabled()) {
            return false;
        }

        ConditionGroup conditions =
                answer.getConditions();

        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        List<Boolean> results = new ArrayList<>();

        for (Condition condition : conditions.getConditions()) {
            results.add(
                    evaluateCondition(
                            player,
                            session,
                            condition
                    )
            );
        }

        if (conditions.getLogic().equalsIgnoreCase("OR")) {
            return results.stream().anyMatch(Boolean::booleanValue);
        }

        return results.stream().allMatch(Boolean::booleanValue);
    }

    private boolean evaluateCondition(
            Player player,
            DialogueSession session,
            Condition condition
    ) {
        if (condition == null) {
            return false;
        }

        String type = condition.getType().toLowerCase();
        String value = condition.getValue();
        String operator = condition.getOperator().toLowerCase();

        return switch (type) {
            case "permission" ->
                    compare(
                            player.hasPermission(value),
                            operator
                    );

            case "tag" ->
                    compare(
                            player.getScoreboardTags().contains(value),
                            operator
                    );

            case "dialogue_completed" ->
                    compare(
                            core.isDialogueCompleted(
                                    player.getUniqueId(),
                                    value
                            ),
                            operator
                    );

            case "branch_completed" ->
                    compare(
                            core.isBranchCompleted(
                                    player.getUniqueId(),
                                    session.getDialogue().getId(),
                                    value
                            ),
                            operator
                    );

            case "answer_selected" ->
                    compareAnswerSelected(
                            player,
                            session,
                            value,
                            operator
                    );

            case "item" ->
                    hasRequiredItem(player, value);

            case "level" ->
                    compareNumber(
                            player.getLevel(),
                            value,
                            operator
                    );

            default -> false;
        };
    }

    private boolean compare(
            boolean actual,
            String operator
    ) {
        return switch (operator) {
            case "not", "not_equals", "!=" ->
                    !actual;

            default -> actual;
        };
    }

    private boolean compareAnswerSelected(
            Player player,
            DialogueSession session,
            String value,
            String operator
    ) {
        boolean selected = core.wasAnswerSelected(
                player.getUniqueId(),
                session.getDialogue().getId(),
                session.getCurrentNode().getId(),
                value
        );

        return compare(selected, operator);
    }

    private boolean compareNumber(
            double actual,
            String expected,
            String operator
    ) {
        double number;

        try {
            number = Double.parseDouble(expected);
        } catch (NumberFormatException exception) {
            return false;
        }

        return switch (operator) {
            case "greater", ">" -> actual > number;
            case "greater_or_equal", ">=" -> actual >= number;
            case "less", "<" -> actual < number;
            case "less_or_equal", "<=" -> actual <= number;
            case "not_equals", "!=" -> actual != number;
            default -> actual == number;
        };
    }

    private boolean hasRequiredItem(
            Player player,
            String materialName
    ) {
        Material material;

        try {
            material = Material.valueOf(
                    materialName.toUpperCase()
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return player.getInventory().contains(material);
    }

    /*
     * ============================================================
     * ANSWER SELECTION
     * ============================================================
     */

    public boolean selectAnswer(
            Player player,
            int index
    ) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        List<Answer> answers =
                session.getCurrentNode().getAnswers();

        if (index < 0 || index >= answers.size()) {
            return false;
        }

        Answer answer = answers.get(index);

        if (!isAnswerAvailable(player, session, answer)) {
            playSound(
                    player,
                    session.getCurrentSettings().getString(
                            "unavailable-answer-sound",
                            "BLOCK_NOTE_BLOCK_BASS"
                    )
            );

            return false;
        }

        session.setSelectedAnswer(index);

        refreshAnswerDisplays(player, session);

        return true;
    }

    public boolean moveSelection(
            Player player,
            int direction
    ) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        List<Answer> answers =
                session.getCurrentNode().getAnswers();

        if (answers.isEmpty()) {
            return false;
        }

        int current = session.getSelectedAnswer();
        int next = current;

        for (int i = 0; i < answers.size(); i++) {
            next += direction >= 0 ? 1 : -1;

            if (next >= answers.size()) {
                next = 0;
            }

            if (next < 0) {
                next = answers.size() - 1;
            }

            if (isAnswerAvailable(
                    player,
                    session,
                    answers.get(next)
            )) {
                session.setSelectedAnswer(next);
                refreshAnswerDisplays(player, session);
                return true;
            }
        }

        return false;
    }

    private void refreshAnswerDisplays(
            Player player,
            DialogueSession session
    ) {
        clearAnswerDisplays(session);

        createAnswerDisplays(
                player,
                session,
                session.getCurrentNode(),
                session.getCurrentSettings()
        );
    }

    /*
     * ============================================================
     * CONFIRM SELECTION
     * ============================================================
     */

    public boolean confirmSelection(Player player) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        if (session.isTyping()) {
            session.skipTyping();
            return true;
        }

        List<Answer> answers =
                session.getCurrentNode().getAnswers();

        if (answers.isEmpty()) {
            return continueDialogue(player);
        }

        int selected = session.getSelectedAnswer();

        if (selected < 0 || selected >= answers.size()) {
            return false;
        }

        return chooseAnswer(
                player,
                selected
        );
    }

    public boolean chooseAnswer(
            Player player,
            int index
    ) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        List<Answer> answers =
                session.getCurrentNode().getAnswers();

        if (index < 0 || index >= answers.size()) {
            return false;
        }

        Answer answer = answers.get(index);

        if (!isAnswerAvailable(player, session, answer)) {
            return false;
        }

        core.markAnswerSelected(
                player.getUniqueId(),
                session.getDialogue().getId(),
                session.getCurrentNode().getId(),
                answer.getId()
        );

        playSound(player, answer.getSound());

        executeAnswerCommands(player, answer);
        giveRewards(player, answer.getRewards());

        if (answer.getTargetNode() != null) {
            return transitionToNode(
                    player,
                    answer.getTargetNode()
            );
        }

        return continueDialogue(player);
    }

    /*
     * ============================================================
     * NODE TRANSITIONS
     * ============================================================
     */

    private boolean transitionToNode(
            Player player,
            String nodeId
    ) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        Node next = session.getDialogue().getNode(nodeId);

        if (next == null) {
            plugin.getLogger().warning(
                    "Dialogue node not found: " + nodeId
            );

            stop(player);
            return false;
        }

        core.markBranchCompleted(
                player.getUniqueId(),
                session.getDialogue().getId(),
                session.getCurrentNode().getId()
        );

        session.setCurrentNode(next);
        session.setSelectedAnswer(0);
        session.setTyping(false);

        displayCurrentNode(player, session);

        return true;
    }

    private boolean continueDialogue(Player player) {
        DialogueSession session = getSession(player);

        if (session == null) {
            return false;
        }

        String nextNode =
                session.getCurrentNode().getNextNode();

        if (nextNode == null || nextNode.isBlank()) {
            core.markBranchCompleted(
                    player.getUniqueId(),
                    session.getDialogue().getId(),
                    session.getCurrentNode().getId()
            );

            core.markDialogueCompleted(
                    player.getUniqueId(),
                    session.getDialogue().getId()
            );

            stop(player);
            return true;
        }

        return transitionToNode(
                player,
                nextNode
        );
  }
    /*
     * ============================================================
     * ANSWER COMMANDS
     * ============================================================
     */

    private void executeAnswerCommands(
            Player player,
            Answer answer
    ) {
        for (String command : answer.getCommands()) {
            if (command == null || command.isBlank()) {
                continue;
            }

            String prepared = command
                    .replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());

            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }

            String finalCommand = prepared;

            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(),
                            finalCommand
                    )
            );
        }
    }

    /*
     * ============================================================
     * REWARDS
     * ============================================================
     */

    private void giveRewards(
            Player player,
            List<Reward> rewards
    ) {
        if (rewards == null) {
            return;
        }

        for (Reward reward : rewards) {
            if (reward == null) {
                continue;
            }

            String type = reward.getType()
                    .toLowerCase();

            String value = reward.getValue();

            int amount = reward.getAmount();

            switch (type) {
                case "command" -> {
                    String command = value
                            .replace("{player}", player.getName())
                            .replace(
                                    "{uuid}",
                                    player.getUniqueId().toString()
                            );

                    if (command.startsWith("/")) {
                        command = command.substring(1);
                    }

                    Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(),
                            command
                    );
                }

                case "item" -> {
                    Material material;

                    try {
                        material = Material.valueOf(
                                value.toUpperCase()
                        );
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning(
                                "Unknown reward material: " + value
                        );
                        continue;
                    }

                    player.getInventory().addItem(
                            new org.bukkit.inventory.ItemStack(
                                    material,
                                    Math.max(1, amount)
                            )
                    );
                }

                case "experience" -> player.giveExp(
                        Math.max(0, amount)
                );

                case "levels" -> player.giveExpLevels(
                        Math.max(0, amount)
                );

                default -> plugin.getLogger().warning(
                        "Unknown reward type: " + type
                );
            }
        }
    }

    /*
     * ============================================================
     * SOUND
     * ============================================================
     */

    private void playSound(
            Player player,
            String soundName
    ) {
        if (player == null
                || !player.isOnline()
                || soundName == null
                || soundName.isBlank()) {
            return;
        }

        try {
            Sound sound = RegistryLookup.findSound(soundName);

            if (sound == null) {
                return;
            }

            player.playSound(
                    player.getLocation(),
                    sound,
                    1.0f,
                    1.0f
            );

        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Could not play dialogue sound: " + soundName
            );
        }
    }

    /*
     * ============================================================
     * DISPLAY CLEANUP
     * ============================================================
     */

    private void clearAnswerDisplays(
            DialogueSession session
    ) {
        for (TextDisplay display :
                new ArrayList<>(session.getAnswerDisplays())) {

            if (display != null && display.isValid()) {
                display.remove();
            }
        }

        session.clearAnswerDisplays();
    }

    private void clearDisplays(
            DialogueSession session
    ) {
        for (Entity entity :
                new ArrayList<>(session.getDisplays())) {

            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }

        session.clearDisplays();
        session.setTextDisplay(null);
    }

    /*
     * ============================================================
     * STOP DIALOGUE
     * ============================================================
     */

    public boolean stop(Player player) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        DialogueSession session = sessions.get(playerId);

        if (session == null) {
            return false;
        }

        sessions.remove(playerId);

        try {
            cleanupSession(player, session);
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not completely stop dialogue for "
                            + player.getName(),
                    exception
            );
        }

        return true;
    }

    private void cleanupSession(
            Player player,
            DialogueSession session
    ) {
        cancelCameraTask(session);

        clearDisplays(session);

        if (!player.isOnline()) {
            return;
        }

        restorePlayer(player, session);
    }

    private void cancelCameraTask(
            DialogueSession session
    ) {
        BukkitTask task = session.getCameraTask();

        if (task != null) {
            task.cancel();
            session.setCameraTask(null);
        }
    }

    /*
     * ============================================================
     * PLAYER RESTORE
     * ============================================================
     */

    private void restorePlayer(
            Player player,
            DialogueSession session
    ) {
        player.setWalkSpeed(
                session.getPreviousWalkSpeed()
        );

        player.setFlySpeed(
                session.getPreviousFlySpeed()
        );

        player.setGravity(
                session.hasPreviousGravity()
        );

        player.setAllowFlight(
                session.hadPreviousAllowFlight()
        );

        if (session.hadPreviousAllowFlight()
                && session.wasPreviousFlying()) {
            player.setFlying(true);
        } else {
            player.setFlying(false);
        }

        Location original =
                session.getOriginalLocation();

        if (original != null
                && original.getWorld() != null) {

            player.teleport(
                    original,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );
        }

        player.setFallDistance(0);
    }

    /*
     * ============================================================
     * SESSION REMOVAL
     * ============================================================
     */

    private void removeSession(UUID playerId) {
        DialogueSession session = sessions.remove(playerId);

        if (session == null) {
            return;
        }

        cancelCameraTask(session);
        clearDisplays(session);
    }

    /*
     * ============================================================
     * PLAYER EVENTS
     * ============================================================
     */

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        DialogueSession session =
                sessions.get(player.getUniqueId());

        if (session != null) {
            removeSession(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        DialogueSession session =
                sessions.get(player.getUniqueId());

        if (session == null) {
            return;
        }

        if (event.getCause()
                == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }

        event.setCancelled(true);
    }

    /*
     * ============================================================
     * INPUT STATE
     * ============================================================
     */

    public boolean skipTyping(Player player) {
        DialogueSession session = getSession(player);

        if (session == null || !session.isTyping()) {
            return false;
        }

        session.skipTyping();

        return true;
    }

    public boolean isTyping(Player player) {
        DialogueSession session = getSession(player);

        return session != null && session.isTyping();
    }

    /*
     * ============================================================
     * SESSION MODEL
     * ============================================================
     */

    public static final class DialogueSession {

        private final UUID playerId;

        private final Dialogue dialogue;

        private final Location originalLocation;

        private Location cameraLocation;

        private Node currentNode;

        private Settings currentSettings;

        private int selectedAnswer;

        private boolean typing;

        private TextDisplay textDisplay;

        private final List<Entity> displays =
                new ArrayList<>();

        private final List<TextDisplay> answerDisplays =
                new ArrayList<>();

        private BukkitTask cameraTask;

        private boolean previousAllowFlight;
        private boolean previousFlying;

        private float previousWalkSpeed;
        private float previousFlySpeed;

        private boolean previousGravity;

        public DialogueSession(
                UUID playerId,
                Dialogue dialogue,
                Location originalLocation
        ) {
            this.playerId = playerId;
            this.dialogue = dialogue;
            this.originalLocation =
                    originalLocation.clone();
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public Dialogue getDialogue() {
            return dialogue;
        }

        public Location getOriginalLocation() {
            return originalLocation.clone();
        }

        public Location getCameraLocation() {
            return cameraLocation == null
                    ? null
                    : cameraLocation.clone();
        }

        public void setCameraLocation(Location location) {
            cameraLocation = location == null
                    ? null
                    : location.clone();
        }

        public Node getCurrentNode() {
            return currentNode;
        }

        public void setCurrentNode(Node node) {
            currentNode = node;
        }

        public Settings getCurrentSettings() {
            return currentSettings;
        }

        public void setCurrentSettings(Settings settings) {
            currentSettings = settings;
        }

        public int getSelectedAnswer() {
            return selectedAnswer;
        }

        public void setSelectedAnswer(int index) {
            selectedAnswer = Math.max(0, index);
        }

        public boolean isTyping() {
            return typing;
        }

        public void setTyping(boolean typing) {
            this.typing = typing;
        }

        public void skipTyping() {
            typing = false;
        }

        public TextDisplay getTextDisplay() {
            return textDisplay;
        }

        public void setTextDisplay(TextDisplay display) {
            textDisplay = display;
        }

        public void addDisplay(Entity entity) {
            if (entity != null) {
                displays.add(entity);
            }
        }

        public List<Entity> getDisplays() {
            return displays;
        }

        public void clearDisplays() {
            displays.clear();
        }

        public void addAnswerDisplay(TextDisplay display) {
            if (display != null) {
                answerDisplays.add(display);
            }
        }

        public List<TextDisplay> getAnswerDisplays() {
            return answerDisplays;
        }

        public void clearAnswerDisplays() {
            answerDisplays.clear();
        }

        public BukkitTask getCameraTask() {
            return cameraTask;
        }

        public void setCameraTask(BukkitTask task) {
            cameraTask = task;
        }

        public boolean hadPreviousAllowFlight() {
            return previousAllowFlight;
        }

        public void setPreviousAllowFlight(boolean value) {
            previousAllowFlight = value;
        }

        public boolean wasPreviousFlying() {
            return previousFlying;
        }

        public void setPreviousFlying(boolean value) {
            previousFlying = value;
        }

        public float getPreviousWalkSpeed() {
            return previousWalkSpeed;
        }

        public void setPreviousWalkSpeed(float value) {
            previousWalkSpeed = value;
        }

        public float getPreviousFlySpeed() {
            return previousFlySpeed;
        }

        public void setPreviousFlySpeed(float value) {
            previousFlySpeed = value;
        }

        public boolean hasPreviousGravity() {
            return previousGravity;
        }

        public void setPreviousGravity(boolean value) {
            previousGravity = value;
        }
    }
    /*
     * ============================================================
     * SOUND REGISTRY LOOKUP
     * ============================================================
     */

    private static final class RegistryLookup {

        private RegistryLookup() {
        }

        private static Sound findSound(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }

            String key = input.trim().toLowerCase();

            if (key.startsWith("minecraft:")) {
                key = key.substring("minecraft:".length());
            }

            try {
                org.bukkit.NamespacedKey namespacedKey =
                        org.bukkit.NamespacedKey.minecraft(key);

                return org.bukkit.Registry.SOUNDS.get(
                        namespacedKey
                );

            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }
}
