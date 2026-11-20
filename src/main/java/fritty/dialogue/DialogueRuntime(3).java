package fritty.dialogue;

import fritty.dialogue.DialogueCore.Answer;
import fritty.dialogue.DialogueCore.Condition;
import fritty.dialogue.DialogueCore.ConditionGroup;
import fritty.dialogue.DialogueCore.ConditionType;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Runs the dialogue graph defined by DialogueEditor and stored by DialogueCore. */
public final class DialogueRuntime implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final FrittyDialogue plugin;
    private final DialogueCore core;
    private final Map<UUID, Session> sessions = new java.util.HashMap<>();
    private boolean initialized;
    private boolean shuttingDown;

    public DialogueRuntime(FrittyDialogue plugin, DialogueCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    public void initialize() {
        if (initialized) return;
        shuttingDown = false;
        initialized = true;
    }

    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            Session session = sessions.remove(id);
            if (session == null) continue;
            if (player != null && player.isOnline()) restore(player, session);
            else session.cancelNodeTasks();
        }
        initialized = false;
    }

    public boolean isRunning(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    public int getActiveSessionCount() { return sessions.size(); }

    public boolean start(Player player, String dialogueId) {
        if (!initialized || shuttingDown || player == null || !player.isOnline()) return false;
        if (isRunning(player)) stop(player);
        Dialogue dialogue = core.getDialogue(dialogueId);
        if (dialogue == null) {
            player.sendMessage(Component.text("Діалог не знайдено.", NamedTextColor.RED));
            return false;
        }
        if (dialogue.getSettings().getBoolean("one-time", false)
                && core.isDialogueCompleted(player.getUniqueId(), dialogue.getId())) {
            player.sendMessage(Component.text("Цей діалог уже пройдено.", NamedTextColor.RED));
            return false;
        }
        Node first = resolveStart(player, dialogue);
        if (first == null) {
            player.sendMessage(Component.text("Початкова гілка недоступна.", NamedTextColor.RED));
            return false;
        }
        Session session = new Session(dialogue);
        sessions.put(player.getUniqueId(), session);
        try {
            preparePlayer(player, session);
            session.node = first;
            enterNode(player, session, true);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not start dialogue " + dialogueId, ex);
            stop(player);
            return false;
        }
    }

    public boolean stop(Player player) {
        if (player == null) return false;
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return false;
        restore(player, session);
        return true;
    }

    private Node resolveStart(Player player, Dialogue dialogue) {
        String saved = core.getCurrentStoryStage(player.getUniqueId(), dialogue.getId());
        if (saved != null && !saved.isBlank() && !saved.equalsIgnoreCase("completed")) {
            Node stage = dialogue.getNode(saved);
            if (stage != null && canEnter(player, dialogue, stage)) return stage;
        }
        Node root = core.getRootNode(dialogue.getId());
        return root != null && canEnter(player, dialogue, root) ? root : null;
    }

    private void preparePlayer(Player player, Session s) {
        s.originalLocation = player.getLocation().clone();
        s.allowFlight = player.getAllowFlight();
        s.flying = player.isFlying();
        s.walkSpeed = player.getWalkSpeed();
        s.flySpeed = player.getFlySpeed();
        s.gravity = player.hasGravity();
        s.invisible = player.isInvisible();
        s.invulnerable = player.isInvulnerable();
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.setWalkSpeed(0);
        player.setFlySpeed(0);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setGravity(false);
        player.setInvulnerable(true);
        if (s.dialogue.getSettings().getBoolean("invisibility", false)) player.setInvisible(true);
        if (s.dialogue.getSettings().getBoolean("hide-players", false)) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player) || !player.canSee(other)) continue;
                player.hidePlayer(plugin, other);
                s.hiddenPlayers.add(other.getUniqueId());
            }
        }
    }

    private void enterNode(Player player, Session s, boolean initial) {
        if (sessions.get(player.getUniqueId()) != s || s.node == null) return;
        s.cancelNodeTasks();
        s.settings = core.getEffectiveSettings(s.dialogue, s.node);
        s.selected = firstAvailable(player, s);
        s.ready = false;
        s.typing = false;
        core.setCurrentStoryStage(player.getUniqueId(), s.dialogue.getId(), s.node.getId());

        Location camera = s.settings.getCameraLocation();
        if (camera == null) camera = s.cameraLocation;
        if (camera == null) camera = s.originalLocation;
        if (camera != null && camera.getWorld() != null) {
            s.cameraLocation = camera.clone();
            if (initial || s.settings.getCameraLocation() != null) {
                player.teleport(camera, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }

        Location textAt = resolveTextLocation(player, s);
        s.branchDisplay = spawnDisplay(player, textAt, "", s.settings, false);
        s.displays.add(s.branchDisplay);
        int[] chars = s.node.getName().codePoints().toArray();
        s.fullText = s.node.getName();
        beginTyping(player, s, chars);
    }

    private Location resolveTextLocation(Player player, Session s) {
        Location configured = parseLocation(s.settings.get("text-location"));
        if (configured != null && configured.getWorld() != null) return configured;
        Location base = s.cameraLocation == null ? player.getLocation().clone() : s.cameraLocation.clone();
        return base.add(0, player.getEyeHeight(), 0);
    }

    private Location parseLocation(Object raw) {
        if (raw instanceof Location loc) return loc.clone();
        if (raw instanceof Map<?, ?> map) {
            Object worldValue = map.get("world");
            World world = worldValue == null ? null : Bukkit.getWorld(String.valueOf(worldValue));
            if (world == null) return null;
            try {
                return new Location(world, number(map.get("x"), 0), number(map.get("y"), 0),
                        number(map.get("z"), 0), (float) number(map.get("yaw"), 0),
                        (float) number(map.get("pitch"), 0));
            } catch (RuntimeException ex) { return null; }
        }
        if (raw instanceof String value) {
            String[] p = value.trim().replace(',', ' ').split("\\s+");
            if (p.length >= 4) {
                World world = Bukkit.getWorld(p[0]);
                if (world == null) return null;
                try {
                    return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                            Double.parseDouble(p[3]), p.length > 4 ? Float.parseFloat(p[4]) : 0,
                            p.length > 5 ? Float.parseFloat(p[5]) : 0);
                } catch (NumberFormatException ignored) { return null; }
            }
        }
        return null;
    }

    private TextDisplay spawnDisplay(Player viewer, Location at, String text, Settings style, boolean answer) {
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setText(LEGACY.serialize(render(text, color(style.getString("text-color", "#FFFFFF")))));
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(clampInt(style.getInt("window-width", answer ? 200 : 300), 1, 4096));
            d.setBackgroundColor(background(style));
            d.setDefaultBackground(false);
            d.setSeeThrough(false);
            d.setShadowed(style.getBoolean("text-shadow", true));
            d.setTextOpacity((byte) 255);
            float scale = (float) clamp(style.getDouble("text-scale", 1), .1, 10);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setPersistent(false);
        });
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(viewer)) other.hideEntity(plugin, display);
        viewer.showEntity(plugin, display);
        return display;
    }

    private Component render(String text, TextColor color) {
        Component component = LEGACY.deserialize(text == null ? "" : text);
        if (color != null) component = component.color(color);
        return component.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    private TextColor color(String value) {
        if (value == null) return NamedTextColor.WHITE;
        try {
            if (value.startsWith("#")) return TextColor.fromHexString(value);
            TextColor named = NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
            return named == null ? NamedTextColor.WHITE : named;
        } catch (RuntimeException ex) { return NamedTextColor.WHITE; }
    }

    private Color background(Settings settings) {
        String value = settings.getString("background-color", "#000000");
        int alpha = clampInt(settings.getInt("background-alpha", 110), 0, 255);
        if (value == null || value.equalsIgnoreCase("none")) return Color.fromARGB(0, 0, 0, 0);
        TextColor c = color(value);
        return Color.fromARGB(alpha, c.red(), c.green(), c.blue());
    }

    /** typing-speed is the total duration, in seconds, for the complete branch text. */
    private void beginTyping(Player player, Session s, int[] chars) {
        s.codePoints = chars;
        s.typeIndex = 0;
        double seconds = s.settings.getDouble("typing-speed", 5.0);
        if (chars.length == 0 || seconds <= 0) {
            setBranchText(s, chars.length);
            afterTyping(player, s);
            return;
        }
        s.typing = true;
        s.ready = false;
        s.startedAt = Bukkit.getCurrentTick();
        s.durationTicks = Math.max(1L, Math.round(seconds * 20.0));
        s.typeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != s || !player.isOnline()) {
                s.cancelTypeTask();
                return;
            }
            long elapsed = Math.max(0, Bukkit.getCurrentTick() - s.startedAt);
            double progress = Math.min(1.0, (double) elapsed / s.durationTicks);
            int target = Math.min(chars.length, (int) Math.ceil(progress * chars.length));
            if (target > s.typeIndex) {
                s.typeIndex = target;
                setBranchText(s, target);
                playTypingSound(player, s.settings);
            }
            if (progress >= 1.0 || s.typeIndex >= chars.length) {
                s.cancelTypeTask();
                s.typing = false;
                afterTyping(player, s);
            }
        }, 1L, 1L);
        updateActionBar(player, s);
    }

    private void setBranchText(Session s, int count) {
        if (s.branchDisplay == null || !s.branchDisplay.isValid()) return;
        String visible = new String(s.codePoints, 0, Math.min(count, s.codePoints.length));
        s.branchDisplay.setText(LEGACY.serialize(render(visible, color(s.settings.getString("text-color", "#FFFFFF")))));
    }

    public boolean skipTyping(Player player) {
        Session s = player == null ? null : sessions.get(player.getUniqueId());
        if (s == null || !s.typing) return false;
        s.cancelTypeTask();
        s.typing = false;
        setBranchText(s, s.codePoints.length);
        afterTyping(player, s);
        return true;
    }

    private void afterTyping(Player player, Session s) {
        if (sessions.get(player.getUniqueId()) != s) return;
        double delay = Math.max(0, s.settings.getDouble("delay", 0));
        if (delay > 0) {
            s.delayTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sessions.get(player.getUniqueId()) == s) makeReady(player, s);
            }, Math.max(1L, Math.round(delay * 20)));
        } else makeReady(player, s);
    }

    private void makeReady(Player player, Session s) {
        if (sessions.get(player.getUniqueId()) != s) return;
        s.delayTask = null;
        s.ready = true;
        renderAnswers(player, s);
        updateActionBar(player, s);
    }

    private void renderAnswers(Player player, Session s) {
        clearAnswerDisplays(s);
        List<Answer> answers = s.node.getAnswers();
        if (answers.isEmpty()) return;
        double spacing = clamp(s.settings.getDouble("answer-spacing", .32), .08, 4);
        Location anchor = resolveTextLocation(player, s);
        double start = -((answers.size() - 1) * spacing) / 2.0;
        for (int i = 0; i < answers.size(); i++) {
            Answer answer = answers.get(i);
            boolean available = isAnswerAvailable(player, s, answer);
            String mode = answer.getSettings().getString("unavailable-answer-mode",
                    s.settings.getString("unavailable-answer-mode", "HIDE"));
            if (!available && mode.equalsIgnoreCase("HIDE")) continue;
            boolean selected = available && i == s.selected;
            String prefix = selected ? s.settings.getString("answer-selected-prefix", "» ")
                    : s.settings.getString("answer-prefix", "  ");
            String suffix = selected ? s.settings.getString("answer-selected-suffix", " «") : "";
            String text = prefix + answer.getText() + suffix;
            if (!available && mode.equalsIgnoreCase("DISABLED")) text = "§8§m" + answer.getText() + " §cНедоступно";

            // Answer style is independent of branch/dialogue style; defaults are applied here.
            Settings style = answer.getSettings();
            Location at = anchor.clone().add(0, start - ((i + 1) * spacing), 0);
            at.add(parseOffset(style.getString("offset", "0 0 0")));
            TextDisplay display = spawnDisplay(player, at, text, answerStyle(style), true);
            s.answerDisplays.add(display);
            s.displays.add(display);
        }
    }

    private Settings answerStyle(Settings source) {
        Settings style = new Settings();
        style.set("text-color", source.getString("text-color", "#FFFFFF"));
        style.set("background-color", source.getString("background-color", "#000000"));
        style.set("background-alpha", source.getInt("background-alpha", 110));
        style.set("text-scale", source.getDouble("text-scale", 1.0));
        style.set("window-width", source.getInt("window-width", 200));
        style.set("text-shadow", source.getBoolean("text-shadow", true));
        return style;
    }

    private Vector parseOffset(String raw) {
        if (raw == null || raw.isBlank()) return new Vector();
        String[] p = raw.trim().replace(',', ' ').split("\\s+");
        if (p.length != 3) return new Vector();
        try { return new Vector(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])); }
        catch (NumberFormatException ex) { return new Vector(); }
    }

    private void clearAnswerDisplays(Session s) {
        for (TextDisplay d : new ArrayList<>(s.answerDisplays)) {
            if (d.isValid()) d.remove();
            s.displays.remove(d);
        }
        s.answerDisplays.clear();
    }

    private int firstAvailable(Player player, Session s) {
        for (int i = 0; i < s.node.getAnswers().size(); i++)
            if (isAnswerAvailable(player, s, s.node.getAnswers().get(i))) return i;
        return -1;
    }

    private boolean hasAvailable(Player player, Session s) { return firstAvailable(player, s) >= 0; }

    private boolean isAnswerAvailable(Player player, Session s, Answer answer) {
        return answer != null && answer.isEnabled()
                && evaluateGroup(player, s.dialogue, s.node, answer.getConditions());
    }

    private boolean canEnter(Player player, Dialogue dialogue, Node node) {
        return evaluateGroup(player, dialogue, node, node.getConditionGroup());
    }

    private boolean evaluateGroup(Player player, Dialogue dialogue, Node current, ConditionGroup group) {
        if (group == null || group.isEmpty()) return true;
        boolean or = group.getLogic().equalsIgnoreCase("OR");
        for (Condition condition : group.getConditions()) {
            boolean passed = evaluateCondition(player, dialogue, current, condition);
            if (or && passed) return true;
            if (!or && !passed) return false;
        }
        return !or;
    }

    private boolean evaluateCondition(Player player, Dialogue dialogue, Node current, Condition condition) {
        if (condition == null) return false;
        String value = condition.getValue() == null ? "" : condition.getValue().trim();
        String op = condition.getOperator() == null ? "equals" : condition.getOperator().toLowerCase(Locale.ROOT);
        return switch (condition.getType()) {
            case PERMISSION -> compareBoolean(player.hasPermission(value), op);
            case TAG -> compareBoolean(player.getScoreboardTags().contains(value), op);
            case DIALOGUE_COMPLETED -> compareBoolean(core.isDialogueCompleted(player.getUniqueId(), value), op);
            case COMPLETED_BRANCH, BRANCH_COMPLETED -> {
                String[] p = value.split(":", 2);
                String dialogueId = p.length == 2 ? p[0] : dialogue.getId();
                String branchId = p.length == 2 ? p[1] : p[0];
                yield compareBoolean(core.isBranchCompleted(player.getUniqueId(), dialogueId, branchId), op);
            }
            case ANSWER_SELECTED -> {
                String[] p = value.split(":", 2);
                String branchId = p.length == 2 ? p[0] : current.getId();
                String answerId = p.length == 2 ? p[1] : p[0];
                yield compareBoolean(core.wasAnswerSelected(player.getUniqueId(), dialogue.getId(), branchId, answerId), op);
            }
            case LEVEL -> compareNumber(player.getLevel(), value, op);
            case LUCKPERMS_GROUP -> compareBoolean(hasLuckPermsGroup(player, value), op);
            case ITEM -> compareBoolean(hasItem(player, value), op);
            case MINECRAFT_TAG -> compareBoolean(hasItemTag(player, value), op);
            case UNKNOWN -> false;
        };
    }

    private boolean compareBoolean(boolean actual, String op) {
        return switch (op) {
            case "not", "not_equals", "!=", "false", "is_false" -> !actual;
            default -> actual;
        };
    }

    private boolean compareNumber(double actual, String expected, String op) {
        try {
            double n = Double.parseDouble(expected);
            return switch (op) {
                case "greater", "greater_than", ">" -> actual > n;
                case "greater_or_equal", ">=" -> actual >= n;
                case "less", "less_than", "<" -> actual < n;
                case "less_or_equal", "<=" -> actual <= n;
                case "not_equals", "!=" -> actual != n;
                default -> actual == n;
            };
        } catch (NumberFormatException ex) { return false; }
    }

    private boolean hasItem(Player player, String input) {
        String[] p = input.split(":", 2);
        Material material = Material.matchMaterial(p[0].trim());
        if (material == null) return false;
        int required = 1;
        if (p.length == 2) try { required = Math.max(1, Integer.parseInt(p[1].trim())); }
        catch (NumberFormatException ex) { return false; }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && item.getType() == material) count += item.getAmount();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && off.getType() == material) count += off.getAmount();
        return count >= required;
    }

    private boolean hasItemTag(Player player, String raw) {
        try {
            NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT))
                    : NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT));
            if (key == null) return false;
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) return false;
            for (ItemStack item : player.getInventory().getContents())
                if (item != null && !item.getType().isAir() && tag.isTagged(item.getType())) return true;
            ItemStack off = player.getInventory().getItemInOffHand();
            return off != null && !off.getType().isAir() && tag.isTagged(off.getType());
        } catch (RuntimeException ex) { return false; }
    }

    private boolean hasLuckPermsGroup(Player player, String groupName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return false;
        try {
            LuckPerms lp = LuckPermsProvider.get();
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) return false;
            Optional<QueryOptions> options = lp.getContextManager().getQueryOptions(user);
            QueryOptions query = options.orElseGet(lp.getContextManager()::getStaticQueryOptions);
            return user.getPrimaryGroup().equalsIgnoreCase(groupName)
                    || user.getInheritedGroups(query).stream().anyMatch(g -> g.getName().equalsIgnoreCase(groupName));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "LuckPerms condition check failed", ex);
            return false;
        }
    }

    public boolean moveSelection(Player player, int direction) {
        Session s = player == null ? null : sessions.get(player.getUniqueId());
        if (s == null || s.typing || !s.ready || s.node.getAnswers().isEmpty()) return false;
        int count = s.node.getAnswers().size();
        int index = s.selected < 0 ? (direction < 0 ? 0 : -1) : s.selected;
        for (int i = 0; i < count; i++) {
            index = (index + (direction < 0 ? -1 : 1) + count) % count;
            if (isAnswerAvailable(player, s, s.node.getAnswers().get(index))) {
                s.selected = index;
                renderAnswers(player, s);
                updateActionBar(player, s);
                return true;
            }
        }
        return false;
    }

    public boolean confirmSelection(Player player) {
        Session s = player == null ? null : sessions.get(player.getUniqueId());
        if (s == null) return false;
        if (s.typing) return skipTyping(player);
        if (!s.ready) return false;
        if (!hasAvailable(player, s)) return continueDialogue(player);
        int selected = s.selected;
        if (selected < 0 || selected >= s.node.getAnswers().size()
                || !isAnswerAvailable(player, s, s.node.getAnswers().get(selected))) selected = firstAvailable(player, s);
        return selected >= 0 && chooseAnswer(player, selected);
    }

    public boolean chooseAnswer(Player player, int index) {
        Session s = player == null ? null : sessions.get(player.getUniqueId());
        if (s == null || s.typing || !s.ready || index < 0 || index >= s.node.getAnswers().size()) return false;
        Answer answer = s.node.getAnswers().get(index);
        if (!isAnswerAvailable(player, s, answer)) return false;
        String target = answer.getTargetNode();
        if ((target == null || target.isBlank()) && s.node.getNextNode() != null) target = s.node.getNextNode();
        Node next = target == null || target.isBlank() ? null : s.dialogue.getNode(target);
        if (target != null && !target.isBlank() && (next == null || !canEnter(player, s.dialogue, next))) {
            player.sendMessage(Component.text("Наступна гілка недоступна.", NamedTextColor.RED));
            return false;
        }
        core.markAnswerSelected(player.getUniqueId(), s.dialogue.getId(), s.node.getId(), answer.getId());
        playSound(player, answer.getSound(), (float) answer.getSettings().getDouble("sound-volume", 1),
                (float) answer.getSettings().getDouble("sound-pitch", 1));
        executeCommands(player, answer.getCommands(), s, answer.getId());
        giveRewards(player, answer.getRewards(), s, answer.getId());
        if (sessions.get(player.getUniqueId()) != s) return false;
        return next == null ? finishDialogue(player, s) : transition(player, s, next);
    }

    private boolean continueDialogue(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return false;
        String target = s.node.getNextNode();
        if (target != null && !target.isBlank()) {
            Node next = s.dialogue.getNode(target);
            if (next == null || !canEnter(player, s.dialogue, next)) {
                player.sendMessage(Component.text("Наступна гілка недоступна.", NamedTextColor.RED));
                return false;
            }
            return transition(player, s, next);
        }
        return finishDialogue(player, s);
    }

    private boolean transition(Player player, Session s, Node next) {
        if (sessions.get(player.getUniqueId()) != s) return false;
        completeBranch(player, s);
        if (sessions.get(player.getUniqueId()) != s) return false;
        s.node = next;
        enterNode(player, s, false);
        return true;
    }

    private void completeBranch(Player player, Session s) {
        core.markBranchCompleted(player.getUniqueId(), s.dialogue.getId(), s.node.getId());
        executeCommands(player, s.node.getCommands(), s, null);
        giveRewards(player, s.node.getRewards(), s, null);
    }

    private boolean finishDialogue(Player player, Session s) {
        if (sessions.get(player.getUniqueId()) != s) return false;
        completeBranch(player, s);
        core.markDialogueCompleted(player.getUniqueId(), s.dialogue.getId());
        core.setCurrentStoryStage(player.getUniqueId(), s.dialogue.getId(), "completed");
        stop(player);
        return true;
    }

    private void executeCommands(Player player, List<String> commands, Session s, String answerId) {
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String command = raw.trim().replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{dialogue}", s.dialogue.getId())
                    .replace("{branch}", s.node == null ? "" : s.node.getId())
                    .replace("{answer}", answerId == null ? "" : answerId);
            if (command.startsWith("/")) command = command.substring(1);
            if (!command.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private void giveRewards(Player player, List<Reward> rewards, Session s, String answerId) {
        for (Reward reward : rewards) {
            if (reward == null) continue;
            String type = reward.getType() == null ? "item" : reward.getType().toLowerCase(Locale.ROOT);
            int amount = Math.max(1, reward.getAmount());
            switch (type) {
                case "item" -> {
                    ItemStack item = reward.getItem();
                    if (item == null || item.getType().isAir()) {
                        Material material = Material.matchMaterial(reward.getValue());
                        if (material == null) { plugin.getLogger().warning("Unknown reward item: " + reward.getValue()); continue; }
                        item = new ItemStack(material);
                    }
                    item.setAmount(amount);
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                    for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                case "experience", "exp" -> player.giveExp(amount);
                case "levels", "level" -> player.giveExpLevels(amount);
                case "command" -> executeCommands(player, List.of(reward.getValue()), s, answerId);
                default -> plugin.getLogger().warning("Unsupported reward type: " + type);
            }
        }
    }

    private void playTypingSound(Player player, Settings settings) {
        String name = settings.getString("typing-sound", null);
        if (name != null && !name.isBlank()) playSound(player, name,
                (float) settings.getDouble("sound-volume", 1), (float) settings.getDouble("sound-pitch", 1));
    }

    private void playSound(Player player, String name, float volume, float pitch) {
        if (player == null || !player.isOnline() || name == null || name.isBlank()) return;
        try {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(key));
            if (sound != null) player.playSound(player.getLocation(), sound,
                    (float) clamp(volume, 0, 2), (float) clamp(pitch, .5, 2));
        } catch (RuntimeException ignored) { plugin.getLogger().fine("Unknown dialogue sound: " + name); }
    }

    private void updateActionBar(Player player, Session s) {
        if (s.typing) player.sendActionBar(Component.text("F — пропустити текст", NamedTextColor.YELLOW));
        else if (!s.ready) player.sendActionBar(Component.text("…", NamedTextColor.GRAY));
        else if (!hasAvailable(player, s)) player.sendActionBar(Component.text("F — продовжити", NamedTextColor.YELLOW));
        else player.sendActionBar(Component.text("W / S — вибрати відповідь    F — підтвердити", NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        boolean forward = event.getInput().isForward();
        boolean backward = event.getInput().isBackward();
        boolean forwardPressed = forward && !s.forward;
        boolean backwardPressed = backward && !s.backward;
        s.forward = forward;
        s.backward = backward;
        if (s.typing || !s.ready) return;
        if (forwardPressed && !backward) moveSelection(event.getPlayer(), -1);
        else if (backwardPressed && !forward) moveSelection(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!isRunning(event.getPlayer())) return;
        event.setCancelled(true);
        confirmSelection(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isRunning(event.getPlayer()) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.getX() != to.getX() || from.getY() != to.getY()
                || from.getZ() != to.getZ() || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isRunning(event.getPlayer()) && event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && isRunning(p)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) { if (isRunning(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p && isRunning(p)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && isRunning(p)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && isRunning(p)) event.setCancelled(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) { s.cancelNodeTasks(); removeDisplays(s); }
    }

    private void restore(Player player, Session s) {
        s.cancelNodeTasks();
        for (Entity entity : new ArrayList<>(s.displays)) if (entity.isValid()) entity.remove();
        s.displays.clear();
        for (UUID id : s.hiddenPlayers) {
            Player other = Bukkit.getPlayer(id);
            if (other != null && other.isOnline()) player.showPlayer(plugin, other);
        }
        player.setWalkSpeed(s.walkSpeed);
        player.setFlySpeed(s.flySpeed);
        player.setGravity(s.gravity);
        player.setInvisible(s.invisible);
        player.setInvulnerable(s.invulnerable);
        player.setAllowFlight(s.allowFlight);
        if (s.flying && s.allowFlight) player.setFlying(true);
        else if (player.isFlying()) player.setFlying(false);
        if (s.dialogue.getSettings().getBoolean("return-to-start", true)
                && s.originalLocation != null && s.originalLocation.getWorld() != null) {
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(s.originalLocation, PlayerTeleportEvent.TeleportCause.PLUGIN));
        }
        player.sendActionBar(Component.empty());
    }

    private void removeDisplays(Session s) {
        for (Entity entity : new ArrayList<>(s.displays)) if (entity.isValid()) entity.remove();
        s.displays.clear();
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class Session {
        private final Dialogue dialogue;
        private final Set<UUID> hiddenPlayers = new HashSet<>();
        private final List<Entity> displays = new ArrayList<>();
        private final List<TextDisplay> answerDisplays = new ArrayList<>();
        private Location originalLocation;
        private Location cameraLocation;
        private Node node;
        private Settings settings;
        private TextDisplay branchDisplay;
        private String fullText;
        private int[] codePoints = new int[0];
        private int typeIndex;
        private long startedAt;
        private long durationTicks;
        private boolean typing;
        private boolean ready;
        private boolean forward;
        private boolean backward;
        private int selected = -1;
        private boolean allowFlight;
        private boolean flying;
        private float walkSpeed;
        private float flySpeed;
        private boolean gravity;
        private boolean invisible;
        private boolean invulnerable;
        private BukkitTask typeTask;
        private BukkitTask delayTask;

        private Session(Dialogue dialogue) { this.dialogue = dialogue; }
        private void cancelTypeTask() { if (typeTask != null) { typeTask.cancel(); typeTask = null; } }
        private void cancelNodeTasks() {
            cancelTypeTask();
            if (delayTask != null) { delayTask.cancel(); delayTask = null; }
        }
    }
}
