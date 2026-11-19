package fritty.dialogue;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persistent dialogue definitions and player progress storage.
 * Dialogue definitions are stored as YAML documents inside SQLite.
 */
public final class DialogueCore {

    private static final int DATABASE_SCHEMA_VERSION = 1;
    private static final String DATABASE_FILE = "frittydialogue.db";

    private final FrittyDialogue plugin;
    private final Map<String, Dialogue> dialogues = new LinkedHashMap<>();
    private Connection connection;
    private boolean initialized;
    private boolean shuttingDown;

    public DialogueCore(FrittyDialogue plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized boolean initialize() {
        if (initialized) {
            return true;
        }

        shuttingDown = false;

        try {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("Could not create plugin data folder.");
            }

            Class.forName("fritty.dialogue.libs.sqlite.JDBC");
            File database = new File(folder, DATABASE_FILE);
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
            }

            createDatabaseTables();
            loadAllDialogues();
            initialized = true;
            plugin.getLogger().info("DialogueCore loaded " + dialogues.size() + " dialogue(s).");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "DialogueCore initialization failed.", exception);
            closeDatabase();
            return false;
        }
    }

    private void createDatabaseTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_info (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS dialogues (" +
                    "id TEXT PRIMARY KEY COLLATE NOCASE, name TEXT NOT NULL, data TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_progress (" +
                    "player_uuid TEXT NOT NULL, dialogue_id TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0, " +
                    "completed_at INTEGER, current_stage TEXT, PRIMARY KEY(player_uuid, dialogue_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS branch_progress (" +
                    "player_uuid TEXT NOT NULL, dialogue_id TEXT NOT NULL, branch_id TEXT NOT NULL, " +
                    "completed INTEGER NOT NULL DEFAULT 0, completed_at INTEGER, " +
                    "PRIMARY KEY(player_uuid, dialogue_id, branch_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS answer_progress (" +
                    "player_uuid TEXT NOT NULL, dialogue_id TEXT NOT NULL, branch_id TEXT NOT NULL, " +
                    "answer_id TEXT NOT NULL, selected INTEGER NOT NULL DEFAULT 0, selected_at INTEGER, " +
                    "PRIMARY KEY(player_uuid, dialogue_id, branch_id, answer_id))");
        }

        ensureColumn("player_progress", "current_stage", "TEXT");

        try (PreparedStatement query = connection.prepareStatement("SELECT version FROM schema_info WHERE id = 1");
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO schema_info(id, version) VALUES(1, ?)")) {
                    insert.setInt(1, DATABASE_SCHEMA_VERSION);
                    insert.executeUpdate();
                }
            } else if (result.getInt("version") > DATABASE_SCHEMA_VERSION) {
                throw new SQLException("Database schema is newer than this plugin build.");
            }
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private void loadAllDialogues() throws SQLException {
        Map<String, Dialogue> loaded = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, data FROM dialogues ORDER BY id COLLATE NOCASE");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String id = result.getString("id");
                String name = result.getString("name");
                String data = result.getString("data");
                try {
                    Dialogue dialogue = deserializeDialogue(id, name, data);
                    loaded.put(dialogue.getId(), dialogue);
                } catch (Exception exception) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not load dialogue '" + id + "'. Its database row was left untouched.", exception);
                }
            }
        }
        dialogues.clear();
        dialogues.putAll(loaded);
    }

    public synchronized void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        if (initialized) {
            saveAllDialogues();
        }
        closeDatabase();
        initialized = false;
    }

    private void closeDatabase() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not close dialogue database.", exception);
            }
            connection = null;
        }
    }

    public synchronized int getDialogueCount() {
        return dialogues.size();
    }

    public synchronized List<String> getDialogueIds() {
        return new ArrayList<>(dialogues.keySet());
    }

    public synchronized Collection<Dialogue> getDialogues() {
        return Collections.unmodifiableCollection(new ArrayList<>(dialogues.values()));
    }

    public synchronized Dialogue getDialogue(String id) {
        return id == null ? null : dialogues.get(normalizeLookupId(id));
    }

    public synchronized boolean hasDialogue(String id) {
        return getDialogue(id) != null;
    }

    public synchronized Dialogue createDialogue(String name) {
        if (!isValidId(name) || hasDialogue(name)) {
            return null;
        }
        String id = normalizeId(name);
        Dialogue dialogue = new Dialogue(id, id);
        dialogue.setRootNode("start");
        dialogue.addNode(new Node("start", "Новий діалог"));
        dialogues.put(id, dialogue);
        if (!saveDialogue(id)) {
            dialogues.remove(id);
            return null;
        }
        return dialogue;
    }

    public synchronized boolean createDialogue(String name, String copyFrom) {
        if (!isValidId(name) || hasDialogue(name)) {
            return false;
        }
        Dialogue created;
        if (copyFrom == null || copyFrom.isBlank()) {
            created = createDialogue(name);
            return created != null;
        }
        Dialogue source = getDialogue(copyFrom);
        if (source == null) {
            return false;
        }
        String id = normalizeId(name);
        created = source.copy(id, id);
        dialogues.put(id, created);
        if (!saveDialogue(id)) {
            dialogues.remove(id);
            return false;
        }
        return true;
    }

    public synchronized boolean deleteDialogue(String id) {
        Dialogue removed = getDialogue(id);
        if (removed == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM dialogues WHERE id = ?")) {
            statement.setString(1, removed.getId());
            statement.executeUpdate();
            deleteProgressForDialogue(removed.getId());
            dialogues.remove(removed.getId());
            return true;
        } catch (SQLException exception) {
            logDatabaseError("Could not delete dialogue " + id, exception);
            return false;
        }
    }

    public synchronized boolean renameDialogue(String oldId, String newId) {
        Dialogue dialogue = getDialogue(oldId);
        if (dialogue == null || !isValidId(newId)) {
            return false;
        }
        String normalized = normalizeId(newId);
        if (!oldId.equalsIgnoreCase(normalized) && hasDialogue(normalized)) {
            return false;
        }
        String previousId = dialogue.getId();
        Dialogue renamed = dialogue.copy(normalized, normalized);
        dialogues.remove(previousId);
        dialogues.put(normalized, renamed);
        if (!saveDialogue(normalized)) {
            dialogues.remove(normalized);
            dialogues.put(previousId, dialogue);
            return false;
        }
        if (!previousId.equals(normalized)) {
            if (previousId.equalsIgnoreCase(normalized)) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE dialogues SET id = ? WHERE id = ?")) {
                    update.setString(1, normalized);
                    update.setString(2, previousId);
                    update.executeUpdate();
                } catch (SQLException exception) {
                    logDatabaseError("Could not update dialogue id casing", exception);
                }
            } else {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM dialogues WHERE id = ?")) {
                    delete.setString(1, previousId);
                    delete.executeUpdate();
                } catch (SQLException exception) {
                    logDatabaseError("Could not remove old dialogue id after rename", exception);
                }
            }
            renameProgressDialogueId(previousId, normalized);
            updateDialogueConditionReferences(previousId, normalized);
        }
        return true;
    }

    private void renameProgressDialogueId(String oldId, String newId) {
        String[] tables = {"player_progress", "branch_progress", "answer_progress"};
        try {
            connection.setAutoCommit(false);
            for (String table : tables) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + table + " SET dialogue_id = ? WHERE dialogue_id = ?")) {
                    statement.setString(1, newId);
                    statement.setString(2, oldId);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            logDatabaseError("Could not rename dialogue progress records", exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public synchronized boolean reloadDialogue(String id) {
        if (id == null || connection == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, data FROM dialogues WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    dialogues.remove(normalizeLookupId(id));
                    return false;
                }
                Dialogue dialogue = deserializeDialogue(result.getString("id"),
                        result.getString("name"), result.getString("data"));
                dialogues.put(dialogue.getId(), dialogue);
                return true;
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not reload dialogue '" + id + "'.", exception);
            return false;
        }
    }

    public synchronized boolean reloadAll() {
        if (connection == null) {
            return false;
        }
        try {
            loadAllDialogues();
            return true;
        } catch (SQLException exception) {
            logDatabaseError("Could not reload dialogues", exception);
            return false;
        }
    }

    public synchronized boolean saveDialogue(String id) {
        Dialogue dialogue = getDialogue(id);
        return dialogue != null && saveDialogue(dialogue);
    }

    public synchronized boolean saveDialogue(Dialogue dialogue) {
        if (dialogue == null || connection == null || shuttingDown && !initialized) {
            return false;
        }
        String data;
        try {
            data = serializeDialogue(dialogue);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not serialize dialogue '" + dialogue.getId() + "'.", exception);
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dialogues(id, name, data) VALUES(?, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET name = excluded.name, data = excluded.data")) {
            statement.setString(1, dialogue.getId());
            statement.setString(2, dialogue.getName());
            statement.setString(3, data);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            logDatabaseError("Could not save dialogue '" + dialogue.getId() + "'", exception);
            return false;
        }
    }

    public synchronized boolean saveAllDialogues() {
        boolean success = true;
        for (Dialogue dialogue : new ArrayList<>(dialogues.values())) {
            success &= saveDialogue(dialogue);
        }
        return success;
    }

    public synchronized boolean setCameraLocation(String dialogueId, Location feetLocation) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue == null || feetLocation == null || feetLocation.getWorld() == null) {
            return false;
        }
        dialogue.getSettings().setCameraLocation(feetLocation);
        return saveDialogue(dialogue);
    }

    public synchronized Node getRootNode(String dialogueId) {
        Dialogue dialogue = getDialogue(dialogueId);
        return dialogue == null ? null : dialogue.getNode(dialogue.getRootNode());
    }

    public synchronized Node getNode(String dialogueId, String nodeId) {
        Dialogue dialogue = getDialogue(dialogueId);
        return dialogue == null ? null : dialogue.getNode(nodeId);
    }

    public synchronized boolean setRootNode(String dialogueId, String nodeId) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue == null || dialogue.getNode(nodeId) == null) {
            return false;
        }
        dialogue.setRootNode(nodeId);
        return saveDialogue(dialogue);
    }

    public synchronized boolean createNode(String dialogueId, String nodeId, String displayName) {
        if (!createBranch(dialogueId, nodeId)) {
            return false;
        }
        Node node = getNode(dialogueId, nodeId);
        if (node != null && displayName != null && !displayName.isBlank()) {
            node.setName(displayName);
            return saveDialogue(dialogueId);
        }
        return node != null;
    }

    public synchronized boolean createBranch(String dialogueId, String branchId) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue == null || !isValidId(branchId) || dialogue.getNode(branchId) != null) {
            return false;
        }
        dialogue.addNode(new Node(normalizeId(branchId), normalizeId(branchId)));
        return saveDialogue(dialogue);
    }

    public synchronized boolean deleteNode(String dialogueId, String nodeId) {
        return deleteBranch(dialogueId, nodeId);
    }

    public synchronized boolean deleteBranch(String dialogueId, String branchId) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue == null || branchId == null || branchId.equalsIgnoreCase(dialogue.getRootNode())) {
            return false;
        }
        Node removed = dialogue.removeNode(branchId);
        if (removed == null) {
            return false;
        }

        Map<Node, String> previousNextNodes = new LinkedHashMap<>();
        Map<Answer, String> previousAnswerTargets = new LinkedHashMap<>();
        for (Node node : dialogue.getNodes()) {
            if (branchId.equals(node.getNextNode())) {
                previousNextNodes.put(node, branchId);
                node.setNextNode(null);
            }
            for (Answer answer : node.getAnswers()) {
                if (branchId.equals(answer.getTargetNode())) {
                    previousAnswerTargets.put(answer, branchId);
                    answer.setTargetNode(null);
                }
            }
        }

        if (!saveDialogue(dialogue)) {
            dialogue.addNode(removed);
            previousNextNodes.forEach(Node::setNextNode);
            previousAnswerTargets.forEach(Answer::setTargetNode);
            return false;
        }

        deleteProgressForBranch(dialogueId, branchId);
        return true;
    }

    public synchronized boolean renameBranch(String dialogueId, String oldId, String newId) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue == null || oldId == null || !isValidId(newId)) {
            return false;
        }
        Node oldNode = dialogue.getNode(oldId);
        String normalized = normalizeId(newId);
        if (oldNode == null || dialogue.getNode(normalized) != null) {
            return false;
        }
        String previousRoot = dialogue.getRootNode();
        Node replacement = oldNode.copyWithId(normalized);
        dialogue.removeNode(oldId);
        dialogue.addNode(replacement);
        if (oldId.equalsIgnoreCase(previousRoot)) {
            dialogue.setRootNode(normalized);
        }
        for (Node node : dialogue.getNodes()) {
            if (oldId.equals(node.getNextNode())) {
                node.setNextNode(normalized);
            }
            for (Answer answer : node.getAnswers()) {
                if (oldId.equals(answer.getTargetNode())) {
                    answer.setTargetNode(normalized);
                }
            }
        }
        if (!saveDialogue(dialogue)) {
            dialogue.removeNode(normalized);
            dialogue.addNode(oldNode);
            dialogue.setRootNode(previousRoot);
            for (Node node : dialogue.getNodes()) {
                if (normalized.equals(node.getNextNode())) {
                    node.setNextNode(oldId);
                }
                for (Answer answer : node.getAnswers()) {
                    if (normalized.equals(answer.getTargetNode())) {
                        answer.setTargetNode(oldId);
                    }
                }
            }
            return false;
        }
        renameBranchProgress(dialogueId, oldId, normalized);
        updateBranchConditionReferences(dialogueId, oldId, normalized);
        return true;
    }

    private void updateDialogueConditionReferences(String oldDialogueId, String newDialogueId) {
        for (Dialogue candidate : new ArrayList<>(dialogues.values())) {
            boolean changed = false;
            for (Node node : candidate.getNodes()) {
                changed |= updateDialogueConditionGroup(
                        node.getConditionGroup(), oldDialogueId, newDialogueId);
                for (Answer answer : node.getAnswers()) {
                    changed |= updateDialogueConditionGroup(
                            answer.getConditions(), oldDialogueId, newDialogueId);
                }
            }
            if (changed && !saveDialogue(candidate)) {
                plugin.getLogger().warning("Could not update dialogue condition references in '"
                        + candidate.getId() + "'.");
            }
        }
    }

    private boolean updateDialogueConditionGroup(ConditionGroup group,
                                                 String oldDialogueId,
                                                 String newDialogueId) {
        boolean changed = false;
        for (Condition condition : group.getConditions()) {
            String value = condition.getValue();
            if (condition.getType() == ConditionType.DIALOGUE_COMPLETED
                    && value.equalsIgnoreCase(oldDialogueId)) {
                condition.setValue(newDialogueId);
                changed = true;
            } else if ((condition.getType() == ConditionType.COMPLETED_BRANCH
                    || condition.getType() == ConditionType.BRANCH_COMPLETED)
                    && value.startsWith(oldDialogueId + ":")) {
                condition.setValue(newDialogueId + value.substring(oldDialogueId.length()));
                changed = true;
            }
        }
        return changed;
    }

    private void updateBranchConditionReferences(String dialogueId,
                                                 String oldBranchId,
                                                 String newBranchId) {
        for (Dialogue candidate : new ArrayList<>(dialogues.values())) {
            boolean changed = false;
            for (Node node : candidate.getNodes()) {
                changed |= updateBranchConditionGroup(
                        node.getConditionGroup(), candidate.getId(), dialogueId,
                        oldBranchId, newBranchId);
                for (Answer answer : node.getAnswers()) {
                    changed |= updateBranchConditionGroup(
                            answer.getConditions(), candidate.getId(), dialogueId,
                            oldBranchId, newBranchId);
                }
            }
            if (changed && !saveDialogue(candidate)) {
                plugin.getLogger().warning("Could not update branch condition references in '"
                        + candidate.getId() + "'.");
            }
        }
    }

    private boolean updateBranchConditionGroup(ConditionGroup group,
                                               String candidateDialogueId,
                                               String targetDialogueId,
                                               String oldBranchId,
                                               String newBranchId) {
        boolean changed = false;
        for (Condition condition : group.getConditions()) {
            String value = condition.getValue();
            ConditionType type = condition.getType();
            if (type == ConditionType.COMPLETED_BRANCH || type == ConditionType.BRANCH_COMPLETED) {
                if (candidateDialogueId.equalsIgnoreCase(targetDialogueId)
                        && value.equals(oldBranchId)) {
                    condition.setValue(newBranchId);
                    changed = true;
                } else if (value.equals(targetDialogueId + ":" + oldBranchId)) {
                    condition.setValue(targetDialogueId + ":" + newBranchId);
                    changed = true;
                }
            } else if (type == ConditionType.ANSWER_SELECTED
                    && candidateDialogueId.equalsIgnoreCase(targetDialogueId)
                    && value.startsWith(oldBranchId + ":")) {
                condition.setValue(newBranchId + value.substring(oldBranchId.length()));
                changed = true;
            }
        }
        return changed;
    }

    private void renameBranchProgress(String dialogueId, String oldId, String newId) {
        if (connection == null) return;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE branch_progress SET branch_id = ? WHERE dialogue_id = ? AND branch_id = ?")) {
                statement.setString(1, newId);
                statement.setString(2, dialogueId);
                statement.setString(3, oldId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE answer_progress SET branch_id = ? WHERE dialogue_id = ? AND branch_id = ?")) {
                statement.setString(1, newId);
                statement.setString(2, dialogueId);
                statement.setString(3, oldId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE player_progress SET current_stage = ? WHERE dialogue_id = ? AND current_stage = ?")) {
                statement.setString(1, newId);
                statement.setString(2, dialogueId);
                statement.setString(3, oldId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            logDatabaseError("Could not update progress after branch rename", exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    public synchronized boolean setBranchText(String dialogueId, String nodeId, String text) {
        Node node = getNode(dialogueId, nodeId);
        if (node == null) return false;
        node.setName(text == null ? "" : text);
        return saveDialogue(dialogueId);
    }

    public synchronized Settings getEffectiveSettings(Dialogue dialogue, Node node) {
        if (dialogue == null) return new Settings();
        Settings effective = dialogue.getSettings().copy();
        if (node != null) effective.applyOverrides(node.getSettingsOverrides());
        return effective;
    }

    public synchronized Settings getEffectiveSettings(String dialogueId, String nodeId) {
        return getEffectiveSettings(getDialogue(dialogueId), getNode(dialogueId, nodeId));
    }

    // ---------------------------------------------------------------------
    // Global, branch and answer settings
    // ---------------------------------------------------------------------

    public synchronized String getGlobalSetting(String dialogueId, String key) {
        Dialogue dialogue = getDialogue(dialogueId);
        return dialogue == null ? null : settingAsString(dialogue.getSettings().get(key));
    }

    public synchronized boolean getGlobalBoolean(String dialogueId, String key) {
        Dialogue dialogue = getDialogue(dialogueId);
        return dialogue != null && dialogue.getSettings().getBoolean(key, false);
    }

    public synchronized void setGlobalSetting(String dialogueId, String key, String value) {
        Dialogue dialogue = getDialogue(dialogueId);
        if (dialogue != null) dialogue.getSettings().set(key, value);
    }

    public synchronized String getBranchSetting(String dialogueId, String branchId, String key) {
        Node node = getNode(dialogueId, branchId);
        return node == null ? null : settingAsString(node.getSettingsOverrides().get(key));
    }

    public synchronized void setBranchSetting(String dialogueId, String branchId, String key, String value) {
        Node node = getNode(dialogueId, branchId);
        if (node != null) node.getSettingsOverrides().set(key, value);
    }

    public synchronized void resetBranchSetting(String dialogueId, String branchId, String key) {
        Node node = getNode(dialogueId, branchId);
        if (node != null) node.getSettingsOverrides().set(key, null);
    }

    public synchronized String getAnswerSetting(String dialogueId, String branchId, String answerId, String key) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        return answer == null ? null : settingAsString(answer.getSettings().get(key));
    }

    public synchronized void setAnswerSetting(String dialogueId, String branchId, String answerId,
                                              String key, String value) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer != null) answer.getSettings().set(key, value);
    }

    public synchronized void setAnswerSoundVolume(String dialogueId, String branchId,
                                                   String answerId, float volume) {
        setAnswerSetting(dialogueId, branchId, answerId, "sound-volume",
                String.valueOf(Math.max(0.0f, Math.min(2.0f, volume))));
    }

    public synchronized void setAnswerSoundPitch(String dialogueId, String branchId,
                                                  String answerId, float pitch) {
        setAnswerSetting(dialogueId, branchId, answerId, "sound-pitch",
                String.valueOf(Math.max(0.5f, Math.min(2.0f, pitch))));
    }

    private String settingAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------------
    // Branch and answer commands
    // ---------------------------------------------------------------------

    public synchronized boolean addBranchCommand(String dialogueId, String branchId, String command) {
        Node node = getNode(dialogueId, branchId);
        if (node == null || command == null || command.isBlank()) return false;
        node.getCommandsInternal().add(command);
        return true;
    }

    public synchronized boolean removeBranchCommand(String dialogueId, String branchId, int index) {
        Node node = getNode(dialogueId, branchId);
        if (node == null || index < 0 || index >= node.getCommandsInternal().size()) return false;
        node.getCommandsInternal().remove(index);
        return true;
    }

    public synchronized boolean addAnswerCommand(String dialogueId, String branchId,
                                                  String answerId, String command) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer == null || command == null || command.isBlank()) return false;
        answer.getCommandsInternal().add(command);
        return true;
    }

    public synchronized boolean removeAnswerCommand(String dialogueId, String branchId,
                                                     String answerId, int index) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer == null || index < 0 || index >= answer.getCommandsInternal().size()) return false;
        answer.getCommandsInternal().remove(index);
        return true;
    }

    // ---------------------------------------------------------------------
    // Branch and answer creation/deletion
    // ---------------------------------------------------------------------

    public synchronized String createAnswer(String dialogueId, String branchId,
                                            String text, String targetNode) {
        Node node = getNode(dialogueId, branchId);
        if (node == null) return null;
        String id;
        do {
            id = "answer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (node.getAnswer(id) != null);
        Answer answer = new Answer(id, text == null ? "" : text);
        if (targetNode != null && getNode(dialogueId, targetNode) != null) answer.setTargetNode(targetNode);
        node.addAnswer(answer);
        return id;
    }

    public synchronized boolean deleteAnswer(String dialogueId, String branchId, String answerId) {
        Node node = getNode(dialogueId, branchId);
        if (node == null || !node.removeAnswer(answerId)) return false;
        deleteProgressForAnswer(dialogueId, branchId, answerId);
        return true;
    }

    public synchronized Answer getAnswer(String dialogueId, String branchId, String answerId) {
        Node node = getNode(dialogueId, branchId);
        return node == null ? null : node.getAnswer(answerId);
    }

    // ---------------------------------------------------------------------
    // Conditions
    // ---------------------------------------------------------------------

    public synchronized Condition getBranchCondition(String dialogueId, String branchId, String conditionId) {
        Node node = getNode(dialogueId, branchId);
        return node == null ? null : node.getConditionGroup().get(conditionId);
    }

    public synchronized Condition getAnswerCondition(String dialogueId, String branchId,
                                                      String answerId, String conditionId) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        return answer == null ? null : answer.getConditions().get(conditionId);
    }

    public synchronized boolean addBranchCondition(String dialogueId, String branchId,
                                                    String conditionId, String type,
                                                    String operator, String value) {
        Node node = getNode(dialogueId, branchId);
        if (node == null || conditionId == null || conditionId.isBlank()) return false;
        node.getConditionGroup().put(new Condition(conditionId, type, operator, value));
        return true;
    }

    public synchronized boolean addAnswerCondition(String dialogueId, String branchId,
                                                    String answerId, String conditionId,
                                                    String type, String operator, String value) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer == null || conditionId == null || conditionId.isBlank()) return false;
        answer.getConditions().put(new Condition(conditionId, type, operator, value));
        return true;
    }

    public synchronized boolean updateBranchCondition(String dialogueId, String branchId,
                                                       String conditionId, String type,
                                                       String operator, String value) {
        Condition condition = getBranchCondition(dialogueId, branchId, conditionId);
        if (condition == null) return false;
        condition.update(type, operator, value);
        return true;
    }

    public synchronized boolean updateAnswerCondition(String dialogueId, String branchId,
                                                       String answerId, String conditionId,
                                                       String type, String operator, String value) {
        Condition condition = getAnswerCondition(dialogueId, branchId, answerId, conditionId);
        if (condition == null) return false;
        condition.update(type, operator, value);
        return true;
    }

    public synchronized boolean deleteBranchCondition(String dialogueId, String branchId, String conditionId) {
        Node node = getNode(dialogueId, branchId);
        return node != null && node.getConditionGroup().remove(conditionId);
    }

    public synchronized boolean deleteAnswerCondition(String dialogueId, String branchId,
                                                       String answerId, String conditionId) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        return answer != null && answer.getConditions().remove(conditionId);
    }

    public synchronized String getBranchConditionLogic(String dialogueId, String branchId) {
        Node node = getNode(dialogueId, branchId);
        return node == null ? "AND" : node.getConditionGroup().getLogic();
    }

    public synchronized void toggleBranchConditionLogic(String dialogueId, String branchId) {
        Node node = getNode(dialogueId, branchId);
        if (node != null) node.getConditionGroup().toggleLogic();
    }

    public synchronized void toggleAnswerConditionLogic(String dialogueId, String branchId, String answerId) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer != null) answer.getConditions().toggleLogic();
    }

    // ---------------------------------------------------------------------
    // Rewards
    // ---------------------------------------------------------------------

    public synchronized boolean addBranchReward(String dialogueId, String branchId,
                                                String rewardId, String type,
                                                String value, int amount) {
        Node node = getNode(dialogueId, branchId);
        if (node == null || rewardId == null || rewardId.isBlank()) return false;
        node.getRewardsInternal().add(new Reward(rewardId, type, value, amount));
        return true;
    }

    public synchronized boolean addAnswerReward(String dialogueId, String branchId,
                                                String answerId, String rewardId,
                                                String type, String value, int amount) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        if (answer == null || rewardId == null || rewardId.isBlank()) return false;
        answer.getRewardsInternal().add(new Reward(rewardId, type, value, amount));
        return true;
    }

    public synchronized boolean updateBranchReward(String dialogueId, String branchId,
                                                   String rewardId, String type,
                                                   String value, Integer amount) {
        Reward reward = findBranchReward(dialogueId, branchId, rewardId);
        if (reward == null) return false;
        reward.update(type, value, amount);
        return true;
    }

    public synchronized boolean updateAnswerReward(String dialogueId, String branchId,
                                                   String answerId, String rewardId,
                                                   String type, String value, Integer amount) {
        Reward reward = findAnswerReward(dialogueId, branchId, answerId, rewardId);
        if (reward == null) return false;
        reward.update(type, value, amount);
        return true;
    }

    public synchronized boolean deleteBranchReward(String dialogueId, String branchId, String rewardId) {
        Node node = getNode(dialogueId, branchId);
        return node != null && node.removeReward(rewardId);
    }

    public synchronized boolean deleteAnswerReward(String dialogueId, String branchId,
                                                    String answerId, String rewardId) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        return answer != null && answer.removeReward(rewardId);
    }

    public synchronized boolean setBranchRewardItem(String dialogueId, String branchId,
                                                    String rewardId, ItemStack item) {
        Reward reward = findBranchReward(dialogueId, branchId, rewardId);
        if (reward == null || item == null) return false;
        reward.setItem(item);
        return true;
    }

    public synchronized boolean setAnswerRewardItem(String dialogueId, String branchId,
                                                    String answerId, String rewardId,
                                                    ItemStack item) {
        Reward reward = findAnswerReward(dialogueId, branchId, answerId, rewardId);
        if (reward == null || item == null) return false;
        reward.setItem(item);
        return true;
    }

    private Reward findBranchReward(String dialogueId, String branchId, String rewardId) {
        Node node = getNode(dialogueId, branchId);
        return node == null ? null : node.getReward(rewardId);
    }

    private Reward findAnswerReward(String dialogueId, String branchId, String answerId, String rewardId) {
        Answer answer = getAnswer(dialogueId, branchId, answerId);
        return answer == null ? null : answer.getReward(rewardId);
    }

    // ---------------------------------------------------------------------
    // Player progress
    // ---------------------------------------------------------------------

    public synchronized boolean isDialogueCompleted(UUID playerId, String dialogueId) {
        return readProgress("SELECT completed FROM player_progress WHERE player_uuid = ? AND dialogue_id = ?",
                playerId, dialogueId, null);
    }

    public synchronized boolean isBranchCompleted(UUID playerId, String dialogueId, String branchId) {
        return readProgress("SELECT completed FROM branch_progress WHERE player_uuid = ? AND dialogue_id = ? AND branch_id = ?",
                playerId, dialogueId, branchId);
    }

    /** Accepts a branch key in the form dialogueId:branchId. */
    public synchronized boolean isBranchCompleted(UUID playerId, String qualifiedBranchId) {
        if (qualifiedBranchId == null) return false;
        int separator = qualifiedBranchId.indexOf(':');
        if (separator < 1 || separator == qualifiedBranchId.length() - 1) return false;
        return isBranchCompleted(playerId, qualifiedBranchId.substring(0, separator),
                qualifiedBranchId.substring(separator + 1));
    }

    public synchronized boolean wasAnswerSelected(UUID playerId, String dialogueId,
                                                   String branchId, String answerId) {
        if (playerId == null || dialogueId == null || branchId == null || answerId == null || connection == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT selected FROM answer_progress WHERE player_uuid = ? AND dialogue_id = ? AND branch_id = ? AND answer_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, dialogueId);
            statement.setString(3, branchId);
            statement.setString(4, answerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        } catch (SQLException exception) {
            logDatabaseError("Could not read answer progress", exception);
            return false;
        }
    }

    public synchronized String getCurrentStoryStage(UUID playerId, String dialogueId) {
        if (playerId == null || dialogueId == null || connection == null) return null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_stage FROM player_progress WHERE player_uuid = ? AND dialogue_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, dialogueId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("current_stage") : null;
            }
        } catch (SQLException exception) {
            logDatabaseError("Could not read current story stage", exception);
            return null;
        }
    }

    public synchronized boolean setCurrentStoryStage(UUID playerId, String dialogueId, String stageId) {
        if (playerId == null || dialogueId == null || stageId == null || connection == null) return false;
        return progressUpsert("INSERT INTO player_progress(player_uuid, dialogue_id, completed, current_stage) VALUES(?, ?, 0, ?) " +
                "ON CONFLICT(player_uuid, dialogue_id) DO UPDATE SET current_stage = excluded.current_stage",
                playerId.toString(), dialogueId, stageId);
    }

    public synchronized void markDialogueCompleted(UUID playerId, String dialogueId) {
        if (playerId == null || dialogueId == null || connection == null) return;
        progressUpsert("INSERT INTO player_progress(player_uuid, dialogue_id, completed, completed_at, current_stage) VALUES(?, ?, 1, ?, 'completed') " +
                "ON CONFLICT(player_uuid, dialogue_id) DO UPDATE SET completed = 1, completed_at = excluded.completed_at, current_stage = 'completed'",
                playerId.toString(), dialogueId, System.currentTimeMillis());
    }

    public synchronized void markBranchCompleted(UUID playerId, String dialogueId, String branchId) {
        if (playerId == null || dialogueId == null || branchId == null || connection == null) return;
        progressUpsert("INSERT INTO branch_progress(player_uuid, dialogue_id, branch_id, completed, completed_at) VALUES(?, ?, ?, 1, ?) " +
                "ON CONFLICT(player_uuid, dialogue_id, branch_id) DO UPDATE SET completed = 1, completed_at = excluded.completed_at",
                playerId.toString(), dialogueId, branchId, System.currentTimeMillis());
    }

    public synchronized void markAnswerSelected(UUID playerId, String dialogueId,
                                                String branchId, String answerId) {
        if (playerId == null || dialogueId == null || branchId == null || answerId == null || connection == null) return;
        progressUpsert("INSERT INTO answer_progress(player_uuid, dialogue_id, branch_id, answer_id, selected, selected_at) VALUES(?, ?, ?, ?, 1, ?) " +
                "ON CONFLICT(player_uuid, dialogue_id, branch_id, answer_id) DO UPDATE SET selected = 1, selected_at = excluded.selected_at",
                playerId.toString(), dialogueId, branchId, answerId, System.currentTimeMillis());
    }

    private void deleteProgressForDialogue(String dialogueId) {
        if (connection == null) return;
        for (String table : List.of("player_progress", "branch_progress", "answer_progress")) {
            String sql = "DELETE FROM " + table + " WHERE dialogue_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dialogueId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                logDatabaseError("Could not delete progress for dialogue " + dialogueId, exception);
            }
        }
    }

    private void deleteProgressForBranch(String dialogueId, String branchId) {
        if (connection == null) return;
        for (String table : List.of("branch_progress", "answer_progress")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE dialogue_id = ? AND branch_id = ?")) {
                statement.setString(1, dialogueId);
                statement.setString(2, branchId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                logDatabaseError("Could not delete progress for branch " + branchId, exception);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE player_progress SET current_stage = NULL WHERE dialogue_id = ? AND current_stage = ?")) {
            statement.setString(1, dialogueId);
            statement.setString(2, branchId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            logDatabaseError("Could not clear deleted branch story stage " + branchId, exception);
        }
    }

    private void deleteProgressForAnswer(String dialogueId, String branchId, String answerId) {
        if (connection == null) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM answer_progress WHERE dialogue_id = ? AND branch_id = ? AND answer_id = ?")) {
            statement.setString(1, dialogueId);
            statement.setString(2, branchId);
            statement.setString(3, answerId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            logDatabaseError("Could not delete progress for answer " + answerId, exception);
        }
    }

    private boolean readProgress(String sql, UUID playerId, String dialogueId, String branchId) {
        if (playerId == null || dialogueId == null || connection == null) return false;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, dialogueId);
            if (branchId != null) statement.setString(3, branchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        } catch (SQLException exception) {
            logDatabaseError("Could not read player progress", exception);
            return false;
        }
    }

    private boolean progressUpsert(String sql, Object... values) {
        if (connection == null) return false;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            logDatabaseError("Could not update player progress", exception);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------

    private String serializeDialogue(Dialogue dialogue) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", dialogue.getId());
        yaml.set("name", dialogue.getName());
        yaml.set("root-node", dialogue.getRootNode());
        yaml.set("settings", dialogue.getSettings().serialize());

        for (Node node : dialogue.getNodes()) {
            String path = "nodes." + node.getId();
            yaml.set(path + ".name", node.getName());
            yaml.set(path + ".next-node", node.getNextNode());
            yaml.set(path + ".settings", node.getSettingsOverrides().serialize());
            yaml.set(path + ".commands", new ArrayList<>(node.getCommands()));
            yaml.set(path + ".conditions.logic", node.getConditionGroup().getLogic());
            yaml.set(path + ".conditions.entries", serializeConditions(node.getConditionGroup()));
            yaml.set(path + ".rewards", serializeRewards(node.getRewards()));

            for (Answer answer : node.getAnswers()) {
                String answerPath = path + ".answers." + answer.getId();
                yaml.set(answerPath + ".text", answer.getText());
                yaml.set(answerPath + ".target-node", answer.getTargetNode());
                yaml.set(answerPath + ".enabled", answer.isEnabled());
                yaml.set(answerPath + ".sound", answer.getSound());
                yaml.set(answerPath + ".settings", answer.getSettings().serialize());
                yaml.set(answerPath + ".commands", new ArrayList<>(answer.getCommands()));
                yaml.set(answerPath + ".conditions.logic", answer.getConditions().getLogic());
                yaml.set(answerPath + ".conditions.entries", serializeConditions(answer.getConditions()));
                yaml.set(answerPath + ".rewards", serializeRewards(answer.getRewards()));
            }
        }
        return yaml.saveToString();
    }

    private Dialogue deserializeDialogue(String id, String name, String serialized)
            throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(serialized == null ? "" : serialized);
        Dialogue dialogue = new Dialogue(id, yaml.getString("name", name));
        dialogue.setRootNode(yaml.getString("root-node", "start"));
        var globalSettings = yaml.getConfigurationSection("settings");
        if (globalSettings != null) {
            dialogue.getSettings().deserialize(globalSettings.getValues(false));
        }

        var nodesSection = yaml.getConfigurationSection("nodes");
        if (nodesSection != null) {
            for (String nodeId : nodesSection.getKeys(false)) {
                String path = "nodes." + nodeId;
                Node node = new Node(nodeId, yaml.getString(path + ".name", nodeId));
                node.setNextNode(yaml.getString(path + ".next-node"));
                var settingsSection = yaml.getConfigurationSection(path + ".settings");
                if (settingsSection != null) node.getSettingsOverrides().deserialize(settingsSection.getValues(false));
                node.getCommandsInternal().addAll(yaml.getStringList(path + ".commands"));
                var conditionSection = yaml.getConfigurationSection(path + ".conditions");
                if (conditionSection != null) {
                    node.getConditionGroup().setLogic(conditionSection.getString("logic", "AND"));
                    node.getConditionGroup().load(deserializeConditions(conditionSection.getMapList("entries")));
                }
                node.getRewardsInternal().addAll(deserializeRewards(yaml.getMapList(path + ".rewards")));

                var answersSection = yaml.getConfigurationSection(path + ".answers");
                if (answersSection != null) {
                    for (String answerId : answersSection.getKeys(false)) {
                        String answerPath = path + ".answers." + answerId;
                        Answer answer = new Answer(answerId, yaml.getString(answerPath + ".text", ""));
                        answer.setTargetNode(yaml.getString(answerPath + ".target-node"));
                        answer.setEnabled(yaml.getBoolean(answerPath + ".enabled", true));
                        answer.setSound(yaml.getString(answerPath + ".sound"));
                        var answerSettings = yaml.getConfigurationSection(answerPath + ".settings");
                        if (answerSettings != null) answer.getSettings().deserialize(answerSettings.getValues(false));
                        answer.getCommandsInternal().addAll(yaml.getStringList(answerPath + ".commands"));
                        var answerConditions = yaml.getConfigurationSection(answerPath + ".conditions");
                        if (answerConditions != null) {
                            answer.getConditions().setLogic(answerConditions.getString("logic", "AND"));
                            answer.getConditions().load(deserializeConditions(answerConditions.getMapList("entries")));
                        }
                        answer.getRewardsInternal().addAll(deserializeRewards(yaml.getMapList(answerPath + ".rewards")));
                        node.addAnswer(answer);
                    }
                }
                dialogue.addNode(node);
            }
        }
        if (dialogue.getNode(dialogue.getRootNode()) == null && !dialogue.getNodes().isEmpty()) {
            dialogue.setRootNode(dialogue.getNodes().iterator().next().getId());
        }
        return dialogue;
    }

    private List<Map<String, Object>> serializeConditions(ConditionGroup group) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Condition condition : group.getConditions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", condition.getId());
            entry.put("type", condition.getType().name());
            entry.put("operator", condition.getOperator());
            entry.put("value", condition.getValue());
            values.add(entry);
        }
        return values;
    }

    private List<Condition> deserializeConditions(List<Map<?, ?>> values) {
        List<Condition> conditions = new ArrayList<>();
        if (values == null) return conditions;
        for (Map<?, ?> value : values) {
            if (value == null) continue;
            String id = mapString(value, "id", "condition_" + conditions.size());
            String type = mapString(value, "type", "PERMISSION");
            String operator = mapString(value, "operator", "equals");
            String conditionValue = mapString(value, "value", "");
            conditions.add(new Condition(id, type, operator, conditionValue));
        }
        return conditions;
    }

    private List<Map<String, Object>> serializeRewards(List<Reward> rewards) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Reward reward : rewards) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", reward.getId());
            entry.put("type", reward.getType());
            entry.put("value", reward.getValue());
            entry.put("amount", reward.getAmount());
            if (reward.getItem() != null) entry.put("item", reward.getItem().serialize());
            values.add(entry);
        }
        return values;
    }

    private List<Reward> deserializeRewards(List<Map<?, ?>> values) {
        List<Reward> rewards = new ArrayList<>();
        if (values == null) return rewards;
        for (Map<?, ?> value : values) {
            if (value == null) continue;
            String id = mapString(value, "id", "reward_" + rewards.size());
            String type = mapString(value, "type", "command");
            String rewardValue = mapString(value, "value", "");
            int amount = readInt(value.get("amount"), 1);
            Reward reward = new Reward(id, type, rewardValue, amount);
            Object itemData = value.get("item");
            if (itemData instanceof Map<?, ?> map) {
                try {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        itemMap.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    reward.setItem(ItemStack.deserialize(itemMap));
                } catch (Exception exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not deserialize an item reward.", exception);
                }
            }
            rewards.add(reward);
        }
        return rewards;
    }

    private String mapString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int readInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    public synchronized boolean writeExample() {
        if (hasDialogue("example")) return false;
        Dialogue dialogue = createDialogue("example");
        if (dialogue == null) return false;
        Node start = dialogue.getNode("start");
        start.setName("Вітаю у світі!");
        createAnswer(dialogue.getId(), "start", "Почати пригоду", null);
        return saveDialogue(dialogue);
    }

    private void logDatabaseError(String message, SQLException exception) {
        plugin.getLogger().log(Level.SEVERE, message, exception);
    }

    private boolean isValidId(String value) {
        return value != null && value.matches("[A-Za-z0-9_\\-]{1,64}");
    }

    private String normalizeId(String value) {
        return value.trim();
    }

    private String normalizeLookupId(String value) {
        String exact = value.trim();
        if (dialogues.containsKey(exact)) return exact;
        for (String id : dialogues.keySet()) if (id.equalsIgnoreCase(exact)) return id;
        return exact;
    }

    // =====================================================================
    // Dialogue model
    // =====================================================================

    public static final class Dialogue {
        private final String id;
        private String name;
        private String rootNode = "start";
        private final Settings settings = new Settings();
        private final Map<String, Node> nodes = new LinkedHashMap<>();

        public Dialogue(String id, String name) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = name == null ? id : name;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name == null ? id : name; }
        public String getRootNode() { return rootNode; }
        public void setRootNode(String rootNode) { this.rootNode = rootNode == null ? "start" : rootNode; }
        public Settings getSettings() { return settings; }
        public Collection<Node> getNodes() { return Collections.unmodifiableCollection(nodes.values()); }
        public Node getNode(String id) { return id == null ? null : nodes.get(id); }
        public void addNode(Node node) { if (node != null) nodes.put(node.getId(), node); }
        public Node removeNode(String id) { return id == null ? null : nodes.remove(id); }
        public Dialogue copy(String newId, String newName) {
            Dialogue copy = new Dialogue(newId, newName);
            copy.rootNode = rootNode;
            copy.settings.deserialize(settings.serialize());
            for (Node node : nodes.values()) copy.addNode(node.copy());
            return copy;
        }
    }

    public static final class Node {
        private final String id;
        private String name;
        private String nextNode;
        private final Settings settingsOverrides = new Settings();
        private final List<String> commands = new ArrayList<>();
        private final ConditionGroup conditionGroup = new ConditionGroup();
        private final List<Reward> rewards = new ArrayList<>();
        private final List<Answer> answers = new ArrayList<>();

        public Node(String id, String name) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = name == null ? id : name;
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name == null ? "" : name; }
        public String getNextNode() { return nextNode; }
        public void setNextNode(String nextNode) { this.nextNode = nextNode; }
        public Settings getSettingsOverrides() { return settingsOverrides; }
        public List<String> getCommands() { return Collections.unmodifiableList(commands); }
        List<String> getCommandsInternal() { return commands; }
        public List<Condition> getConditions() { return conditionGroup.getConditions(); }
        public ConditionGroup getConditionGroup() { return conditionGroup; }
        public List<Reward> getRewards() { return Collections.unmodifiableList(rewards); }
        List<Reward> getRewardsInternal() { return rewards; }
        public List<Answer> getAnswers() { return Collections.unmodifiableList(answers); }
        public Answer getAnswer(String answerId) {
            if (answerId == null) return null;
            for (Answer answer : answers) if (answer.getId().equals(answerId)) return answer;
            return null;
        }
        public void addAnswer(Answer answer) { if (answer != null) answers.add(answer); }
        public boolean removeAnswer(String answerId) { return answers.removeIf(a -> a.getId().equals(answerId)); }
        public Reward getReward(String rewardId) {
            if (rewardId == null) return null;
            for (Reward reward : rewards) if (reward.getId().equals(rewardId)) return reward;
            return null;
        }
        public boolean removeReward(String rewardId) { return rewards.removeIf(r -> r.getId().equals(rewardId)); }
        Node copyWithId(String newId) {
            Node copy = new Node(newId, name);
            copy.nextNode = nextNode;
            copy.settingsOverrides.deserialize(settingsOverrides.serialize());
            copy.commands.addAll(commands);
            copy.conditionGroup.load(conditionGroup.copy().getConditions());
            copy.conditionGroup.setLogic(conditionGroup.getLogic());
            for (Reward reward : rewards) copy.rewards.add(reward.copy());
            for (Answer answer : answers) copy.addAnswer(answer.copy());
            return copy;
        }
        public Node copy() { return copyWithId(id); }
    }

    public static final class Answer {
        private final String id;
        private String text;
        private String targetNode;
        private boolean enabled = true;
        private String sound;
        private final Settings settings = new Settings();
        private final List<String> commands = new ArrayList<>();
        private final ConditionGroup conditions = new ConditionGroup();
        private final List<Reward> rewards = new ArrayList<>();

        public Answer(String id, String text) {
            this.id = Objects.requireNonNull(id, "id");
            this.text = text == null ? "" : text;
        }
        public String getId() { return id; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text == null ? "" : text; }
        public String getTargetNode() { return targetNode; }
        public void setTargetNode(String targetNode) { this.targetNode = targetNode; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSound() { return sound; }
        public void setSound(String sound) { this.sound = sound; }
        public Settings getSettings() { return settings; }
        public List<String> getCommands() { return Collections.unmodifiableList(commands); }
        List<String> getCommandsInternal() { return commands; }
        public ConditionGroup getConditions() { return conditions; }
        public List<Reward> getRewards() { return Collections.unmodifiableList(rewards); }
        List<Reward> getRewardsInternal() { return rewards; }
        public Reward getReward(String rewardId) {
            if (rewardId == null) return null;
            for (Reward reward : rewards) if (reward.getId().equals(rewardId)) return reward;
            return null;
        }
        public boolean removeReward(String rewardId) { return rewards.removeIf(r -> r.getId().equals(rewardId)); }
        public Answer copy() {
            Answer copy = new Answer(id, text);
            copy.targetNode = targetNode;
            copy.enabled = enabled;
            copy.sound = sound;
            copy.settings.deserialize(settings.serialize());
            copy.commands.addAll(commands);
            copy.conditions.load(conditions.copy().getConditions());
            copy.conditions.setLogic(conditions.getLogic());
            for (Reward reward : rewards) copy.rewards.add(reward.copy());
            return copy;
        }
    }

    public static final class Settings {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private Location cameraLocation;

        public Location getCameraLocation() {
            if (cameraLocation != null) return cameraLocation.clone();
            Object raw = values.get("camera-location");
            if (raw instanceof Map<?, ?> map) {
                Object worldName = map.get("world");
                World world = worldName == null ? null : Bukkit.getWorld(String.valueOf(worldName));
                if (world != null) {
                    cameraLocation = new Location(world, number(map.get("x")), number(map.get("y")),
                            number(map.get("z")), (float) number(map.get("yaw")), (float) number(map.get("pitch")));
                    return cameraLocation.clone();
                }
            }
            if (raw instanceof String string) return parseLocation(string);
            return null;
        }
        public void setCameraLocation(Location location) {
            cameraLocation = location == null ? null : location.clone();
            if (location == null) values.remove("camera-location");
            else {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("world", location.getWorld() == null ? "" : location.getWorld().getName());
                map.put("x", location.getX()); map.put("y", location.getY()); map.put("z", location.getZ());
                map.put("yaw", location.getYaw()); map.put("pitch", location.getPitch());
                values.put("camera-location", map);
            }
        }
        public Object get(String key) { return values.get(key); }
        public Object get(String key, Object fallback) { return values.getOrDefault(key, fallback); }
        public void set(String key, Object value) {
            if (key == null || key.isBlank()) return;
            if (value == null) values.remove(key); else values.put(key, value);
            if ("camera-location".equals(key)) cameraLocation = null;
        }
        public boolean contains(String key) { return values.containsKey(key); }
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            if (value instanceof Boolean b) return b;
            if (value instanceof String s) return Boolean.parseBoolean(s);
            return fallback;
        }
        public double getDouble(String key, double fallback) {
            Object value = values.get(key);
            if (value instanceof Number n) return n.doubleValue();
            try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            if (value instanceof Number n) return n.intValue();
            try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : String.valueOf(value);
        }
        public void applyOverrides(Settings overrides) {
            if (overrides == null) return;
            values.putAll(overrides.values);
            if (overrides.values.containsKey("camera-location")) {
                cameraLocation = overrides.cameraLocation == null ? null : overrides.cameraLocation.clone();
            }
        }
        public Map<String, Object> serialize() {
            Map<String, Object> result = new LinkedHashMap<>(values);
            if (cameraLocation != null && cameraLocation.getWorld() != null) {
                Map<String, Object> location = new LinkedHashMap<>();
                location.put("world", cameraLocation.getWorld().getName());
                location.put("x", cameraLocation.getX()); location.put("y", cameraLocation.getY());
                location.put("z", cameraLocation.getZ()); location.put("yaw", cameraLocation.getYaw());
                location.put("pitch", cameraLocation.getPitch());
                result.put("camera-location", location);
            }
            return result;
        }
        public void deserialize(Map<?, ?> data) {
            values.clear(); cameraLocation = null;
            if (data == null) return;
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                if (entry.getKey() == null) continue;
                Object value = entry.getValue();
                if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
                    value = section.getValues(false);
                }
                values.put(String.valueOf(entry.getKey()), value);
            }
        }
        public Settings copy() { Settings copy = new Settings(); copy.deserialize(serialize()); return copy; }
        private static Location parseLocation(String value) {
            String[] parts = value.split(",");
            if (parts.length < 4) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            try {
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0;
                float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0;
                return new Location(world, x, y, z, yaw, pitch);
            } catch (NumberFormatException ignored) { return null; }
        }
        private static double number(Object value) {
            if (value instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (Exception ignored) { return 0; }
        }
    }

    public static final class ConditionGroup extends AbstractList<Condition> {
        private final Map<String, Condition> conditions = new LinkedHashMap<>();
        private String logic = "AND";
        @Override public Condition get(int index) { return new ArrayList<>(conditions.values()).get(index); }
        @Override public int size() { return conditions.size(); }
        @Override public void add(int index, Condition condition) {
            if (condition == null) return;
            List<Condition> ordered = new ArrayList<>(conditions.values());
            if (index < 0 || index > ordered.size()) throw new IndexOutOfBoundsException(index);
            ordered.add(index, condition);
            conditions.clear();
            for (Condition entry : ordered) conditions.put(entry.getId(), entry);
        }
        @Override public Condition remove(int index) {
            List<Condition> ordered = new ArrayList<>(conditions.values());
            Condition removed = ordered.remove(index);
            conditions.clear();
            for (Condition entry : ordered) conditions.put(entry.getId(), entry);
            return removed;
        }
        public List<Condition> getConditions() { return Collections.unmodifiableList(new ArrayList<>(conditions.values())); }
        public Condition get(String id) { return id == null ? null : conditions.get(id); }
        public void put(Condition condition) { if (condition != null) conditions.put(condition.getId(), condition); }
        public boolean remove(String id) { return id != null && conditions.remove(id) != null; }
        public void clear() { conditions.clear(); }
        public String getLogic() { return logic; }
        public void setLogic(String logic) { this.logic = logic != null && logic.equalsIgnoreCase("OR") ? "OR" : "AND"; }
        public boolean isEmpty() { return conditions.isEmpty(); }
        public void toggleLogic() { logic = logic.equals("AND") ? "OR" : "AND"; }
        void load(Collection<Condition> values) { conditions.clear(); if (values != null) for (Condition c : values) put(c); }
        public ConditionGroup copy() { ConditionGroup copy = new ConditionGroup(); copy.logic = logic; for (Condition c : conditions.values()) copy.put(c.copy()); return copy; }
    }

    public enum ConditionType {
        ITEM, MINECRAFT_TAG, LUCKPERMS_GROUP, COMPLETED_BRANCH,
        PERMISSION, TAG, DIALOGUE_COMPLETED, BRANCH_COMPLETED,
        ANSWER_SELECTED, LEVEL, UNKNOWN;
        public String toLowerCase() { return name().toLowerCase(Locale.ROOT); }
        public static ConditionType from(String value) {
            if (value == null) return UNKNOWN;
            String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
            try { return valueOf(normalized); }
            catch (IllegalArgumentException ignored) { return UNKNOWN; }
        }
    }

    public static final class Condition {
        private final String id;
        private ConditionType type;
        private String value;
        private String operator;
        public Condition(String id, String type, String operator, String value) {
            this.id = id == null ? "condition" : id;
            this.type = ConditionType.from(type);
            this.operator = operator == null ? "equals" : operator;
            this.value = value == null ? "" : value;
        }
        public Condition(String type, String value, String operator) { this(null, type, operator, value); }
        public String getId() { return id; }
        public ConditionType getType() { return type; }
        public void setType(String type) { this.type = ConditionType.from(type); }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value == null ? "" : value; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator == null ? "equals" : operator; }
        public void update(String type, String operator, String value) {
            if (type != null) setType(type);
            if (operator != null) setOperator(operator);
            if (value != null) setValue(value);
        }
        public Condition copy() { return new Condition(id, type.name(), operator, value); }
    }

    public static final class Reward {
        private final String id;
        private String type;
        private String value;
        private int amount;
        private ItemStack item;
        public Reward(String id, String type, String value, int amount) {
            this.id = id == null ? "reward" : id;
            this.type = type == null ? "command" : type;
            this.value = value == null ? "" : value;
            this.amount = Math.max(1, amount);
        }
        public Reward(String type, String value, int amount) { this(null, type, value, amount); }
        public String getId() { return id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type == null ? "command" : type; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value == null ? "" : value; }
        public int getAmount() { return amount; }
        public void setAmount(int amount) {
            this.amount = Math.max(1, amount);
            if (item != null) item.setAmount(this.amount);
        }
        public ItemStack getItem() { return item == null ? null : item.clone(); }
        public void setItem(ItemStack item) {
            this.item = item == null ? null : item.clone();
            if (this.item != null) this.item.setAmount(this.amount);
        }
        public void update(String type, String value, Integer amount) {
            if (type != null) setType(type);
            if (value != null) setValue(value);
            if (amount != null) setAmount(amount);
        }
        public Reward copy() { Reward copy = new Reward(id, type, value, amount); copy.setItem(item); return copy; }
    }
}
