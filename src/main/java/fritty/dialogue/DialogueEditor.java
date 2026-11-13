package fritty.dialogue;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class DialogueEditor implements Listener {

    private static final int INVENTORY_SIZE = 54;
    private static final int ENTRIES_PER_PAGE = 28;

    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_CREATE = 48;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private static final Component PREFIX =
            Component.text("Fritty", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD)
                    .append(Component.text(
                            "Dialogue",
                            NamedTextColor.WHITE
                    ).decorate(TextDecoration.BOLD));

    private final FrittyDialogue plugin;
    private final DialogueCore core;
    private final DialogueRuntime runtime;

    private final Map<UUID, ChatPrompt> chatPrompts =
            new HashMap<>();

    private boolean initialized;

    public DialogueEditor(
            FrittyDialogue plugin,
            DialogueCore core,
            DialogueRuntime runtime
    ) {
        this.plugin = plugin;
        this.core = core;
        this.runtime = runtime;
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private enum Screen {
        MAIN,
        DIALOGUE,
        NODES,
        NODE,
        ANSWER,
        SETTINGS,
        SOUND_PICKER,
        CONDITION_EDITOR,
        REWARD_EDITOR,
        BRANCH_PICKER,
        CONFIRM_DELETE,
        COMMANDS,
        SOUND_PARAMETERS,
        COLOR_PICKER,
        BRANCH_CONDITIONS,
        BRANCH_REWARDS,
        INHERITANCE,
        NODE_LIST,
        ANSWER_LIST,
        CONDITION_LIST,
        REWARD_LIST,
        CONDITION,
        REWARD
    }

    private static final class EditorHolder
            implements InventoryHolder {

        private final Screen screen;
        private final String dialogueId;
        private final String nodeId;
        private final String answerId;
        private String extraId;
        private final int page;
        private final List<String> entries =
                new ArrayList<>();

        private Inventory inventory;

        private EditorHolder(
                Screen screen,
                String dialogueId,
                String nodeId,
                String answerId,
                int page
        ) {
            this.screen = screen;
            this.dialogueId = dialogueId;
            this.nodeId = nodeId;
            this.answerId = answerId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record ChatPrompt(
            Consumer<String> handler,
            Runnable cancel
    ) {
    }

    private ItemStack item(
            Material material,
            String name,
            NamedTextColor color,
            String... lore
    ) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(
                Component.text(name, color)
                        .decoration(TextDecoration.ITALIC, false)
        );

        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();

            for (String line : lore) {
                lines.add(
                        Component.text(
                                line,
                                NamedTextColor.GRAY
                        ).decoration(TextDecoration.ITALIC, false)
                );
            }

            meta.lore(lines);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private void fillBackground(Inventory inventory) {
        ItemStack background = item(
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                NamedTextColor.GRAY
        );

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, background);
        }
    }

    private int[] createEntrySlots() {
        return new int[]{
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
    }

    private EditorHolder createHolder(
            Screen screen,
            String dialogueId,
            String nodeId,
            String answerId,
            int page,
            String title
    ) {
        EditorHolder holder = new EditorHolder(
                screen,
                dialogueId,
                nodeId,
                answerId,
                page
        );

        holder.inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text(title, NamedTextColor.DARK_RED)
        );

        fillBackground(holder.inventory);
        return holder;
    }
    private Inventory createHolder(
            Player player,
            Screen screen,
            String dialogueId,
            String nodeId,
            String answerId,
            int size,
            String title
    ) {
        EditorHolder holder = new EditorHolder(
                screen,
                dialogueId,
                nodeId,
                answerId,
                0
        );

        holder.inventory = Bukkit.createInventory(
                holder,
                size,
                Component.text(title, NamedTextColor.DARK_RED)
        );

        fillBackground(holder.inventory);
        return holder.inventory;
        }

    private void open(Player player, EditorHolder holder) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.openInventory(holder.inventory);
    }

    private void save(String dialogueId) {
        if (dialogueId != null) {
            core.saveDialogue(dialogueId);
        }
    }

    private DialogueCore.Dialogue getDialogue(String dialogueId) {
        return core.getDialogue(dialogueId);
    }

    private DialogueCore.Node getNode(
            String dialogueId,
            String nodeId
    ) {
        DialogueCore.Dialogue dialogue = getDialogue(dialogueId);

        if (dialogue == null) {
            return null;
        }

        return dialogue.getNode(nodeId);
    }

    // ============================================================
    // MAIN MENU
    // ============================================================

    public void openMainMenu(Player player) {
        openMainMenu(player, 0);
    }

    public void openMainMenu(Player player, int page) {
        if (player == null || !player.isOnline()) {
            return;
        }

        List<String> dialogueIds =
                new ArrayList<>(core.getDialogueIds());

        dialogueIds.sort(String.CASE_INSENSITIVE_ORDER);

        int maxPage = Math.max(
                0,
                (dialogueIds.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.MAIN,
                null,
                null,
                null,
                page,
                "FrittyDialogue • Діалоги"
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                dialogueIds.size()
        );

        for (int i = start; i < end; i++) {
            String id = dialogueIds.get(i);

            holder.entries.add(id);

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            Material.WRITABLE_BOOK,
                            id,
                            NamedTextColor.GOLD,
                            "ЛКМ — відкрити редактор",
                            "ПКМ — перейменувати",
                            "Shift + ПКМ — видалити"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_CREATE,
                item(
                        Material.EMERALD,
                        "Створити діалог",
                        NamedTextColor.GREEN,
                        "Створити новий діалог"
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        holder.inventory.setItem(
                4,
                item(
                        Material.BOOK,
                        "Редактор діалогів",
                        NamedTextColor.GOLD,
                        "Усього діалогів: " + dialogueIds.size()
                )
        );

        open(player, holder);
    }

    // ============================================================
    // DIALOGUE MENU
    // ============================================================

    private void openDialogueMenu(
            Player player,
            String dialogueId
    ) {
        DialogueCore.Dialogue dialogue =
                getDialogue(dialogueId);

        if (dialogue == null) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Діалог не знайдено.",
                            NamedTextColor.RED
                    ))
            );
            openMainMenu(player);
            return;
        }

        EditorHolder holder = createHolder(
                Screen.DIALOGUE,
                dialogueId,
                null,
                null,
                0,
                "Діалог • " + dialogueId
        );

        holder.inventory.setItem(
                10,
                item(
                        Material.WRITABLE_BOOK,
                        "Гілки діалогу",
                        NamedTextColor.YELLOW,
                        "Переглянути та редагувати гілки",
                        "Головна гілка start створюється автоматично"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.COMPARATOR,
                        "Налаштування діалогу",
                        NamedTextColor.YELLOW,
                        "Загальні параметри діалогу"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.NAME_TAG,
                        "Перейменувати діалог",
                        NamedTextColor.YELLOW,
                        "Змінити ідентифікатор діалогу"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.BARRIER,
                        "Видалити діалог",
                        NamedTextColor.RED,
                        "Видалити цей діалог повністю"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад",
                        NamedTextColor.YELLOW
                )
        );

        open(player, holder);
    }

    // ============================================================
    // BRANCH LIST
    // ============================================================

    private void openNodeList(
            Player player,
            String dialogueId,
            int page
    ) {
        DialogueCore.Dialogue dialogue =
                getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player);
            return;
        }

        List<DialogueCore.Node> nodes =
                new ArrayList<>(dialogue.getNodes());

        nodes.sort((first, second) -> {
            if (first.getId().equalsIgnoreCase("start")) {
                return -1;
            }

            if (second.getId().equalsIgnoreCase("start")) {
                return 1;
            }

            return first.getId().compareToIgnoreCase(
                    second.getId()
            );
        });

        int maxPage = Math.max(
                0,
                (nodes.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.NODES,
                dialogueId,
                null,
                null,
                page,
                "Гілки • " + dialogueId
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                nodes.size()
        );

        for (int i = start; i < end; i++) {
            DialogueCore.Node node = nodes.get(i);
            String nodeId = node.getId();

            holder.entries.add(nodeId);

            boolean mainBranch =
                    nodeId.equalsIgnoreCase("start");

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            mainBranch
                                    ? Material.ENCHANTED_BOOK
                                    : Material.PAPER,
                            nodeId,
                            mainBranch
                                    ? NamedTextColor.GOLD
                                    : NamedTextColor.YELLOW,
                            mainBranch
                                    ? "Головна гілка"
                                    : "Додаткова гілка",
                            "ЛКМ — редагувати",
                            "ПКМ — перейменувати",
                            mainBranch
                                    ? "Головну гілку не можна видалити"
                                    : "Shift + ПКМ — видалити"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_CREATE,
                item(
                        Material.PAPER,
                        "Створити гілку",
                        NamedTextColor.GREEN,
                        "Створити нову додаткову гілку"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад",
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }

    // ============================================================
    // BRANCH EDITOR
    // ============================================================

    private void openNodeMenu(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        DialogueCore.Node node =
                getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        EditorHolder holder = createHolder(
                Screen.NODE,
                dialogueId,
                nodeId,
                null,
                0,
                "Гілка • " + nodeId
        );

        holder.inventory.setItem(
                10,
                item(
                        Material.WRITABLE_BOOK,
                        "Текст діалогу",
                        NamedTextColor.YELLOW,
                        "Змінити текст цієї гілки"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.CLOCK,
                        "Затримка тексту",
                        NamedTextColor.YELLOW,
                        "Налаштувати швидкість появи тексту"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.OAK_SIGN,
                        "Відповіді",
                        NamedTextColor.YELLOW,
                        "Додати або редагувати відповіді",
                        "Кількість відповідей не обмежена"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.COMPARATOR,
                        "Налаштування гілки",
                        NamedTextColor.YELLOW,
                        "Камера, координати, звук та інші параметри",
                        "Кожен параметр може успадковуватися окремо"
                )
        );

        holder.inventory.setItem(
                28,
                item(
                        Material.NAME_TAG,
                        "Перейменувати гілку",
                        NamedTextColor.YELLOW
                )
        );

        if (!nodeId.equalsIgnoreCase("start")) {
            holder.inventory.setItem(
                    30,
                    item(
                            Material.BARRIER,
                            "Видалити гілку",
                            NamedTextColor.RED
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до гілок",
                        NamedTextColor.YELLOW
                )
        );

        open(player, holder);
    }
    // ============================================================
    // ANSWER LIST
    // ============================================================

    private void openAnswerList(
            Player player,
            String dialogueId,
            String nodeId,
            int page
    ) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        List<DialogueCore.Answer> answers =
                new ArrayList<>(node.getAnswers());

        int maxPage = Math.max(
                0,
                (answers.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.ANSWER,
                dialogueId,
                nodeId,
                null,
                page,
                "Відповіді • " + nodeId
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                answers.size()
        );

        for (int i = start; i < end; i++) {
            DialogueCore.Answer answer = answers.get(i);

            holder.entries.add(answer.getId());

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            Material.PAPER,
                            answer.getText(),
                            answer.isEnabled()
                                    ? NamedTextColor.YELLOW
                                    : NamedTextColor.RED,
                            "Ціль: " + answer.getTargetNode(),
                            "ЛКМ — редагувати",
                            "ПКМ — увімкнути/вимкнути",
                            "Shift + ПКМ — видалити"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_CREATE,
                item(
                        Material.WRITABLE_BOOK,
                        "Створити відповідь",
                        NamedTextColor.GREEN,
                        "Додати нову відповідь до гілки"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до гілки",
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }

    // ============================================================
    // ANSWER EDITOR
    // ============================================================

    private void openAnswerMenu(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        DialogueCore.Answer answer = node.getAnswer(answerId);

        if (answer == null) {
            openAnswerList(player, dialogueId, nodeId, 0);
            return;
        }

        EditorHolder holder = createHolder(
                Screen.ANSWER,
                dialogueId,
                nodeId,
                answerId,
                0,
                "Відповідь • " + answerId
        );

        holder.inventory.setItem(
                10,
                item(
                        Material.NAME_TAG,
                        "Текст відповіді",
                        NamedTextColor.YELLOW,
                        answer.getText()
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.COMPASS,
                        "Цільова гілка",
                        NamedTextColor.YELLOW,
                        "Зараз: " + answer.getTargetNode(),
                        "Можна вибрати будь-яку гілку",
                        "Дозволено переходити в ту саму гілку"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.REDSTONE,
                        "Умови доступності",
                        NamedTextColor.YELLOW,
                        "Налаштувати умови показу відповіді"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.COMMAND_BLOCK,
                        "Команди відповіді",
                        NamedTextColor.YELLOW,
                        "Команди після вибору відповіді"
                )
        );

        holder.inventory.setItem(
                28,
                item(
                        Material.CHEST,
                        "Нагороди відповіді",
                        NamedTextColor.YELLOW,
                        "Налаштувати нагороди за вибір"
                )
        );

        holder.inventory.setItem(
                30,
                item(
                        Material.NOTE_BLOCK,
                        "Звук відповіді",
                        NamedTextColor.YELLOW,
                        "Налаштувати звук вибору"
                )
        );

        holder.inventory.setItem(
                32,
                item(
                        answer.isEnabled()
                                ? Material.LIME_DYE
                                : Material.GRAY_DYE,
                        answer.isEnabled()
                                ? "Відповідь увімкнена"
                                : "Відповідь вимкнена",
                        answer.isEnabled()
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED,
                        "Натисни, щоб перемкнути стан"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до відповідей",
                        NamedTextColor.YELLOW
                )
        );

        holder.inventory.setItem(
                50,
                item(
                        Material.BARRIER,
                        "Видалити відповідь",
                        NamedTextColor.RED
                )
        );

        open(player, holder);
    }

    // ============================================================
    // DIALOGUE SETTINGS
    // ============================================================

    private void openDialogueSettings(
            Player player,
            String dialogueId
    ) {
        DialogueCore.Dialogue dialogue =
                getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player);
            return;
        }

        EditorHolder holder = createHolder(
                Screen.SETTINGS,
                dialogueId,
                null,
                null,
                0,
                "Налаштування • " + dialogueId
        );
        
        holder.inventory.setItem(
                10,
                item(
                        Material.PLAYER_HEAD,
                        "Приховати інших гравців",
                        NamedTextColor.YELLOW,
                        "Перемикання видимості інших гравців"
                )
        );

        holder.inventory.setItem(
                11,
                item(
                        Material.CLOCK,
                        "Одноразовий діалог",
                        NamedTextColor.YELLOW,
                        "Дозволити проходження лише один раз"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.GLASS,
                        "Видимість крізь блоки",
                        NamedTextColor.YELLOW,
                        "Налаштування видимості тексту"
                )
        );

        holder.inventory.setItem(
                13,
                item(
                        Material.ENDER_EYE,
                        "Позиція тексту",
                        NamedTextColor.YELLOW,
                        "Задати глобальну позицію тексту"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.SPYGLASS,
                        "Позиція камери",
                        NamedTextColor.YELLOW,
                        "Зберегти позицію камери гравця"
                )
        );

        holder.inventory.setItem(
                15,
                item(
                        Material.ARMOR_STAND,
                        "Плавна камера",
                        NamedTextColor.YELLOW,
                        "Увімкнути або вимкнути плавний рух"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.NOTE_BLOCK,
                        "Звук друку",
                        NamedTextColor.YELLOW,
                        "Вибрати звук появи символів"
                )
        );

        holder.inventory.setItem(
                19,
                item(
                        Material.REPEATER,
                        "Швидкість друку",
                        NamedTextColor.YELLOW,
                        "Затримка між символами"
                )
        );

        holder.inventory.setItem(
                20,
                item(
                        Material.WRITABLE_BOOK,
                        "Друк посимвольно",
                        NamedTextColor.YELLOW,
                        "Увімкнути або вимкнути анімацію"
                )
        );

        holder.inventory.setItem(
                21,
                item(
                        Material.JUKEBOX,
                        "Гучність і тон",
                        NamedTextColor.YELLOW,
                        "Налаштувати параметри звуку"
                )
        );

        holder.inventory.setItem(
                22,
                item(
                        Material.MAP,
                        "Масштаб тексту",
                        NamedTextColor.YELLOW,
                        "Змінити розмір тексту"
                )
        );

        holder.inventory.setItem(
                23,
                item(
                        Material.PAPER,
                        "Ширина текстового вікна",
                        NamedTextColor.YELLOW,
                        "Змінити ширину вікна"
                )
        );

        holder.inventory.setItem(
                24,
                item(
                        Material.BLACK_STAINED_GLASS_PANE,
                        "Прозорість фону",
                        NamedTextColor.YELLOW,
                        "Змінити прозорість фону вікна"
                )
        );

        holder.inventory.setItem(
                25,
                item(
                        Material.POTION,
                        "Невидимість",
                        NamedTextColor.YELLOW,
                        "Налаштувати ефект невидимості"
                )
        );

        holder.inventory.setItem(
                29,
                item(
                        Material.RECOVERY_COMPASS,
                        "Повернення на початкову позицію",
                        NamedTextColor.YELLOW,
                        "Після завершення діалогу повернути гравця",
                        "до місця, де він перебував перед початком"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад",
                        NamedTextColor.YELLOW
                )
        );

        open(player, holder);
    }

    // ============================================================
    // BRANCH SETTINGS
    // ============================================================

    private void openBranchSettings(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        DialogueCore.Node node =
                getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        EditorHolder holder = createHolder(
                Screen.SETTINGS,
                dialogueId,
                nodeId,
                null,
                0,
                "Налаштування гілки • " + nodeId
        );

        holder.inventory.setItem(
                10,
                item(
                        Material.PAINTING,
                        "Колір тексту",
                        NamedTextColor.YELLOW,
                        "Змінити лише колір тексту"
                )
        );

        holder.inventory.setItem(
                11,
                item(
                        Material.BLACK_CONCRETE,
                        "Колір фону",
                        NamedTextColor.YELLOW,
                        "Змінити лише колір фону"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.MAP,
                        "Масштаб тексту",
                        NamedTextColor.YELLOW,
                        "Змінити лише розмір тексту"
                )
        );

        holder.inventory.setItem(
                13,
                item(
                        Material.ENDER_EYE,
                        "Позиція тексту",
                        NamedTextColor.YELLOW,
                        "Задати координати тексту"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.SPYGLASS,
                        "Позиція камери",
                        NamedTextColor.YELLOW,
                        "Зберегти поточну позицію гравця"
                )
        );

        holder.inventory.setItem(
                15,
                item(
                        Material.CLOCK,
                        "Затримка друку",
                        NamedTextColor.YELLOW,
                        "Змінити швидкість появи тексту"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.NOTE_BLOCK,
                        "Звук друку",
                        NamedTextColor.YELLOW,
                        "Вибрати звук для цієї гілки"
                )
        );

        holder.inventory.setItem(
                19,
                item(
                        Material.COMMAND_BLOCK,
                        "Команди гілки",
                        NamedTextColor.YELLOW,
                        "Налаштувати команди гілки"
                )
        );

        holder.inventory.setItem(
                20,
                item(
                        Material.REDSTONE,
                        "Умови гілки",
                        NamedTextColor.YELLOW,
                        "Налаштувати умови входу"
                )
        );

        holder.inventory.setItem(
                21,
                item(
                        Material.CHEST,
                        "Нагороди гілки",
                        NamedTextColor.YELLOW,
                        "Налаштувати нагороди"
                )
        );

        holder.inventory.setItem(
                23,
                item(
                        Material.BARRIER,
                        "Скинути параметр",
                        NamedTextColor.YELLOW,
                        "Повернути вибраний параметр до успадкування"
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до гілки",
                        NamedTextColor.YELLOW
                )
        );

        open(player, holder);
    }
    // ============================================================
    // CONDITION EDITOR
    // ============================================================

    private void openConditionList(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            int page
    ) {
        EditorHolder holder = createHolder(
                Screen.CONDITION_EDITOR,
                dialogueId,
                nodeId,
                answerId,
                page,
                "Умови доступності"
        );

        DialogueCore.Node node = getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        DialogueCore.Answer answer =
                answerId == null ? null : node.getAnswer(answerId);

        if (answer == null) {
            openNodeMenu(player, dialogueId, nodeId);
            return;
        }

        List<DialogueCore.Condition> conditions =
                new ArrayList<>(answer.getConditions());

        int maxPage = Math.max(
                0,
                (conditions.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        holder = createHolder(
                Screen.CONDITION_EDITOR,
                dialogueId,
                nodeId,
                answerId,
                page,
                "Умови • " + answerId
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                conditions.size()
        );

        for (int i = start; i < end; i++) {
            DialogueCore.Condition condition = conditions.get(i);

            holder.entries.add(condition.getId());

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            Material.REDSTONE,
                            condition.getType().name(),
                            NamedTextColor.YELLOW,
                            "Оператор: " + condition.getOperator(),
                            "Значення: " + condition.getValue(),
                            "ЛКМ — редагувати",
                            "Shift + ПКМ — видалити"
                    )
            );
        }

        holder.inventory.setItem(
                45,
                item(
                        Material.REPEATER,
                        "Логіка умов",
                        NamedTextColor.YELLOW,
                        "Перемкнути AND / OR"
                )
        );

        holder.inventory.setItem(
                SLOT_CREATE,
                item(
                        Material.EMERALD,
                        "Додати умову",
                        NamedTextColor.GREEN
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до відповіді",
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }

    private void openConditionMenu(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String conditionId
    ) {
        EditorHolder holder = createHolder(
                Screen.CONDITION,
                dialogueId,
                nodeId,
                answerId,
                0,
                "Редагування умови"
        );
        holder.extraId = conditionId;

        holder.inventory.setItem(
                10,
                item(
                        Material.COMPARATOR,
                        "Тип умови",
                        NamedTextColor.YELLOW,
                        "Вибрати тип перевірки"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.REPEATER,
                        "Оператор",
                        NamedTextColor.YELLOW,
                        "Рівно, більше, менше, містить тощо"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.PAPER,
                        "Значення",
                        NamedTextColor.YELLOW,
                        "Задати значення умови через чат"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.LIME_DYE,
                        "Зберегти умову",
                        NamedTextColor.GREEN
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до умов",
                        NamedTextColor.YELLOW
                )
        );

        open(player, holder);
    }

    // ============================================================
    // REWARD EDITOR
    // ============================================================
    private void openRewardList(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            int page
    ) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        List<DialogueCore.Reward> rewards;
        String menuTitle;
        String backTitle;

        if (answerId != null) {
            DialogueCore.Answer answer = node.getAnswer(answerId);

            if (answer == null) {
                openNodeMenu(player, dialogueId, nodeId);
                return;
            }

            rewards = new ArrayList<>(answer.getRewards());
            menuTitle = "Нагороди • " + answerId;
            backTitle = "Назад до відповіді";
        } else {
            rewards = new ArrayList<>(node.getRewards());
            menuTitle = "Нагороди гілки • " + nodeId;
            backTitle = "Назад до гілки";
        }

        int maxPage = Math.max(
                0,
                (rewards.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.REWARD_EDITOR,
                dialogueId,
                nodeId,
                answerId,
                page,
                menuTitle
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                rewards.size()
        );

        for (int i = start; i < end; i++) {
            DialogueCore.Reward reward = rewards.get(i);

            holder.entries.add(reward.getId());

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            Material.CHEST,
                            reward.getType().name(),
                            NamedTextColor.YELLOW,
                            "ID: " + reward.getId(),
                            "Значення: " + reward.getValue(),
                            "Кількість: " + reward.getAmount(),
                            "ЛКМ — редагувати",
                            "Shift + ПКМ — видалити"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_CREATE,
                item(
                        Material.EMERALD,
                        "Додати нагороду",
                        NamedTextColor.GREEN
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        backTitle,
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }
    
    private void openRewardMenu(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);

        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        List<DialogueCore.Reward> rewards;

        if (answerId != null) {
            DialogueCore.Answer answer = node.getAnswer(answerId);

            if (answer == null) {
                openNodeMenu(player, dialogueId, nodeId);
                return;
            }

            rewards = answer.getRewards();
        } else {
            rewards = node.getRewards();
        }

        DialogueCore.Reward reward = null;

        for (DialogueCore.Reward candidate : rewards) {
            if (candidate.getId().equals(rewardId)) {
                reward = candidate;
                break;
            }
        }

        if (reward == null) {
            if (answerId == null) {
                openBranchRewards(player, dialogueId, nodeId);
            } else {
                openRewardList(
                        player,
                        dialogueId,
                        nodeId,
                        answerId,
                        0
                );
            }
            return;
        }

        EditorHolder holder = createHolder(
                Screen.REWARD,
                dialogueId,
                nodeId,
                answerId,
                0,
                "Редагування нагороди"
        );

        holder.extraId = rewardId;

        holder.inventory.setItem(
                10,
                item(
                        Material.CHEST,
                        "Тип нагороди",
                        NamedTextColor.YELLOW,
                        "Поточний тип: " + reward.getType().name(),
                        "Натисніть, щоб змінити"
                )
        );

        holder.inventory.setItem(
                12,
                item(
                        Material.PAPER,
                        "Значення нагороди",
                        NamedTextColor.YELLOW,
                        "Поточне значення: " + reward.getValue(),
                        "Натисніть, щоб змінити"
                )
        );

        holder.inventory.setItem(
                14,
                item(
                        Material.ITEM_FRAME,
                        "Предмет із руки",
                        NamedTextColor.YELLOW,
                        "Скопіювати предмет з усіма властивостями"
                )
        );

        holder.inventory.setItem(
                15,
                item(
                        Material.COMPARATOR,
                        "Кількість предметів",
                        NamedTextColor.YELLOW,
                        "Поточна кількість: " + reward.getAmount(),
                        "Натисніть, щоб змінити"
                )
        );

        holder.inventory.setItem(
                16,
                item(
                        Material.LIME_DYE,
                        "Зберегти нагороду",
                        NamedTextColor.GREEN
                )
        );

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад до нагород",
                        NamedTextColor.YELLOW
                )
        );

        holder.inventory.setItem(
                50,
                item(
                        Material.BARRIER,
                        "Видалити нагороду",
                        NamedTextColor.RED,
                        "Видалити цю нагороду"
                )
        );

        open(player, holder);
    }

    // ============================================================
    // SOUND PICKER
    // ============================================================

    private void openSoundPicker(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            int page
    ) {
        List<Sound> sounds = new ArrayList<>(
                List.of(Sound.values())
        );

        sounds.sort(Comparator.comparing(Enum::name));

        int maxPage = Math.max(
                0,
                (sounds.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.SOUND_PICKER,
                dialogueId,
                nodeId,
                answerId,
                page,
                "Вибір звуку"
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                sounds.size()
        );

        for (int i = start; i < end; i++) {
            Sound sound = sounds.get(i);

            holder.entries.add(sound.name());

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            Material.NOTE_BLOCK,
                            sound.name(),
                            NamedTextColor.YELLOW,
                            "ЛКМ — прослухати",
                            "ПКМ — вибрати"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад",
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }

    // ============================================================
    // BRANCH TARGET PICKER
    // ============================================================

    private void openBranchPicker(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            int page
    ) {
        DialogueCore.Dialogue dialogue =
                getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player);
            return;
        }

        List<DialogueCore.Node> nodes =
                new ArrayList<>(dialogue.getNodes());

        nodes.sort(Comparator.comparing(
                DialogueCore.Node::getId,
                String.CASE_INSENSITIVE_ORDER
        ));

        int maxPage = Math.max(
                0,
                (nodes.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = createHolder(
                Screen.BRANCH_PICKER,
                dialogueId,
                nodeId,
                answerId,
                page,
                "Вибір цільової гілки"
        );

        int[] slots = createEntrySlots();

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                nodes.size()
        );

        for (int i = start; i < end; i++) {
            DialogueCore.Node node = nodes.get(i);

            holder.entries.add(node.getId());

            holder.inventory.setItem(
                    slots[i - start],
                    item(
                            node.getId().equalsIgnoreCase("start")
                                    ? Material.ENCHANTED_BOOK
                                    : Material.PAPER,
                            node.getId(),
                            NamedTextColor.YELLOW,
                            "ЛКМ — вибрати ціль"
                    )
            );
        }

        holder.inventory.setItem(
                SLOT_BACK,
                item(
                        Material.ARROW,
                        "Назад",
                        NamedTextColor.YELLOW
                )
        );

        if (page > 0) {
            holder.inventory.setItem(
                    SLOT_PREVIOUS,
                    item(
                            Material.ARROW,
                            "Попередня сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        if (page < maxPage) {
            holder.inventory.setItem(
                    SLOT_NEXT,
                    item(
                            Material.ARROW,
                            "Наступна сторінка",
                            NamedTextColor.YELLOW
                    )
            );
        }

        open(player, holder);
    }
    // ============================================================
    // CHAT INPUT
    // ============================================================

    private void requestChatInput(
            Player player,
            String prompt,
            Consumer<String> handler
    ) {
        requestChatInput(player, prompt, handler, () -> {});
    }

    private void requestChatInput(
            Player player,
            String prompt,
            Consumer<String> handler,
            Runnable cancel
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(handler, cancel)
        );

        player.closeInventory();

        player.sendMessage(
                PREFIX.append(Component.text(
                        " " + prompt,
                        NamedTextColor.YELLOW
                ))
        );

        player.sendMessage(
                Component.text(
                        "Введи cancel для скасування.",
                        NamedTextColor.GRAY
                )
        );
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        ChatPrompt prompt = chatPrompts.get(
                player.getUniqueId()
        );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String message = PlainTextComponentSerializer
                .plainText()
                .serialize(event.message())
                .trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            ChatPrompt current = chatPrompts.remove(
                    player.getUniqueId()
            );

            if (current == null || !player.isOnline()) {
                return;
            }

            if (message.equalsIgnoreCase("cancel")) {
                current.cancel().run();

                player.sendMessage(
                        PREFIX.append(Component.text(
                                " Введення скасовано.",
                                NamedTextColor.RED
                        ))
                );

                return;
            }

            if (message.isBlank()) {
                player.sendMessage(
                        PREFIX.append(Component.text(
                                " Порожнє значення не допускається.",
                                NamedTextColor.RED
                        ))
                );

                chatPrompts.put(
                        player.getUniqueId(),
                        current
                );

                return;
            }

            current.handler().accept(message);
        });
    }

    private void clearChatPrompt(Player player) {
        if (player != null) {
            chatPrompts.remove(player.getUniqueId());
        }
    }

    // ============================================================
    // INVENTORY EVENTS
    // ============================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof EditorHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != top) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        handleClick(player, holder, slot, event.getClick());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Chat prompts must remain active after closing the editor.
    }

    // ============================================================
    // CLICK ROUTER
    // ============================================================

    private void handleClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        switch (holder.screen) {
            case MAIN -> handleMainClick(
                    player, holder, slot, click
            );

            case DIALOGUE -> handleDialogueClick(
                    player, holder, slot, click
            );

            case NODES -> handleNodeListClick(
                    player, holder, slot, click
            );

            case NODE -> handleNodeClick(
                    player, holder, slot, click
            );

            case ANSWER -> handleAnswerClick(
                    player, holder, slot, click
            );

            case SETTINGS -> handleSettingsClick(
                    player, holder, slot, click
            );

            case SOUND_PICKER -> handleSoundClick(
                    player, holder, slot, click
            );

            case CONDITION_EDITOR -> handleConditionClick(
                    player, holder, slot, click
            );

            case REWARD_EDITOR -> handleRewardClick(
                    player, holder, slot, click
            );

            case BRANCH_PICKER -> handleBranchPickerClick(
                    player, holder, slot, click
            );

            default -> handleSpecialScreenClick(
                    player, holder, slot, click
            );
        }
    }

    private boolean isLeft(ClickType click) {
        return click == ClickType.LEFT;
    }

    private boolean isRight(ClickType click) {
        return click == ClickType.RIGHT;
    }

    private boolean isShiftLeft(ClickType click) {
        return click == ClickType.SHIFT_LEFT;
    }

    private boolean isShiftRight(ClickType click) {
        return click == ClickType.SHIFT_RIGHT;
    }

    private boolean isPlainClick(ClickType click) {
        return isLeft(click) || isRight(click);
    }

    private String entryAt(
            EditorHolder holder,
            int slot
    ) {
        int[] slots = createEntrySlots();

        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i < holder.entries.size()
                        ? holder.entries.get(i)
                        : null;
            }
        }

        return null;
    }

    private void playClickSound(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.UI_BUTTON_CLICK,
                0.5f,
                1.2f
        );
    }

    private void handleMainClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_CREATE && isLeft(click)) {
            requestChatInput(
                    player,
                    "Введи назву нового діалогу:",
                    name -> createDialogue(player, name)
            );
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openMainMenu(player, holder.page - 1);
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openMainMenu(player, holder.page + 1);
            return;
        }

        String dialogueId = entryAt(holder, slot);

        if (dialogueId == null) {
            return;
        }

        if (isLeft(click)) {
            openDialogueMenu(player, dialogueId);
        } else if (isRight(click)) {
            requestRenameDialogue(player, dialogueId);
        } else if (isShiftRight(click)) {
            confirmDeleteDialogue(player, dialogueId);
        }
    }

    private void handleDialogueClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            openMainMenu(player);
            return;
        }

        if (!isLeft(click)) {
            return;
        }

        switch (slot) {
            case 10 -> openNodeList(
                    player, holder.dialogueId, 0
            );

            case 12 -> openDialogueSettings(
                    player, holder.dialogueId
            );

            case 14 -> requestRenameDialogue(
                    player, holder.dialogueId
            );

            case 16 -> confirmDeleteDialogue(
                    player, holder.dialogueId
            );

            default -> {
            }
        }
    }

    private void handleNodeListClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            openDialogueMenu(player, holder.dialogueId);
            return;
        }

        if (slot == SLOT_CREATE && isLeft(click)) {
            requestChatInput(
                    player,
                    "Введи назву нової гілки:",
                    name -> createBranch(
                            player,
                            holder.dialogueId,
                            name
                    )
            );
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openNodeList(
                    player,
                    holder.dialogueId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openNodeList(
                    player,
                    holder.dialogueId,
                    holder.page + 1
            );
            return;
        }

        String nodeId = entryAt(holder, slot);

        if (nodeId == null) {
            return;
        }

        if (isLeft(click)) {
            openNodeMenu(
                    player, holder.dialogueId, nodeId
            );
        } else if (isRight(click)) {
            requestRenameBranch(
                    player, holder.dialogueId, nodeId
            );
        } else if (isShiftRight(click)
                && !nodeId.equalsIgnoreCase("start")) {
            confirmDeleteBranch(
                    player, holder.dialogueId, nodeId
            );
        }
    }
    // ============================================================
    // BRANCH CLICK HANDLER
    // ============================================================

    private void handleNodeClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            openNodeList(
                    player, holder.dialogueId, 0
            );
            return;
        }

        if (!isLeft(click)) {
            return;
        }

        switch (slot) {
            case 10 -> requestBranchText(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            case 12 -> requestBranchDelay(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            case 14 -> openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    0
            );

            case 16 -> openBranchSettings(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            case 28 -> requestRenameBranch(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            case 30 -> {
                if (!holder.nodeId.equalsIgnoreCase("start")) {
                    confirmDeleteBranch(
                            player,
                            holder.dialogueId,
                            holder.nodeId
                    );
                }
            }

            default -> {
            }
        }
    }

    // ============================================================
    // ANSWER CLICK HANDLER
    // ============================================================

    private void handleAnswerClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            if (holder.answerId == null) {
                openNodeMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            } else {
                openAnswerList(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        0
                );
            }
            return;
        }

        if (holder.answerId == null) {
            handleAnswerListClick(
                    player, holder, slot, click
            );
            return;
        }

        handleAnswerEditorClick(
                player, holder, slot, click
        );
    }

    private void handleAnswerListClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_CREATE && isLeft(click)) {
            createAnswer(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page + 1
            );
            return;
        }

        String answerId = entryAt(holder, slot);

        if (answerId == null) {
            return;
        }

        DialogueCore.Node node = getNode(
                holder.dialogueId,
                holder.nodeId
        );

        if (node == null) {
            return;
        }

        DialogueCore.Answer answer =
                node.getAnswer(answerId);

        if (answer == null) {
            return;
        }

        if (isLeft(click)) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    answerId
            );
        } else if (isRight(click)) {
            answer.setEnabled(!answer.isEnabled());
            save(holder.dialogueId);

            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page
            );
        } else if (isShiftRight(click)) {
            confirmDeleteAnswer(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    answerId
            );
        }
    }

    private void handleAnswerEditorClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (!isLeft(click)) {
            return;
        }

        switch (slot) {
            case 10 -> requestAnswerText(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 12 -> openBranchPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    0
            );

            case 14 -> openConditionList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    0
            );

            case 16 -> openCommandSettings(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 28 -> openRewardList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    0
            );

            case 30 -> openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    0
            );

            case 32 -> {
                DialogueCore.Node node = getNode(
                        holder.dialogueId,
                        holder.nodeId
                );

                if (node == null) {
                    return;
                }

                DialogueCore.Answer answer =
                        node.getAnswer(holder.answerId);

                if (answer == null) {
                    return;
                }

                answer.setEnabled(!answer.isEnabled());
                save(holder.dialogueId);

                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            }

            case 50 -> confirmDeleteAnswer(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            default -> {
            }
        }
    }

    // ============================================================
    // SETTINGS CLICK HANDLER
    // ============================================================

    private void handleSettingsClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            if (holder.nodeId == null) {
                openDialogueMenu(player, holder.dialogueId);
            } else {
                openNodeMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (!isLeft(click)) {
            return;
        }

        if (holder.nodeId == null) {
            handleGlobalSettingsClick(
                    player, holder, slot
            );
        } else {
            handleBranchSettingsClick(
                    player, holder, slot
            );
        }
    }

    private void handleGlobalSettingsClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        switch (slot) {
            case 10 -> toggleGlobalSetting(
                    player, holder.dialogueId, "hide-players"
            );

            case 11 -> toggleGlobalSetting(
                    player, holder.dialogueId, "one-time"
            );

            case 12 -> toggleGlobalSetting(
                    player, holder.dialogueId, "see-through-blocks"
            );

            case 13 -> captureTextLocation(
                    player, holder.dialogueId, null
            );

            case 14 -> captureCameraLocation(
                    player, holder.dialogueId, null
            );

            case 15 -> toggleGlobalSetting(
                    player, holder.dialogueId, "smooth-camera"
            );

            case 16 -> openSoundPicker(
                    player,
                    holder.dialogueId,
                    null,
                    null,
                    0
            );

            case 19 -> requestGlobalNumber(
                    player, holder.dialogueId,
                    "typing-speed", "Швидкість друку"
            );

            case 20 -> toggleGlobalSetting(
                    player, holder.dialogueId, "character-printing"
            );

            case 21 -> openSoundParameters(
                    player, holder.dialogueId, null, null
            );

            case 22 -> requestGlobalNumber(
                    player, holder.dialogueId,
                    "text-scale", "Масштаб тексту"
            );

            case 23 -> requestGlobalNumber(
                    player, holder.dialogueId,
                    "window-width", "Ширина вікна"
            );

            case 24 -> requestGlobalNumber(
                    player, holder.dialogueId,
                    "background-alpha", "Прозорість фону"
            );

            case 25 -> toggleGlobalSetting(
                    player, holder.dialogueId, "invisibility"
            );

            case 29 -> toggleGlobalSetting(
                    player, holder.dialogueId, "return-to-start"
            );

            default -> {
            }
        }
    }

    private void handleBranchSettingsClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        switch (slot) {
            case 10 -> openColorPicker(
                    player, holder.dialogueId,
                    holder.nodeId, "text-color"
            );

            case 11 -> openColorPicker(
                    player, holder.dialogueId,
                    holder.nodeId, "background-color"
            );

            case 12 -> requestBranchNumber(
                    player, holder.dialogueId,
                    holder.nodeId, "text-scale", "Масштаб тексту"
            );

            case 13 -> captureTextLocation(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            case 14 -> captureCameraLocation(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            case 15 -> requestBranchNumber(
                    player, holder.dialogueId,
                    holder.nodeId, "typing-speed", "Швидкість друку"
            );

            case 16 -> openSoundPicker(
                    player, holder.dialogueId,
                    holder.nodeId, null, 0
            );

            case 19 -> openBranchCommands(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            case 20 -> openBranchConditions(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            case 21 -> openBranchRewards(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            case 23 -> openInheritanceReset(
                    player, holder.dialogueId,
                    holder.nodeId
            );

            default -> {
            }
        }
    }
    // ============================================================
    // SOUND PICKER CLICK HANDLER
    // ============================================================
        private void handleSoundClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else if (holder.nodeId == null) {
                openDialogueSettings(
                        player,
                        holder.dialogueId
                );
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page + 1
            );
            return;
        }

        String soundName = entryAt(holder, slot);

        if (soundName == null) {
            return;
        }

        Sound sound;

        try {
            sound = Sound.valueOf(soundName);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Цей звук більше недоступний.",
                            NamedTextColor.RED
                    ))
            );
            return;
        }

        if (isLeft(click)) {
            player.playSound(
                    player.getLocation(),
                    sound,
                    1.0f,
                    1.0f
            );
            return;
        }

        if (!isRight(click)) {
            return;
        }

        if (holder.answerId != null) {
            DialogueCore.Node node = getNode(
                    holder.dialogueId,
                    holder.nodeId
            );

            if (node == null) {
                return;
            }

            DialogueCore.Answer answer =
                    node.getAnswer(holder.answerId);

            if (answer == null) {
                return;
            }

            answer.setSound(sound.name());
        } else if (holder.nodeId == null) {
            core.setGlobalSetting(
                    holder.dialogueId,
                    "typing-sound",
                    sound.name()
            );
        } else {
            core.setBranchSetting(
                    holder.dialogueId,
                    holder.nodeId,
                    "typing-sound",
                    sound.name()
            );
        }

        save(holder.dialogueId);

        player.sendMessage(
                PREFIX.append(Component.text(
                        " Звук збережено: " + sound.name(),
                        NamedTextColor.GREEN
                ))
        );

        if (holder.answerId != null) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
        } else if (holder.nodeId == null) {
            openDialogueSettings(
                    player,
                    holder.dialogueId
            );
        } else {
            openBranchSettings(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
        }
    }
    // ============================================================
    // CONDITION CLICK HANDLER
    // ============================================================

    private void handleConditionClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            if (holder.answerId != null) {
                openConditionList(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        0
                );
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (holder.answerId == null) {
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openConditionList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openConditionList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page + 1
            );
            return;
        }

        if (slot == SLOT_CREATE && isLeft(click)) {
            createCondition(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        if (slot == 45 && isLeft(click)) {
            toggleConditionLogic(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        String conditionId = entryAt(holder, slot);

        if (conditionId == null) {
            return;
        }

        if (isLeft(click)) {
            openConditionMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    conditionId
            );
        } else if (isShiftRight(click)) {
            deleteCondition(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    conditionId
            );
        }
    }

    // ============================================================
    // REWARD CLICK HANDLER
    // ============================================================

    private void handleRewardClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openRewardList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openRewardList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page + 1
            );
            return;
        }

        if (slot == SLOT_CREATE && isLeft(click)) {
            createReward(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        String rewardId = entryAt(holder, slot);

        if (rewardId == null) {
            return;
        }

        if (isLeft(click)) {
            openRewardMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    rewardId
            );
        } else if (isShiftRight(click)) {
            deleteReward(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    rewardId
            );
        }
    }

    // ============================================================
    // BRANCH PICKER CLICK HANDLER
    // ============================================================

    private void handleBranchPickerClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK && isLeft(click)) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        if (slot == SLOT_PREVIOUS && isLeft(click)) {
            openBranchPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page - 1
            );
            return;
        }

        if (slot == SLOT_NEXT && isLeft(click)) {
            openBranchPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page + 1
            );
            return;
        }

        if (!isLeft(click)) {
            return;
        }

        String targetNodeId = entryAt(holder, slot);

        if (targetNodeId == null) {
            return;
        }

        DialogueCore.Node node = getNode(
                holder.dialogueId,
                holder.nodeId
        );

        if (node == null) {
            return;
        }

        DialogueCore.Answer answer =
                node.getAnswer(holder.answerId);

        if (answer == null) {
            return;
        }

        answer.setTargetNode(targetNodeId);
        save(holder.dialogueId);

        openAnswerMenu(
                player,
                holder.dialogueId,
                holder.nodeId,
                holder.answerId
        );
    }

    // ============================================================
    // DIALOGUE CREATION
    // ============================================================

    private void createDialogue(
            Player player,
            String name
    ) {
        String id = normalizeId(name);

        if (id.isBlank()) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Некоректна назва.",
                            NamedTextColor.RED
                    ))
            );
            openMainMenu(player);
            return;
        }

        if (core.getDialogue(id) != null) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Діалог із такою назвою вже існує.",
                            NamedTextColor.RED
                    ))
            );
            openMainMenu(player);
            return;
        }

        DialogueCore.Dialogue dialogue =
                core.createDialogue(id);

        if (dialogue == null) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Не вдалося створити діалог.",
                            NamedTextColor.RED
                    ))
            );
            openMainMenu(player);
            return;
        }

        save(id);

        player.sendMessage(
                PREFIX.append(Component.text(
                        " Діалог створено: " + id,
                        NamedTextColor.GREEN
                ))
        );

        openDialogueMenu(player, id);
    }

    private String normalizeId(String input) {
        return input
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_\\-]", "");
    }

    // ============================================================
    // BRANCH CREATION
    // ============================================================

    private void createBranch(
            Player player,
            String dialogueId,
            String name
    ) {
        String id = normalizeId(name);

        DialogueCore.Dialogue dialogue =
                getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player);
            return;
        }

        if (id.isBlank() || id.equalsIgnoreCase("start")) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Некоректна назва гілки.",
                            NamedTextColor.RED
                    ))
            );
            openNodeList(player, dialogueId, 0);
            return;
        }

        if (dialogue.getNode(id) != null) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Така гілка вже існує.",
                            NamedTextColor.RED
                    ))
            );
            openNodeList(player, dialogueId, 0);
            return;
        }

        core.createBranch(dialogueId, id);
        save(dialogueId);

        player.sendMessage(
                PREFIX.append(Component.text(
                        " Гілку створено: " + id,
                        NamedTextColor.GREEN
                ))
        );

        openNodeMenu(player, dialogueId, id);
    }

    // ============================================================
    // ANSWER CREATION
    // ============================================================

    private void createAnswer(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        String answerId = core.createAnswer(
                dialogueId,
                nodeId,
                "Нова відповідь",
                nodeId
        );

        if (answerId == null) {
            player.sendMessage(
                    PREFIX.append(Component.text(
                            " Не вдалося створити відповідь.",
                            NamedTextColor.RED
                    ))
            );
            openAnswerList(player, dialogueId, nodeId, 0);
            return;
        }

        save(dialogueId);

        openAnswerMenu(
                player,
                dialogueId,
                nodeId,
                answerId
        );
    }
    private void requestRenameDialogue(Player player, String dialogueId) {
        requestChatInput(player, "Введіть нову назву діалогу:", input -> {
            String newId = normalizeId(input);
            if (newId.isBlank()) {
                player.sendMessage(Component.text("Назва не може бути порожньою."));
                openDialogueMenu(player, dialogueId);
                return;
            }

            if (core.getDialogue(newId) != null && !newId.equals(dialogueId)) {
                player.sendMessage(Component.text("Діалог із такою назвою вже існує."));
                openDialogueMenu(player, dialogueId);
                return;
            }

            core.renameDialogue(dialogueId, newId);
            player.sendMessage(Component.text("Діалог перейменовано."));
            openDialogueMenu(player, newId);
        });
    }

    private void confirmDeleteDialogue(Player player, String dialogueId) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);
        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        Inventory inventory = createHolder(
                player,
                Screen.CONFIRM_DELETE,
                dialogueId,
                null,
                null,
                27,
                "§4Видалити діалог?"
        );

        inventory.setItem(11, item(
                Material.GREEN_WOOL,
                "§aПідтвердити",
                "§7Видалити діалог і всі його дані"
        ));

        inventory.setItem(15, item(
                Material.RED_WOOL,
                "§cСкасувати",
                "§7Повернутися до меню діалогу"
        ));

        player.openInventory(inventory);
    }

    private void requestRenameBranch(Player player, String dialogueId, String nodeId) {
        requestChatInput(player, "Введіть нову назву гілки:", input -> {
            String newId = normalizeId(input);

            if (newId.isBlank()) {
                player.sendMessage(Component.text("Назва не може бути порожньою."));
                openNodeMenu(player, dialogueId, nodeId);
                return;
            }

            DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);
            if (dialogue == null) {
                openMainMenu(player, 0);
                return;
            }

            if (dialogue.getNode(newId) != null && !newId.equals(nodeId)) {
                player.sendMessage(Component.text("Гілка з такою назвою вже існує."));
                openNodeMenu(player, dialogueId, nodeId);
                return;
            }

            core.renameBranch(dialogueId, nodeId, newId);
            player.sendMessage(Component.text("Гілку перейменовано."));
            openNodeMenu(player, dialogueId, newId);
        });
    }

    private void confirmDeleteBranch(Player player, String dialogueId, String nodeId) {
        Inventory inventory = createHolder(
                player,
                Screen.CONFIRM_DELETE,
                dialogueId,
                nodeId,
                null,
                27,
                "§4Видалити гілку?"
        );

        inventory.setItem(11, item(
                Material.GREEN_WOOL,
                "§aПідтвердити",
                "§7Видалити гілку та її відповіді"
        ));

        inventory.setItem(15, item(
                Material.RED_WOOL,
                "§cСкасувати",
                "§7Повернутися до редактора гілки"
        ));

        player.openInventory(inventory);
    }

    private void requestBranchText(Player player, String dialogueId, String nodeId) {
        requestChatInput(player, "Введіть текст гілки:", input -> {
            core.setBranchText(dialogueId, nodeId, input);
            player.sendMessage(Component.text("Текст гілки збережено."));
            openNodeMenu(player, dialogueId, nodeId);
        });
    }

    private void requestBranchDelay(Player player, String dialogueId, String nodeId) {
        requestChatInput(player, "Введіть затримку показу тексту в тіках:", input -> {
            try {
                int ticks = Integer.parseInt(input.trim());

                if (ticks < 0) {
                    throw new NumberFormatException();
                }

                core.setBranchSetting(
                        dialogueId,
                        nodeId,
                        "delay",
                        String.valueOf(ticks)
                );

                player.sendMessage(Component.text("Затримку збережено."));
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Введіть ціле число від 0."));
            }

            openNodeMenu(player, dialogueId, nodeId);
        });
    }

    private void confirmDeleteAnswer(Player player, String dialogueId, String nodeId, String answerId) {
        Inventory inventory = createHolder(
                player,
                Screen.CONFIRM_DELETE,
                dialogueId,
                nodeId,
                answerId,
                27,
                "§4Видалити відповідь?"
        );

        inventory.setItem(11, item(
                Material.GREEN_WOOL,
                "§aПідтвердити",
                "§7Видалити цю відповідь"
        ));

        inventory.setItem(15, item(
                Material.RED_WOOL,
                "§cСкасувати",
                "§7Повернутися до відповіді"
        ));

        player.openInventory(inventory);
    }

    private void openCommandSettings(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        DialogueCore.Answer answer = core.getAnswer(dialogueId, nodeId, answerId);
        if (answer == null) {
            openAnswerList(player, dialogueId, nodeId, 0);
            return;
        }

        Inventory inventory = createHolder(
                player,
                Screen.COMMANDS,
                dialogueId,
                nodeId,
                answerId,
                54,
                "§8Команди відповіді"
        );

        inventory.setItem(10, item(
                Material.WRITABLE_BOOK,
                "§eДодати команду",
                "§7Введіть команду в чат",
                "§7Плейсхолдери: {player}, {uuid}"
        ));

        inventory.setItem(12, item(
                Material.PAPER,
                "§fКоманди відповіді",
                "§7Кількість: §e" + answer.getCommands().size()
        ));

        inventory.setItem(14, item(
                Material.COMMAND_BLOCK,
                "§bВиконання команд",
                "§7Команди виконуються від імені консолі"
        ));

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7До редактора відповіді"
        ));

        player.openInventory(inventory);
    }

    private void openBranchCommands(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);
        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        Inventory inventory = createHolder(
                player,
                Screen.COMMANDS,
                dialogueId,
                nodeId,
                null,
                54,
                "§8Команди гілки"
        );

        inventory.setItem(10, item(
                Material.WRITABLE_BOOK,
                "§eДодати команду",
                "§7Введіть команду в чат"
        ));

        inventory.setItem(12, item(
                Material.PAPER,
                "§fКоманди гілки",
                "§7Кількість: §e" + node.getCommands().size()
        ));

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7До редактора гілки"
        ));

        player.openInventory(inventory);
    }

    private void addAnswerCommand(Player player, String dialogueId, String nodeId, String answerId) {
        requestChatInput(player, "Введіть команду без початкового /:", input -> {
            String command = input.trim();

            if (command.isBlank()) {
                player.sendMessage(Component.text("Команда не може бути порожньою."));
                openCommandSettings(player, dialogueId, nodeId, answerId);
                return;
            }

            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            core.addAnswerCommand(dialogueId, nodeId, answerId, command);
            player.sendMessage(Component.text("Команду додано."));
            openCommandSettings(player, dialogueId, nodeId, answerId);
        });
    }

    private void addBranchCommand(Player player, String dialogueId, String nodeId) {
        requestChatInput(player, "Введіть команду без початкового /:", input -> {
            String command = input.trim();

            if (command.isBlank()) {
                player.sendMessage(Component.text("Команда не може бути порожньою."));
                openBranchCommands(player, dialogueId, nodeId);
                return;
            }

            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            core.addBranchCommand(dialogueId, nodeId, command);
            player.sendMessage(Component.text("Команду додано."));
            openBranchCommands(player, dialogueId, nodeId);
        });
    }
    private void toggleGlobalSetting(Player player, String dialogueId, String key) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);
        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        boolean current = core.getGlobalBoolean(dialogueId, key);
        core.setGlobalSetting(dialogueId, key, String.valueOf(!current));

        save(dialogueId);
        openDialogueSettings(player, dialogueId);
    }

    private void captureTextLocation(Player player, String dialogueId, String nodeId) {
        Location location = player.getEyeLocation();

        String value = location.getWorld().getName()
                + "," + location.getX()
                + "," + location.getY()
                + "," + location.getZ()
                + "," + location.getYaw()
                + "," + location.getPitch();

        if (nodeId == null) {
            core.setGlobalSetting(dialogueId, "text-location", value);
            save(dialogueId);
            player.sendMessage(Component.text("Глобальну позицію тексту збережено."));
            openDialogueSettings(player, dialogueId);
        } else {
            core.setBranchSetting(dialogueId, nodeId, "text-location", value);
            save(dialogueId);
            player.sendMessage(Component.text("Позицію тексту гілки збережено."));
            openBranchSettings(player, dialogueId, nodeId);
        }
    }

    private void captureCameraLocation(Player player, String dialogueId, String nodeId) {
        Location location = player.getLocation();

        String value = location.getWorld().getName()
                + "," + location.getX()
                + "," + location.getY()
                + "," + location.getZ()
                + "," + location.getYaw()
                + "," + location.getPitch();

        if (nodeId == null) {
            core.setGlobalSetting(dialogueId, "camera-location", value);
            save(dialogueId);
            player.sendMessage(Component.text("Глобальну позицію камери збережено."));
            openDialogueSettings(player, dialogueId);
        } else {
            core.setBranchSetting(dialogueId, nodeId, "camera-location", value);
            save(dialogueId);
            player.sendMessage(Component.text("Позицію камери гілки збережено."));
            openBranchSettings(player, dialogueId, nodeId);
        }
    }

    private void requestGlobalNumber(Player player, String dialogueId, String key, String title) {
        requestChatInput(player, "Введіть значення для параметра «" + title + "»:", input -> {
            try {
                double value = Double.parseDouble(input.trim());

                if (!Double.isFinite(value) || value < 0) {
                    throw new NumberFormatException();
                }

                core.setGlobalSetting(dialogueId, key, String.valueOf(value));
                save(dialogueId);
                player.sendMessage(Component.text("Параметр збережено."));
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Потрібно ввести невід'ємне число."));
            }

            openDialogueSettings(player, dialogueId);
        });
    }

    private void requestBranchNumber(
            Player player,
            String dialogueId,
            String nodeId,
            String key,
            String title
    ) {
        requestChatInput(player, "Введіть значення для параметра «" + title + "»:", input -> {
            try {
                double value = Double.parseDouble(input.trim());

                if (!Double.isFinite(value) || value < 0) {
                    throw new NumberFormatException();
                }

                core.setBranchSetting(
                        dialogueId,
                        nodeId,
                        key,
                        String.valueOf(value)
                );

                save(dialogueId);
                player.sendMessage(Component.text("Параметр гілки збережено."));
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Потрібно ввести невід'ємне число."));
            }

            openBranchSettings(player, dialogueId, nodeId);
        });
    }

    private void openSoundParameters(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        Inventory inventory = createHolder(
                player,
                Screen.SOUND_PARAMETERS,
                dialogueId,
                nodeId,
                answerId,
                27,
                "§8Параметри звуку"
        );

        inventory.setItem(11, item(
                Material.NOTE_BLOCK,
                "§eГучність",
                "§7Натисніть, щоб змінити"
        ));

        inventory.setItem(15, item(
                Material.JUKEBOX,
                "§eВисота тону",
                "§7Натисніть, щоб змінити"
        ));

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7До налаштувань звуку"
        ));

        player.openInventory(inventory);
    }

    private void toggleBranchSetting(
            Player player,
            String dialogueId,
            String nodeId,
            String key
    ) {
        String current = core.getBranchSetting(dialogueId, nodeId, key);
        boolean enabled = Boolean.parseBoolean(current);

        core.setBranchSetting(
                dialogueId,
                nodeId,
                key,
                String.valueOf(!enabled)
        );

        save(dialogueId);
        openBranchSettings(player, dialogueId, nodeId);
    }

    private void openColorPicker(
            Player player,
            String dialogueId,
            String nodeId,
            String key
    ) {
        Inventory inventory = createHolder(
                player,
                Screen.COLOR_PICKER,
                dialogueId,
                nodeId,
                key,
                54,
                "§8Вибір кольору"
        );

        Material[] materials = {
                Material.WHITE_WOOL,
                Material.ORANGE_WOOL,
                Material.MAGENTA_WOOL,
                Material.LIGHT_BLUE_WOOL,
                Material.YELLOW_WOOL,
                Material.LIME_WOOL,
                Material.PINK_WOOL,
                Material.GRAY_WOOL,
                Material.LIGHT_GRAY_WOOL,
                Material.CYAN_WOOL,
                Material.PURPLE_WOOL,
                Material.BLUE_WOOL,
                Material.BROWN_WOOL,
                Material.GREEN_WOOL,
                Material.RED_WOOL,
                Material.BLACK_WOOL
        };

        String[] colors = {
                "#FFFFFF",
                "#FFAA00",
                "#FF55FF",
                "#55FFFF",
                "#FFFF55",
                "#55FF55",
                "#FFAAAA",
                "#555555",
                "#AAAAAA",
                "#00AAAA",
                "#AA00AA",
                "#5555FF",
                "#AA5500",
                "#00AA00",
                "#FF5555",
                "#000000"
        };

        for (int i = 0; i < materials.length; i++) {
            inventory.setItem(i, item(
                    materials[i],
                    "§f" + colors[i],
                    "§7Натисніть, щоб вибрати"
            ));
        }

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7Повернутися"
        ));

        player.openInventory(inventory);
    }

    private void openBranchConditions(Player player, String dialogueId, String nodeId) {
        DialogueCore.Node node = getNode(dialogueId, nodeId);
        if (node == null) {
            openNodeList(player, dialogueId, 0);
            return;
        }

        Inventory inventory = createHolder(
                player,
                Screen.BRANCH_CONDITIONS,
                dialogueId,
                nodeId,
                null,
                54,
                "§8Умови гілки"
        );

        inventory.setItem(10, item(
                Material.COMPARATOR,
                "§eДодати умову",
                "§7Додати умову доступності гілки"
        ));

        inventory.setItem(12, item(
                Material.PAPER,
                "§fУмови",
                "§7Кількість: §e" + node.getConditions().size()
        ));

        inventory.setItem(14, item(
                Material.REPEATER,
                "§bЛогіка умов",
                "§7Поточна логіка: §e" + node.getConditionLogic()
        ));

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7До налаштувань гілки"
        ));

        player.openInventory(inventory);
    }

    private void openBranchRewards(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        openRewardList(
                player,
                dialogueId,
                nodeId,
                null,
                0
        );
    }

    private void openInheritanceReset(Player player, String dialogueId, String nodeId) {
        Inventory inventory = createHolder(
                player,
                Screen.INHERITANCE,
                dialogueId,
                nodeId,
                null,
                54,
                "§8Спадкування налаштувань"
        );

        String[] keys = {
                "text-color",
                "background-color",
                "text-scale",
                "text-location",
                "camera-location",
                "typing-speed",
                "typing-sound",
                "delay"
        };

        for (int i = 0; i < keys.length; i++) {
            inventory.setItem(i, item(
                    Material.PAPER,
                    "§e" + keys[i],
                    "§7Натисніть, щоб скинути",
                    "§7значення до успадкованого"
            ));
        }

        inventory.setItem(SLOT_BACK, item(
                Material.ARROW,
                "§cНазад",
                "§7До налаштувань гілки"
        ));

        player.openInventory(inventory);
    }
    private void createCondition(Player player, String dialogueId, String nodeId, String answerId) {
        String conditionId = "condition_" + System.currentTimeMillis();

        if (answerId != null) {
            core.addAnswerCondition(
                    dialogueId,
                    nodeId,
                    answerId,
                    conditionId,
                    "permission",
                    "has",
                    "example.permission"
            );

            openConditionList(player, dialogueId, nodeId, answerId, 0);
            return;
        }

        core.addBranchCondition(
                dialogueId,
                nodeId,
                conditionId,
                "permission",
                "has",
                "example.permission"
        );

        openBranchConditions(player, dialogueId, nodeId);
    }

    private void toggleConditionLogic(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        if (answerId != null) {
            core.toggleAnswerConditionLogic(dialogueId, nodeId, answerId);
            openConditionList(player, dialogueId, nodeId, answerId, 0);
            return;
        }

        core.toggleBranchConditionLogic(dialogueId, nodeId);
        openBranchConditions(player, dialogueId, nodeId);
    }

    private void deleteCondition(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String conditionId
    ) {
        if (answerId != null) {
            core.deleteAnswerCondition(
                    dialogueId,
                    nodeId,
                    answerId,
                    conditionId
            );

            openConditionList(player, dialogueId, nodeId, answerId, 0);
            return;
        }

        core.deleteBranchCondition(dialogueId, nodeId, conditionId);
        openBranchConditions(player, dialogueId, nodeId);
    }

    private void createReward(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        String rewardId = "reward_" + System.currentTimeMillis();

        if (answerId != null) {
            core.addAnswerReward(
                    dialogueId,
                    nodeId,
                    answerId,
                    rewardId,
                    "command",
                    "say Нова нагорода",
                    1
            );

            save(dialogueId);
            openRewardList(
                    player,
                    dialogueId,
                    nodeId,
                    answerId,
                    0
            );
            return;
        }

        core.addBranchReward(
                dialogueId,
                nodeId,
                rewardId,
                "command",
                "say Нова нагорода",
                1
        );

        save(dialogueId);
        openBranchRewards(player, dialogueId, nodeId);
    }

    private void deleteReward(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        if (answerId != null) {
            core.deleteAnswerReward(
                    dialogueId,
                    nodeId,
                    answerId,
                    rewardId
            );

            save(dialogueId);
            openRewardList(
                    player,
                    dialogueId,
                    nodeId,
                    answerId,
                    0
            );
            return;
        }

        core.deleteBranchReward(
                dialogueId,
                nodeId,
                rewardId
        );

        save(dialogueId);
        openBranchRewards(player, dialogueId, nodeId);
    }

    private void resetBranchSetting(
            Player player,
            String dialogueId,
            String nodeId,
            String key
    ) {
        core.resetBranchSetting(dialogueId, nodeId, key);
        save(dialogueId);

        player.sendMessage(Component.text(
                "Налаштування скинуто до успадкованого значення."
        ));

        openInheritanceReset(player, dialogueId, nodeId);
    }

    private void changeConditionType(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String conditionId
    ) {
        requestChatInput(player, "Введіть тип умови:", input -> {
            if (answerId != null) {
                core.updateAnswerCondition(
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId,
                        input.trim(),
                        null,
                        null
                );

                openConditionMenu(
                        player,
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId
                );
                return;
            }

            core.updateBranchCondition(
                    dialogueId,
                    nodeId,
                    conditionId,
                    input.trim(),
                    null,
                    null
            );

            openBranchConditions(player, dialogueId, nodeId);
        });
    }

    private void changeConditionOperator(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String conditionId
    ) {
        requestChatInput(player, "Введіть оператор умови:", input -> {
            if (answerId != null) {
                core.updateAnswerCondition(
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId,
                        null,
                        input.trim(),
                        null
                );

                openConditionMenu(
                        player,
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId
                );
                return;
            }

            core.updateBranchCondition(
                    dialogueId,
                    nodeId,
                    conditionId,
                    null,
                    input.trim(),
                    null
            );

            openBranchConditions(player, dialogueId, nodeId);
        });
    }

    private void changeConditionValue(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String conditionId
    ) {
        requestChatInput(player, "Введіть значення умови:", input -> {
            if (answerId != null) {
                core.updateAnswerCondition(
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId,
                        null,
                        null,
                        input
                );

                openConditionMenu(
                        player,
                        dialogueId,
                        nodeId,
                        answerId,
                        conditionId
                );
                return;
            }

            core.updateBranchCondition(
                    dialogueId,
                    nodeId,
                    conditionId,
                    null,
                    null,
                    input
            );

            openBranchConditions(player, dialogueId, nodeId);
        });
    }

    private void changeRewardType(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        requestChatInput(player, "Введіть тип нагороди (command або item):", input -> {
            String type;

            if (input.trim().equalsIgnoreCase("command")) {
                type = "command";
            } else if (input.trim().equalsIgnoreCase("item")) {
                type = "item";
            } else {
                player.sendMessage(Component.text(
                        "Доступні типи: command або item."
                ));
                openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
                return;
            }

            if (answerId != null) {
                core.updateAnswerReward(
                        dialogueId,
                        nodeId,
                        answerId,
                        rewardId,
                        type,
                        null,
                        null
                );
            } else {
                core.updateBranchReward(
                        dialogueId,
                        nodeId,
                        rewardId,
                        type,
                        null,
                        null
                );
            }

            save(dialogueId);
            openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
        });
    }

    private void changeRewardValue(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        requestChatInput(player, "Введіть значення нагороди:", input -> {
            if (answerId != null) {
                core.updateAnswerReward(
                        dialogueId,
                        nodeId,
                        answerId,
                        rewardId,
                        null,
                        input,
                        null
                );
            } else {
                core.updateBranchReward(
                        dialogueId,
                        nodeId,
                        rewardId,
                        null,
                        input,
                        null
                );
            }

            save(dialogueId);
            openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
        });
    }

    private void changeRewardAmount(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        requestChatInput(player, "Введіть кількість предметів:", input -> {
            try {
                int amount = Integer.parseInt(input.trim());

                if (amount < 1 || amount > 2304) {
                    throw new NumberFormatException();
                }

                if (answerId != null) {
                    core.updateAnswerReward(
                            dialogueId,
                            nodeId,
                            answerId,
                            rewardId,
                            null,
                            null,
                            amount
                    );
                } else {
                    core.updateBranchReward(
                            dialogueId,
                            nodeId,
                            rewardId,
                            null,
                            null,
                            amount
                    );
                }

                save(dialogueId);
                openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);

            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Введіть число від 1 до 2304."));
                openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
            }
        });
    }

    private void captureRewardItem(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String rewardId
    ) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType().isAir()) {
            player.sendMessage(Component.text("Візьміть предмет у головну руку."));
            openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
            return;
        }

        ItemStack copy = hand.clone();

        if (answerId != null) {
            core.updateAnswerReward(
                    dialogueId,
                    nodeId,
                    answerId,
                    rewardId,
                    "item",
                    null,
                    copy.getAmount()
            );

            core.setAnswerRewardItem(
                    dialogueId,
                    nodeId,
                    answerId,
                    rewardId,
                    copy
            );
        } else {
            core.updateBranchReward(
                    dialogueId,
                    nodeId,
                    rewardId,
                    "item",
                    null,
                    copy.getAmount()
            );

            core.setBranchRewardItem(
                    dialogueId,
                    nodeId,
                    rewardId,
                    copy
            );
        }

        save(dialogueId);
        openRewardMenu(player, dialogueId, nodeId, answerId, rewardId);
    }
    private void handleConfirmDeleteClick(Player player, EditorHolder holder, int slot) {
        if (slot == 15) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else if (holder.nodeId != null) {
                openNodeMenu(player, holder.dialogueId, holder.nodeId);
            } else {
                openDialogueMenu(player, holder.dialogueId);
            }
            return;
        }

        if (slot != 11) {
            return;
        }

        if (holder.answerId != null) {
            core.deleteAnswer(
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            save(holder.dialogueId);
            openAnswerList(player, holder.dialogueId, holder.nodeId, 0);
            return;
        }

        if (holder.nodeId != null) {
            core.deleteBranch(holder.dialogueId, holder.nodeId);
            save(holder.dialogueId);
            openNodeList(player, holder.dialogueId, 0);
            return;
        }

        core.deleteDialogue(holder.dialogueId);
        save(holder.dialogueId);
        openMainMenu(player, 0);
    }

    private void handleCommandClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (slot == 10) {
            if (holder.answerId != null) {
                addAnswerCommand(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else {
                addBranchCommand(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
        }
    }

    private void handleBranchConditionsClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (slot == 10) {
            createCondition(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null
            );
            return;
        }

        if (slot == 14) {
            toggleConditionLogic(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null
            );
        }
    }

    private void handleBranchRewardsClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (slot == 10) {
            createReward(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null
            );
        }
    }

    private void handleInheritanceClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        String[] keys = {
                "text-color",
                "background-color",
                "text-scale",
                "text-location",
                "camera-location",
                "typing-speed",
                "typing-sound",
                "delay",
                "return-to-start"
        };

        if (slot >= 0 && slot < keys.length) {
            resetBranchSetting(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    keys[slot]
            );
        }
    }

    private void handleColorPickerClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            if (holder.nodeId == null) {
                openDialogueSettings(player, holder.dialogueId);
            } else {
                openBranchSettings(player, holder.dialogueId, holder.nodeId);
            }
            return;
        }

        String[] colors = {
                "#FFFFFF",
                "#FFAA00",
                "#FF55FF",
                "#55FFFF",
                "#FFFF55",
                "#55FF55",
                "#FFAAAA",
                "#555555",
                "#AAAAAA",
                "#00AAAA",
                "#AA00AA",
                "#5555FF",
                "#AA5500",
                "#00AA00",
                "#FF5555",
                "#000000"
        };

        if (slot < 0 || slot >= colors.length) {
            return;
        }

        String color = colors[slot];

        if (holder.nodeId == null) {
            core.setGlobalSetting(
                    holder.dialogueId,
                    holder.answerId,
                    color
            );

            save(holder.dialogueId);
            openDialogueSettings(player, holder.dialogueId);
        } else {
            core.setBranchSetting(
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    color
            );

            save(holder.dialogueId);
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
        }
    }

    private void handleSoundParametersClick(Player player, EditorHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else if (holder.nodeId != null) {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            } else {
                openDialogueSettings(player, holder.dialogueId);
            }
            return;
        }

        if (slot == 11) {
            requestChatInput(player, "Введіть гучність звуку (0.0–2.0):", input -> {
                try {
                    float volume = Float.parseFloat(input.trim());

                    if (!Float.isFinite(volume) || volume < 0 || volume > 2) {
                        throw new NumberFormatException();
                    }

                    if (holder.answerId != null) {
                        core.setAnswerSoundVolume(
                                holder.dialogueId,
                                holder.nodeId,
                                holder.answerId,
                                volume
                        );
                    } else if (holder.nodeId != null) {
                        core.setBranchSetting(
                                holder.dialogueId,
                                holder.nodeId,
                                "sound-volume",
                                String.valueOf(volume)
                        );
                    } else {
                        core.setGlobalSetting(
                                holder.dialogueId,
                                "sound-volume",
                                String.valueOf(volume)
                        );
                    }
                    save(holder.dialogueId);
                } catch (NumberFormatException exception) {
                    player.sendMessage(Component.text("Введіть число від 0.0 до 2.0."));
                }

                openSoundParameters(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            });
            return;
        }

        if (slot == 15) {
            requestChatInput(player, "Введіть висоту тону (0.5–2.0):", input -> {
                try {
                    float pitch = Float.parseFloat(input.trim());

                    if (!Float.isFinite(pitch) || pitch < 0.5f || pitch > 2.0f) {
                        throw new NumberFormatException();
                    }

                    if (holder.answerId != null) {
                        core.setAnswerSoundPitch(
                                holder.dialogueId,
                                holder.nodeId,
                                holder.answerId,
                                pitch
                        );
                    } else if (holder.nodeId != null) {
                        core.setBranchSetting(
                                holder.dialogueId,
                                holder.nodeId,
                                "sound-pitch",
                                String.valueOf(pitch)
                        );
                    } else {
                        core.setGlobalSetting(
                                holder.dialogueId,
                                "sound-pitch",
                                String.valueOf(pitch)
                        );
                    }

                    save(holder.dialogueId);
                } catch (NumberFormatException exception) {
                    player.sendMessage(Component.text("Введіть число від 0.5 до 2.0."));
                }

                openSoundParameters(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            });
        }
    }

    private void handleBranchConditionEditClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchConditions(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (holder.answerId == null) {
            return;
        }

        if (slot == 10) {
            changeConditionType(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        } else if (slot == 12) {
            changeConditionOperator(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        } else if (slot == 14) {
            changeConditionValue(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        }
    }

    private void handleBranchRewardEditClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchRewards(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (holder.answerId == null) {
            return;
        }

        if (slot == 10) {
            changeRewardType(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        } else if (slot == 12) {
            changeRewardValue(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        } else if (slot == 14) {
            changeRewardAmount(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    null,
                    holder.answerId
            );
        }
    }
    private void handleBranchConditionListClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (slot == 10) {
            createCondition(player, holder.dialogueId, holder.nodeId, null);
            return;
        }

        if (slot == 14) {
            toggleConditionLogic(player, holder.dialogueId, holder.nodeId, null);
        }
    }

    private void handleBranchRewardListClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (slot == 10) {
            createReward(player, holder.dialogueId, holder.nodeId, null);
        }
    }

    private void handleConditionEditClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK || slot == 16) {
            if (holder.answerId == null) {
                openBranchConditions(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            } else {
                openConditionList(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        0
                );
            }
            return;
        }

        if (slot == 10) {
            changeConditionType(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 12) {
            changeConditionOperator(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 14) {
            changeConditionValue(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 50) {
            deleteCondition(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        }
    }

    private void handleRewardEditClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            if (holder.answerId == null) {
                openBranchRewards(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            } else {
                openRewardList(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        0
                );
            }
            return;
        }

        if (slot == 10) {
            changeRewardType(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 12) {
            changeRewardValue(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 14) {
            captureRewardItem(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 15) {
            changeRewardAmount(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        } else if (slot == 16) {
            save(holder.dialogueId);

            if (holder.answerId == null) {
                openBranchRewards(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            } else {
                openRewardList(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        0
                );
            }
        } else if (slot == 50) {
            deleteReward(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.extraId
            );
        }
    }

    private void handleBranchCommandClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }

        if (slot == 10) {
            addBranchCommand(player, holder.dialogueId, holder.nodeId);
        }
    }

    private void handleAnswerCommandClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        if (slot == 10) {
            addAnswerCommand(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
        }
    }

    private void handleSoundPickerClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == SLOT_BACK) {
            if (holder.answerId != null) {
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (slot < 0 || slot >= Sound.values().length) {
            return;
        }

        Sound sound = Sound.values()[slot];

        if (click.isRightClick()) {
            if (holder.answerId != null) {
                core.setAnswerSound(
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        sound.name()
                );
                save(holder.dialogueId);
                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            } else {
                core.setBranchSetting(
                        holder.dialogueId,
                        holder.nodeId,
                        "typing-sound",
                        sound.name()
                );
                save(holder.dialogueId);
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        player.playSound(
                player.getLocation(),
                sound,
                1.0f,
                1.0f
        );
    }

    private void handleColorSelect(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        String[] colors = {
                "#FFFFFF", "#FFAA00", "#FF55FF", "#55FFFF",
                "#FFFF55", "#55FF55", "#FFAAAA", "#555555",
                "#AAAAAA", "#00AAAA", "#AA00AA", "#5555FF",
                "#AA5500", "#00AA00", "#FF5555", "#000000"
        };

        if (slot == SLOT_BACK) {
            if (holder.nodeId == null) {
                openDialogueSettings(player, holder.dialogueId);
            } else {
                openBranchSettings(
                        player,
                        holder.dialogueId,
                        holder.nodeId
                );
            }
            return;
        }

        if (slot < 0 || slot >= colors.length) {
            return;
        }

        String key = holder.answerId;

        if (key == null || key.isBlank()) {
            return;
        }

        if (holder.nodeId == null) {
            core.setGlobalSetting(holder.dialogueId, key, colors[slot]);
            save(holder.dialogueId);
            openDialogueSettings(player, holder.dialogueId);
        } else {
            core.setBranchSetting(
                    holder.dialogueId,
                    holder.nodeId,
                    key,
                    colors[slot]
            );
            save(holder.dialogueId);
            openBranchSettings(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
        }
    }

    private void handleInheritanceResetClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == SLOT_BACK) {
            openBranchSettings(player, holder.dialogueId, holder.nodeId);
            return;
        }
        String[] keys = {
            "text-color",
            "background-color",
            "text-scale",
            "text-location",
            "camera-location",
            "typing-speed",
            "typing-sound",
            "delay"
        };

        if (slot < 0 || slot >= keys.length) {
            return;
        }

        resetBranchSetting(
                player,
                holder.dialogueId,
                holder.nodeId,
                keys[slot]
        );
    }

    private void handleSoundParameters(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        handleSoundParametersClick(player, holder, slot);
    }

    private void handleBranchSettingsBack(
            Player player,
            EditorHolder holder
    ) {
        if (holder.nodeId == null) {
            openDialogueMenu(player, holder.dialogueId);
        } else {
            openNodeMenu(player, holder.dialogueId, holder.nodeId);
        }
    }
    private void handleSpecialScreenClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        switch (holder.screen) {
            case CONFIRM_DELETE ->
                    handleConfirmDeleteClick(player, holder, slot);

            case COMMANDS ->
                    handleCommandClick(player, holder, slot);

            case BRANCH_CONDITIONS ->
                    handleBranchConditionsClick(player, holder, slot);

            case BRANCH_REWARDS ->
                    handleBranchRewardsClick(player, holder, slot);

            case INHERITANCE ->
                    handleInheritanceClick(player, holder, slot);

            case COLOR_PICKER ->
                    handleColorPickerClick(player, holder, slot);

            case SOUND_PARAMETERS ->
                    handleSoundParametersClick(player, holder, slot);

            case CONDITION ->
                    handleConditionEditClick(player, holder, slot);

            case REWARD ->
                    handleRewardEditClick(player, holder, slot);

            case BRANCH_PICKER ->
                    handleBranchPickerClick(player, holder, slot, click);

            case SOUND_PICKER ->
                    handleSoundClick(player, holder, slot, click);

            default -> {
            }
        }
    }

    private boolean isEditorInventory(Inventory inventory) {
        return inventory != null
                && inventory.getHolder() instanceof EditorHolder;
    }

    private EditorHolder getEditorHolder(Inventory inventory) {
        if (!isEditorInventory(inventory)) {
            return null;
        }

        return (EditorHolder) inventory.getHolder();
    }

    private void reopenCurrentScreen(Player player, EditorHolder holder) {
        switch (holder.screen) {
            case MAIN -> openMainMenu(player, holder.page);

            case DIALOGUE -> openDialogueMenu(
                    player,
                    holder.dialogueId
            );

            case NODE_LIST -> openNodeList(
                    player,
                    holder.dialogueId,
                    holder.page
            );

            case NODE -> openNodeMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            case ANSWER_LIST -> openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page
            );

            case ANSWER -> {
                if (holder.answerId == null) {
                    openAnswerList(
                            player,
                            holder.dialogueId,
                            holder.nodeId,
                            holder.page
                    );
                } else {
                    openAnswerMenu(
                            player,
                            holder.dialogueId,
                            holder.nodeId,
                            holder.answerId
                    );
                }
            }

            case SETTINGS -> {
                if (holder.nodeId == null) {
                    openDialogueSettings(player, holder.dialogueId);
                } else {
                    openBranchSettings(
                            player,
                            holder.dialogueId,
                            holder.nodeId
                    );
                }
            }

            case CONDITION_LIST -> openConditionList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page
            );

            case REWARD_LIST -> openRewardList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page
            );

            case SOUND_PICKER -> openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page
            );

            case BRANCH_PICKER -> openBranchPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page
            );

            default -> handleSpecialScreenClick(
                    player,
                    holder,
                    -1,
                    ClickType.UNKNOWN
            );
        }
    }

    private void closeEditor(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.closeInventory();
    }

    private void sendEditorMessage(Player player, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage(Component.text(message));
    }

    private boolean validDialogue(String dialogueId) {
        return dialogueId != null
                && !dialogueId.isBlank()
                && core.getDialogue(dialogueId) != null;
    }

    private boolean validBranch(String dialogueId, String nodeId) {
        return validDialogue(dialogueId)
                && nodeId != null
                && core.getDialogue(dialogueId).getNode(nodeId) != null;
    }

    private boolean validAnswer(
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        if (!validBranch(dialogueId, nodeId) || answerId == null) {
            return false;
        }

        return core.getAnswer(dialogueId, nodeId, answerId) != null;
    }

    private void refreshAfterMutation(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        save(dialogueId);

        if (answerId != null) {
            openAnswerMenu(player, dialogueId, nodeId, answerId);
        } else if (nodeId != null) {
            openNodeMenu(player, dialogueId, nodeId);
        } else {
            openDialogueMenu(player, dialogueId);
        }
    }

    private String cleanInput(String input) {
        if (input == null) {
            return "";
        }

        return input.trim();
    }

    private String safeId(String input, String fallback) {
        String id = normalizeId(input);

        if (id.isBlank()) {
            return fallback;
        }

        return id;
    }

    private int clampPage(int page, int size) {
        if (size <= 0) {
            return 0;
        }

        int maxPage = Math.max(
                0,
                (size - 1) / ENTRIES_PER_PAGE
        );

        return Math.max(0, Math.min(page, maxPage));
    }

    private void playEditorClick(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.UI_BUTTON_CLICK,
                0.5f,
                1.2f
        );
    }

    private void playEditorError(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                0.7f,
                0.7f
        );
    }

    private void playEditorSuccess(Player player) {
        player.playSound(
                player.getLocation(),
                Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                0.6f,
                1.3f
        );
    }

    private void updateInventoryTitle(Player player, String title) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Inventory inventory = player.getOpenInventory().getTopInventory();

        if (!isEditorInventory(inventory)) {
            return;
        }

        player.openInventory(Bukkit.createInventory(
                inventory.getHolder(),
                inventory.getSize(),
                Component.text(title)
        ));
    }

    private void ensureMainBranch(String dialogueId) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);

        if (dialogue == null) {
            return;
        }

        if (dialogue.getNode("start") == null) {
            core.createBranch(dialogueId, "start");
        }
    }

    private void ensureDialogueSaved(String dialogueId) {
        if (validDialogue(dialogueId)) {
            core.saveDialogue(dialogueId);
        }
    }

    private void ensureBranchSaved(String dialogueId, String nodeId) {
        if (validBranch(dialogueId, nodeId)) {
            core.saveDialogue(dialogueId);
        }
    }

    private void ensureAnswerSaved(
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        if (validAnswer(dialogueId, nodeId, answerId)) {
            core.saveDialogue(dialogueId);
        }
    }

    private void clearPlayerPrompt(UUID playerId) {
        if (playerId != null) {
            chatPrompts.remove(playerId);
        }
    }

    private void clearAllPrompts() {
        chatPrompts.clear();
    }
}
