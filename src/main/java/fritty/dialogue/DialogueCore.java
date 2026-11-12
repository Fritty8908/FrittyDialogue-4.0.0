package fritty.dialogue;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class DialogueCore {

    private final FrittyDialogue plugin;

    private Connection database;

    private final Map<String, Dialogue> dialogues =
            new LinkedHashMap<>();

    private boolean initialized;
    private boolean shuttingDown;

    public DialogueCore(FrittyDialogue plugin) {
        this.plugin = plugin;
    }

    /*
     * ============================================================
     * INITIALIZATION
     * ============================================================
     */

    public boolean initialize() {
        if (initialized) {
            return true;
        }

        try {
            File folder = plugin.getDataFolder();

            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException(
                        "Could not create plugin data folder."
                );
            }

            File databaseFile = new File(
                    folder,
                    "frittydialogue.db"
            );

            database = DriverManager.getConnection(
                    "jdbc:sqlite:" + databaseFile.getAbsolutePath()
            );

            createDatabaseTables();
            loadAllDialogues();

            initialized = true;

            plugin.getLogger().info(
                    "Loaded " + dialogues.size()
                            + " dialogue(s)."
            );

            return true;

        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not initialize DialogueCore.",
                    exception
            );

            closeDatabase();
            return false;
        }
    }

    private void createDatabaseTables() throws SQLException {
        try (Statement statement = database.createStatement()) {

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS dialogues ("
                            + "id TEXT PRIMARY KEY,"
                            + "name TEXT NOT NULL,"
                            + "data TEXT NOT NULL"
                            + ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_progress ("
                            + "player_uuid TEXT NOT NULL,"
                            + "dialogue_id TEXT NOT NULL,"
                            + "completed INTEGER NOT NULL DEFAULT 0,"
                            + "completed_at INTEGER NOT NULL DEFAULT 0,"
                            + "PRIMARY KEY (player_uuid, dialogue_id)"
                            + ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS branch_progress ("
                            + "player_uuid TEXT NOT NULL,"
                            + "dialogue_id TEXT NOT NULL,"
                            + "branch_id TEXT NOT NULL,"
                            + "completed INTEGER NOT NULL DEFAULT 0,"
                            + "completed_at INTEGER NOT NULL DEFAULT 0,"
                            + "PRIMARY KEY (player_uuid, dialogue_id, branch_id)"
                            + ")"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS answer_progress ("
                            + "player_uuid TEXT NOT NULL,"
                            + "dialogue_id TEXT NOT NULL,"
                            + "branch_id TEXT NOT NULL,"
                            + "answer_id TEXT NOT NULL,"
                            + "selected INTEGER NOT NULL DEFAULT 0,"
                            + "selected_at INTEGER NOT NULL DEFAULT 0,"
                            + "PRIMARY KEY (player_uuid, dialogue_id, branch_id, answer_id)"
                            + ")"
            );
        }
    }

    private void loadAllDialogues() throws SQLException {
        dialogues.clear();

        try (PreparedStatement statement =
                     database.prepareStatement(
                             "SELECT id, name, data FROM dialogues"
                     );
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                String id = result.getString("id");
                String name = result.getString("name");
                String data = result.getString("data");

                try {
                    Dialogue dialogue = deserializeDialogue(
                            id,
                            name,
                            data
                    );

                    if (dialogue != null) {
                        dialogues.put(id, dialogue);
                    }

                } catch (Exception exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not load dialogue '" + id + "'.",
                            exception
                    );
                }
            }
        }
    }

    /*
     * ============================================================
     * SHUTDOWN
     * ============================================================
     */

    public void shutdown() {
        if (shuttingDown) {
            return;
        }

        shuttingDown = true;

        try {
            saveAllDialogues();
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not save dialogues during shutdown.",
                    exception
            );
        }

        closeDatabase();

        initialized = false;
    }

    private void closeDatabase() {
        if (database == null) {
            return;
        }

        try {
            database.close();
        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not close SQLite database.",
                    exception
            );
        }

        database = null;
    }

    /*
     * ============================================================
     * DIALOGUE ACCESS
     * ============================================================
     */

    public int getDialogueCount() {
        return dialogues.size();
    }

    public List<String> getDialogueIds() {
        return new ArrayList<>(dialogues.keySet());
    }

    public Collection<Dialogue> getDialogues() {
        return Collections.unmodifiableCollection(
                dialogues.values()
        );
    }

    public Dialogue getDialogue(String id) {
        if (id == null) {
            return null;
        }

        return dialogues.get(normalizeId(id));
    }

    public boolean hasDialogue(String id) {
        return getDialogue(id) != null;
    }

    public boolean createDialogue(
            String name,
            String copyFrom
    ) {
        if (!isValidId(name)) {
            return false;
        }

        String id = normalizeId(name);

        if (dialogues.containsKey(id)) {
            return false;
        }

        Dialogue dialogue = new Dialogue(
                id,
                name.trim()
        );

        dialogue.setRootNode("start");

        if (copyFrom != null && !copyFrom.isBlank()) {
            Dialogue source = getDialogue(copyFrom);

            if (source == null) {
                return false;
            }

            dialogue = source.copy(id, name.trim());
        }

        if (!saveDialogue(dialogue)) {
            return false;
        }

        dialogues.put(id, dialogue);

        return true;
    }

    public boolean deleteDialogue(String id) {
        String normalized = normalizeId(id);

        if (!dialogues.containsKey(normalized)) {
            return false;
        }

        try {
            try (PreparedStatement statement =
                         database.prepareStatement(
                                 "DELETE FROM dialogues WHERE id = ?"
                         )) {
                statement.setString(1, normalized);
                statement.executeUpdate();
            }

            dialogues.remove(normalized);

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not delete dialogue '" + normalized + "'.",
                    exception
            );

            return false;
        }
    }

    public boolean reloadDialogue(String id) {
        String normalized = normalizeId(id);

        try (PreparedStatement statement =
                     database.prepareStatement(
                             "SELECT id, name, data FROM dialogues WHERE id = ?"
                     )) {

            statement.setString(1, normalized);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    dialogues.remove(normalized);
                    return false;
                }

                Dialogue dialogue = deserializeDialogue(
                        result.getString("id"),
                        result.getString("name"),
                        result.getString("data")
                );

                if (dialogue == null) {
                    return false;
                }

                dialogues.put(normalized, dialogue);

                return true;
            }

        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not reload dialogue '" + normalized + "'.",
                    exception
            );

            return false;
        }
    }

    public boolean reloadAll() {
        try {
            loadAllDialogues();
            return true;

        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not reload dialogues.",
                    exception
            );

            return false;
        }
    }

    /*
     * ============================================================
     * SAVE OPERATIONS
     * ============================================================
     */

    public boolean saveDialogue(Dialogue dialogue) {
        if (dialogue == null || database == null) {
            return false;
        }

        try {
            String serialized = serializeDialogue(dialogue);

            try (PreparedStatement statement =
                         database.prepareStatement(
                                 "INSERT INTO dialogues (id, name, data) "
                                         + "VALUES (?, ?, ?) "
                                         + "ON CONFLICT(id) DO UPDATE SET "
                                         + "name = excluded.name, "
                                         + "data = excluded.data"
                         )) {

                statement.setString(1, dialogue.getId());
                statement.setString(2, dialogue.getName());
                statement.setString(3, serialized);

                statement.executeUpdate();
            }

            dialogues.put(dialogue.getId(), dialogue);

            return true;

        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not save dialogue '"
                            + dialogue.getId() + "'.",
                    exception
            );

            return false;
        }
    }

    public boolean saveAllDialogues() {
        boolean success = true;

        for (Dialogue dialogue :
                new ArrayList<>(dialogues.values())) {

            if (!saveDialogue(dialogue)) {
                success = false;
            }
        }

        return success;
    }

    /*
     * ============================================================
     * CAMERA LOCATION
     * ============================================================
     */

    public boolean setCameraLocation(
            String id,
            Location feetLocation
    ) {
        Dialogue dialogue = getDialogue(id);

        if (dialogue == null || feetLocation == null
                || feetLocation.getWorld() == null) {
            return false;
        }

        dialogue.getSettings().setCameraLocation(
                feetLocation.clone()
        );

        return saveDialogue(dialogue);
    }

    /*
     * ============================================================
     * ROOT NODE AND BRANCH ACCESS
     * ============================================================
     */

    public Node getRootNode(String dialogueId) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null) {
            return null;
        }

        return dialogue.getNode(
                dialogue.getRootNode()
        );
    }

    public Node getNode(
            String dialogueId,
            String nodeId
    ) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null) {
            return null;
        }

        return dialogue.getNode(nodeId);
    }

    public boolean setRootNode(
            String dialogueId,
            String nodeId
    ) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null
                || dialogue.getNode(nodeId) == null) {
            return false;
        }

        dialogue.setRootNode(nodeId);

        return saveDialogue(dialogue);
    }

    public boolean createNode(
            String dialogueId,
            String nodeId,
            String displayName
    ) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null || !isValidId(nodeId)
                || dialogue.getNode(nodeId) != null) {
            return false;
        }

        Node node = new Node(
                normalizeId(nodeId),
                displayName == null || displayName.isBlank()
                        ? nodeId
                        : displayName.trim()
        );

        dialogue.getNodes().put(
                node.getId(),
                node
        );

        if (dialogue.getRootNode() == null
                || dialogue.getRootNode().isBlank()) {
            dialogue.setRootNode(node.getId());
        }

        return saveDialogue(dialogue);
    }

    public boolean deleteNode(
            String dialogueId,
            String nodeId
    ) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null || nodeId == null) {
            return false;
        }

        String normalized = normalizeId(nodeId);

        if (!dialogue.getNodes().containsKey(normalized)) {
            return false;
        }

        dialogue.getNodes().remove(normalized);

        if (normalized.equals(dialogue.getRootNode())) {
            dialogue.setRootNode(
                    dialogue.getNodes().isEmpty()
                            ? null
                            : dialogue.getNodes().keySet()
                                    .iterator().next()
            );
        }

        for (Node node : dialogue.getNodes().values()) {
            if (normalized.equals(node.getNextNode())) {
                node.setNextNode(null);
            }

            for (Answer answer : node.getAnswers()) {
                if (normalized.equals(answer.getTargetNode())) {
                    answer.setTargetNode(null);
                }
            }
        }

        return saveDialogue(dialogue);
    }

    /*
     * ============================================================
     * SETTINGS INHERITANCE
     * ============================================================
     */

    public Settings getEffectiveSettings(
            Dialogue dialogue,
            Node node
    ) {
        if (dialogue == null) {
            return new Settings();
        }

        Settings effective = dialogue.getSettings().copy();

        if (node != null) {
            effective.applyOverrides(
                    node.getSettingsOverrides()
            );
        }

        return effective;
    }

    public Settings getEffectiveSettings(
            String dialogueId,
            String nodeId
    ) {
        Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null) {
            return new Settings();
        }

        return getEffectiveSettings(
                dialogue,
                dialogue.getNode(nodeId)
        );
    }

    /*
     * ============================================================
     * PLAYER PROGRESS
     * ============================================================
     */

    public boolean isDialogueCompleted(
            UUID playerId,
            String dialogueId
    ) {
        return readProgress(
                "SELECT completed FROM player_progress "
                        + "WHERE player_uuid = ? AND dialogue_id = ?",
                playerId,
                dialogueId
        );
    }

    public boolean isBranchCompleted(
            UUID playerId,
            String dialogueId,
            String branchId
    ) {
        if (database == null || playerId == null) {
            return false;
        }

        try (PreparedStatement statement =
                     database.prepareStatement(
                             "SELECT completed FROM branch_progress "
                                     + "WHERE player_uuid = ? "
                                     + "AND dialogue_id = ? "
                                     + "AND branch_id = ?"
                     )) {

            statement.setString(1, playerId.toString());
            statement.setString(2, normalizeId(dialogueId));
            statement.setString(3, normalizeId(branchId));

            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && result.getInt("completed") == 1;
            }

        } catch (SQLException exception) {
            logDatabaseError(exception);
            return false;
        }
    }

    public boolean wasAnswerSelected(
            UUID playerId,
            String dialogueId,
            String branchId,
            String answerId
    ) {
        if (database == null || playerId == null) {
            return false;
        }

        try (PreparedStatement statement =
                     database.prepareStatement(
                             "SELECT selected FROM answer_progress "
                                     + "WHERE player_uuid = ? "
                                     + "AND dialogue_id = ? "
                                     + "AND branch_id = ? "
                                     + "AND answer_id = ?"
                     )) {

            statement.setString(1, playerId.toString());
            statement.setString(2, normalizeId(dialogueId));
            statement.setString(3, normalizeId(branchId));
            statement.setString(4, normalizeId(answerId));

            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && result.getInt("selected") == 1;
            }
        } catch (SQLException exception) {
            logDatabaseError(exception);
            return false;
        }
    }

    public void markDialogueCompleted(
            UUID playerId,
            String dialogueId
    ) {
        if (database == null || playerId == null) {
            return;
        }

        executeProgressUpdate(
                "INSERT INTO player_progress "
                        + "(player_uuid, dialogue_id, completed, completed_at) "
                        + "VALUES (?, ?, 1, ?) "
                        + "ON CONFLICT(player_uuid, dialogue_id) "
                        + "DO UPDATE SET completed = 1, "
                        + "completed_at = excluded.completed_at",
                playerId.toString(),
                normalizeId(dialogueId),
                System.currentTimeMillis()
        );
    }

    public void markBranchCompleted(
            UUID playerId,
            String dialogueId,
            String branchId
    ) {
        if (database == null || playerId == null) {
            return;
        }

        executeProgressUpdate(
                "INSERT INTO branch_progress "
                        + "(player_uuid, dialogue_id, branch_id, completed, completed_at) "
                        + "VALUES (?, ?, ?, 1, ?) "
                        + "ON CONFLICT(player_uuid, dialogue_id, branch_id) "
                        + "DO UPDATE SET completed = 1, "
                        + "completed_at = excluded.completed_at",
                playerId.toString(),
                normalizeId(dialogueId),
                normalizeId(branchId),
                System.currentTimeMillis()
        );
    }

    public void markAnswerSelected(
            UUID playerId,
            String dialogueId,
            String branchId,
            String answerId
    ) {
        if (database == null || playerId == null) {
            return;
        }

        executeProgressUpdate(
                "INSERT INTO answer_progress "
                        + "(player_uuid, dialogue_id, branch_id, answer_id, selected, selected_at) "
                        + "VALUES (?, ?, ?, ?, 1, ?) "
                        + "ON CONFLICT(player_uuid, dialogue_id, branch_id, answer_id) "
                        + "DO UPDATE SET selected = 1, "
                        + "selected_at = excluded.selected_at",
                playerId.toString(),
                normalizeId(dialogueId),
                normalizeId(branchId),
                normalizeId(answerId),
                System.currentTimeMillis()
        );
    }

    private boolean readProgress(
            String sql,
            UUID playerId,
            String dialogueId
    ) {
        if (database == null || playerId == null) {
            return false;
        }

        try (PreparedStatement statement =
                     database.prepareStatement(sql)) {

            statement.setString(1, playerId.toString());
            statement.setString(2, normalizeId(dialogueId));

            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && result.getInt(1) == 1;
            }

        } catch (SQLException exception) {
            logDatabaseError(exception);
            return false;
        }
    }

    private void executeProgressUpdate(
            String sql,
            Object... values
    ) {
        try (PreparedStatement statement =
                     database.prepareStatement(sql)) {

            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }

            statement.executeUpdate();

        } catch (SQLException exception) {
            logDatabaseError(exception);
        }
    }

    private void logDatabaseError(SQLException exception) {
        plugin.getLogger().log(
                Level.SEVERE,
                "SQLite operation failed.",
                exception
        );
    }
    /*
     * ============================================================
     * SERIALIZATION
     * ============================================================
     */

    private String serializeDialogue(Dialogue dialogue) {
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set("id", dialogue.getId());
        yaml.set("name", dialogue.getName());
        yaml.set("root-node", dialogue.getRootNode());

        saveSettings(yaml, "settings", dialogue.getSettings());

        for (Node node : dialogue.getNodes().values()) {
            String path = "nodes." + node.getId();

            yaml.set(path + ".name", node.getName());
            yaml.set(path + ".next-node", node.getNextNode());

            saveSettings(
                    yaml,
                    path + ".settings",
                    node.getSettingsOverrides()
            );

            for (Answer answer : node.getAnswers()) {
                String answerPath = path
                        + ".answers."
                        + answer.getId();

                yaml.set(answerPath + ".text", answer.getText());
                yaml.set(
                        answerPath + ".target-node",
                        answer.getTargetNode()
                );

                yaml.set(
                        answerPath + ".enabled",
                        answer.isEnabled()
                );

                yaml.set(
                        answerPath + ".sound",
                        answer.getSound()
                );

                yaml.set(
                        answerPath + ".commands",
                        answer.getCommands()
                );

                yaml.set(
                        answerPath + ".conditions",
                        serializeConditions(answer.getConditions())
                );

                yaml.set(
                        answerPath + ".rewards",
                        serializeRewards(answer.getRewards())
                );
            }
        }

        return yaml.saveToString();
    }

    private Dialogue deserializeDialogue(
            String id,
            String name,
            String serialized
    ) {
        if (serialized == null || serialized.isBlank()) {
            return null;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setDefaults(null);

        try {
            yaml.loadFromString(serialized);
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Invalid dialogue data: " + id,
                    exception
            );

            return null;
        }

        Dialogue dialogue = new Dialogue(id, name);

        dialogue.setRootNode(
                yaml.getString("root-node", "start")
        );

        loadSettings(
                yaml,
                "settings",
                dialogue.getSettings()
        );

        ConfigurationSection nodesSection =
                yaml.getConfigurationSection("nodes");

        if (nodesSection != null) {
            for (String nodeId : nodesSection.getKeys(false)) {
                String path = "nodes." + nodeId;

                Node node = new Node(
                        nodeId,
                        yaml.getString(
                                path + ".name",
                                nodeId
                        )
                );

                node.setNextNode(
                        yaml.getString(path + ".next-node")
                );

                loadSettings(
                        yaml,
                        path + ".settings",
                        node.getSettingsOverrides()
                );

                ConfigurationSection answersSection =
                        yaml.getConfigurationSection(
                                path + ".answers"
                        );

                if (answersSection != null) {
                    for (String answerId :
                            answersSection.getKeys(false)) {

                        String answerPath =
                                path + ".answers." + answerId;

                        Answer answer = new Answer(
                                answerId,
                                yaml.getString(
                                        answerPath + ".text",
                                        answerId
                                )
                        );

                        answer.setTargetNode(
                                yaml.getString(
                                        answerPath + ".target-node"
                                )
                        );

                        answer.setEnabled(
                                yaml.getBoolean(
                                        answerPath + ".enabled",
                                        true
                                )
                        );

                        answer.setSound(
                                yaml.getString(
                                        answerPath + ".sound"
                                )
                        );

                        answer.setCommands(
                                yaml.getStringList(
                                        answerPath + ".commands"
                                )
                        );

                        answer.setConditions(
                                deserializeConditions(
                                        yaml.getMapList(
                                                answerPath + ".conditions"
                                        )
                                )
                        );

                        answer.setRewards(
                                deserializeRewards(
                                        yaml.getMapList(
                                                answerPath + ".rewards"
                                        )
                                )
                        );

                        node.addAnswer(answer);
                    }
                }

                dialogue.addNode(node);
            }
        }

        return dialogue;
    }

    private void saveSettings(
            YamlConfiguration yaml,
            String path,
            Settings settings
    ) {
        yaml.set(path, settings.serialize());
    }

    private void loadSettings(
            YamlConfiguration yaml,
            String path,
            Settings settings
    ) {
        ConfigurationSection section =
                yaml.getConfigurationSection(path);

        if (section == null) {
            return;
        }

        settings.deserialize(section.getValues(false));
    }

    /*
     * ============================================================
     * CONDITIONS
     * ============================================================
     */

    private List<Map<String, Object>> serializeConditions(
            ConditionGroup group
    ) {
        List<Map<String, Object>> result =
                new ArrayList<>();

        if (group == null) {
            return result;
        }

        for (Condition condition : group.getConditions()) {
            Map<String, Object> entry =
                    new LinkedHashMap<>();

            entry.put("type", condition.getType());
            entry.put("value", condition.getValue());
            entry.put("operator", condition.getOperator());

            result.add(entry);
        }

        return result;
    }


    private ConditionGroup deserializeConditions(
            List<Map<?, ?>> values
    ) {
        ConditionGroup group = new ConditionGroup();

        if (values == null) {
            return group;
        }

        for (Map<?, ?> value : values) {
            if (value == null) {
                continue;
            }

            String type = String.valueOf(
                    value.containsKey("type") ? value.get("type") : "permission"
            );

            String expected = String.valueOf(
                    value.containsKey("value") ? value.get("value") : ""
            );

            String operator = String.valueOf(
                    value.containsKey("operator") ? value.get("operator") : "equals"
            );

            group.add(new Condition(
                    type,
                    expected,
                    operator
            ));
        }

        return group;
    }

    /*
     * ============================================================
     * REWARDS
     * ============================================================
     */

    private List<Map<String, Object>> serializeRewards(
            List<Reward> rewards
    ) {
        List<Map<String, Object>> result =
                new ArrayList<>();

        if (rewards == null) {
            return result;
        }

        for (Reward reward : rewards) {
            Map<String, Object> entry =
                    new LinkedHashMap<>();

            entry.put("type", reward.getType());
            entry.put("value", reward.getValue());
            entry.put("amount", reward.getAmount());

            result.add(entry);
        }

        return result;
    }
    private List<Reward> deserializeRewards(
            List<Map<?, ?>> values
    ) {
        List<Reward> rewards = new ArrayList<>();

        if (values == null) {
            return rewards;
        }

        for (Map<?, ?> value : values) {
            if (value == null) {
                continue;
            }

            String type = String.valueOf(
                    value.containsKey("type") ? value.get("type") : "command"
            );

            String rewardValue = String.valueOf(
                    value.containsKey("value") ? value.get("value") : ""
            );

            int amount = 1;

            Object amountValue = value.get("amount");

            if (amountValue instanceof Number number) {
                amount = Math.max(1, number.intValue());
            }

            rewards.add(new Reward(
                    type,
                    rewardValue,
                    amount
            ));
        }

        return rewards;
    }


    /*
     * ============================================================
     * EXAMPLE DIALOGUE
     * ============================================================
     */

    public boolean writeExample() {
        if (hasDialogue("example")) {
            return true;
        }

        Dialogue dialogue = new Dialogue(
                "example",
                "Приклад діалогу"
        );

        dialogue.setRootNode("start");

        Node start = new Node(
                "start",
                "Початок"
        );

        start.addAnswer(new Answer(
                "continue",
                "Продовжити"
        ));

        dialogue.addNode(start);

        return saveDialogue(dialogue);
    }

    /*
     * ============================================================
     * IDENTIFIERS
     * ============================================================
     */

    private boolean isValidId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        return value.matches("[a-zA-Z0-9_-]{1,64}");
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }

        return value.trim().toLowerCase(Locale.ROOT);
    }

    /*
     * ============================================================
     * DIALOGUE MODEL
     * ============================================================
     */

    public static final class Dialogue {

        private final String id;
        private String name;

        private String rootNode = "start";

        private final Settings settings =
                new Settings();

        private final Map<String, Node> nodes =
                new LinkedHashMap<>();

        public Dialogue(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRootNode() {
            return rootNode;
        }

        public void setRootNode(String rootNode) {
            this.rootNode = rootNode;
        }

        public Settings getSettings() {
            return settings;
        }

        public Map<String, Node> getNodes() {
            return nodes;
        }

        public Node getNode(String id) {
            if (id == null) {
                return null;
            }

            return nodes.get(
                    id.toLowerCase(Locale.ROOT)
            );
        }

        public void addNode(Node node) {
            if (node != null) {
                nodes.put(node.getId(), node);
            }
        }

        public Dialogue copy(
                String newId,
                String newName
        ) {
            Dialogue copy = new Dialogue(
                    newId,
                    newName
            );

            copy.rootNode = rootNode;
            copy.settings.deserialize(
                    settings.serialize()
            );

            for (Node node : nodes.values()) {
                copy.addNode(node.copy());
            }

            return copy;
        }
    }

    /*
     * ============================================================
     * NODE MODEL
     * ============================================================
     */

    public static final class Node {

        private final String id;
        private String name;

        private String nextNode;

        private final Settings settingsOverrides =
                new Settings();

        private final List<Answer> answers =
                new ArrayList<>();

        public Node(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNextNode() {
            return nextNode;
        }

        public void setNextNode(String nextNode) {
            this.nextNode = nextNode;
        }

        public Settings getSettingsOverrides() {
            return settingsOverrides;
        }

        public List<Answer> getAnswers() {
            return Collections.unmodifiableList(answers);
        }

        public void addAnswer(Answer answer) {
            if (answer != null) {
                answers.add(answer);
            }
        }

        public Node copy() {
            Node copy = new Node(id, name);

            copy.nextNode = nextNode;

            copy.settingsOverrides.deserialize(
                    settingsOverrides.serialize()
            );

            for (Answer answer : answers) {
                copy.addAnswer(answer.copy());
            }

            return copy;
        }
    }

    /*
     * ============================================================
     * ANSWER MODEL
     * ============================================================
     */

    public static final class Answer {

        private final String id;
        private String text;

        private String targetNode;
        private boolean enabled = true;

        private String sound;

        private final List<String> commands =
                new ArrayList<>();

        private ConditionGroup conditions =
                new ConditionGroup();

        private final List<Reward> rewards =
                new ArrayList<>();

        public Answer(String id, String text) {
            this.id = id;
            this.text = text;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getTargetNode() {
            return targetNode;
        }

        public void setTargetNode(String targetNode) {
            this.targetNode = targetNode;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSound() {
            return sound;
        }

        public void setSound(String sound) {
            this.sound = sound;
        }

        public List<String> getCommands() {
            return Collections.unmodifiableList(commands);
        }

        public void setCommands(List<String> values) {
            commands.clear();

            if (values != null) {
                commands.addAll(values);
            }
        }

        public ConditionGroup getConditions() {
            return conditions;
        }

        public void setConditions(ConditionGroup conditions) {
            this.conditions = conditions == null
                    ? new ConditionGroup()
                    : conditions;
        }

        public List<Reward> getRewards() {
            return Collections.unmodifiableList(rewards);
        }

        public void setRewards(List<Reward> values) {
            rewards.clear();

            if (values != null) {
                rewards.addAll(values);
            }
        }

        public Answer copy() {
            Answer copy = new Answer(id, text);

            copy.targetNode = targetNode;
            copy.enabled = enabled;
            copy.sound = sound;

            copy.setCommands(commands);
            copy.setConditions(conditions.copy());
            copy.setRewards(rewards);

            return copy;
        }
    }
    /*
     * ============================================================
     * SETTINGS MODEL
     * ============================================================
     */

    public static final class Settings {

        private final Map<String, Object> values =
                new LinkedHashMap<>();

        private Location cameraLocation;

        public Settings() {
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

        public Object get(String key) {
            return values.get(key);
        }

        public Object get(String key, Object fallback) {
            return values.getOrDefault(key, fallback);
        }

        public void set(String key, Object value) {
            if (key == null || key.isBlank()) {
                return;
            }

            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        public boolean contains(String key) {
            return values.containsKey(key);
        }

        public boolean getBoolean(
                String key,
                boolean fallback
        ) {
            Object value = values.get(key);

            if (value instanceof Boolean result) {
                return result;
            }

            if (value instanceof String string) {
                return Boolean.parseBoolean(string);
            }

            return fallback;
        }

        public double getDouble(
                String key,
                double fallback
        ) {
            Object value = values.get(key);

            if (value instanceof Number number) {
                return number.doubleValue();
            }

            if (value instanceof String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }

            return fallback;
        }

        public int getInt(
                String key,
                int fallback
        ) {
            Object value = values.get(key);

            if (value instanceof Number number) {
                return number.intValue();
            }

            if (value instanceof String string) {
                try {
                    return Integer.parseInt(string);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }

            return fallback;
        }

        public String getString(
                String key,
                String fallback
        ) {
            Object value = values.get(key);

            return value == null
                    ? fallback
                    : String.valueOf(value);
        }

        public void applyOverrides(Settings overrides) {
            if (overrides == null) {
                return;
            }

            values.putAll(overrides.values);

            if (overrides.cameraLocation != null) {
                cameraLocation =
                        overrides.cameraLocation.clone();
            }
        }

        public Map<String, Object> serialize() {
            Map<String, Object> result =
                    new LinkedHashMap<>(values);

            if (cameraLocation != null
                    && cameraLocation.getWorld() != null) {

                Map<String, Object> location =
                        new LinkedHashMap<>();

                location.put(
                        "world",
                        cameraLocation.getWorld().getName()
                );

                location.put("x", cameraLocation.getX());
                location.put("y", cameraLocation.getY());
                location.put("z", cameraLocation.getZ());
                location.put("yaw", cameraLocation.getYaw());
                location.put("pitch", cameraLocation.getPitch());

                result.put("camera-location", location);
            }

            return result;
        }

        public void deserialize(Map<?, ?> data) {
            values.clear();
            cameraLocation = null;

            if (data == null) {
                return;
            }

            for (Map.Entry<?, ?> entry : data.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }

                String key = String.valueOf(entry.getKey());

                if (key.equals("camera-location")) {
                    loadCameraLocation(entry.getValue());
                    continue;
                }

                values.put(key, entry.getValue());
            }
        }

        private void loadCameraLocation(Object value) {
            if (!(value instanceof Map<?, ?> locationData)) {
                return;
            }

            Object worldValue = locationData.get("world");

            if (worldValue == null) {
                return;
            }

            org.bukkit.World world = Bukkit.getWorld(
                    String.valueOf(worldValue)
            );

            if (world == null) {
                return;
            }

            double x = readDouble(locationData.get("x"));
            double y = readDouble(locationData.get("y"));
            double z = readDouble(locationData.get("z"));

            float yaw = (float) readDouble(
                    locationData.get("yaw")
            );

            float pitch = (float) readDouble(
                    locationData.get("pitch")
            );

            cameraLocation = new Location(
                    world,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
            );
        }

        private double readDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }

            if (value instanceof String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }

            return 0.0;
        }

        public Settings copy() {
            Settings copy = new Settings();

            copy.deserialize(serialize());

            return copy;
        }
    }

    /*
     * ============================================================
     * CONDITION GROUP
     * ============================================================
     */

    public static final class ConditionGroup {

        private final List<Condition> conditions =
                new ArrayList<>();

        private String logic = "AND";

        public List<Condition> getConditions() {
            return Collections.unmodifiableList(
                    conditions
            );
        }

        public void add(Condition condition) {
            if (condition != null) {
                conditions.add(condition);
            }
        }

        public void remove(Condition condition) {
            conditions.remove(condition);
        }

        public void clear() {
            conditions.clear();
        }

        public String getLogic() {
            return logic;
        }

        public void setLogic(String logic) {
            if (logic == null) {
                this.logic = "AND";
                return;
            }

            String normalized =
                    logic.trim().toUpperCase(Locale.ROOT);

            this.logic = normalized.equals("OR")
                    ? "OR"
                    : "AND";
        }

        public boolean isEmpty() {
            return conditions.isEmpty();
        }

        public ConditionGroup copy() {
            ConditionGroup copy = new ConditionGroup();

            copy.logic = logic;

            for (Condition condition : conditions) {
                copy.add(condition.copy());
            }

            return copy;
        }
    }

    /*
     * ============================================================
     * CONDITION MODEL
     * ============================================================
     */

    public static final class Condition {

        private String type;
        private String value;
        private String operator;

        public Condition(
                String type,
                String value,
                String operator
        ) {
            this.type = type == null
                    ? "permission"
                    : type;

            this.value = value == null
                    ? ""
                    : value;

            this.operator = operator == null
                    ? "equals"
                    : operator;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Condition copy() {
            return new Condition(
                    type,
                    value,
                    operator
            );
        }
    }

    /*
     * ============================================================
     * REWARD MODEL
     * ============================================================
     */

    public static final class Reward {

        private String type;
        private String value;
        private int amount;

        public Reward(
                String type,
                String value,
                int amount
        ) {
            this.type = type == null
                    ? "command"
                    : type;

            this.value = value == null
                    ? ""
                    : value;

            this.amount = Math.max(1, amount);
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = Math.max(1, amount);
        }

        public Reward copy() {
            return new Reward(
                    type,
                    value,
                    amount
            );
        }
    }

}
