
package fritty.dialogue;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class DialogueEditor implements Listener {

    private static final int INVENTORY_SIZE = 54;
    private static final int ENTRIES_PER_PAGE = 28;

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

    /*
     * ============================================================
     * INITIALIZATION
     * ============================================================
     */

    public void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
    }

    /*
     * ============================================================
     * GUI SCREENS
     * ============================================================
     */

    private enum Screen {

        MAIN,
        DIALOGUE,
        NODES,
        NODE,
        ANSWER,
        SETTINGS,
        SOUND_PICKER,
        CONDITION_EDITOR,
        REWARD_EDITOR

    }

    private static final class EditorHolder
            implements InventoryHolder {

        private final Screen screen;

        private final String dialogueId;
        private final String nodeId;
        private final String answerId;

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

    /*
     * ============================================================
     * MAIN MENU
     * ============================================================
     */

    public void openMainMenu(
            Player player,
            int page
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        List<String> dialogueIds =
                new ArrayList<>(core.getDialogueIds());

        int maxPage = Math.max(
                0,
                (dialogueIds.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = new EditorHolder(
                Screen.MAIN,
                null,
                null,
                null,
                page
        );

        holder.inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text(
                        "FrittyDialogue • Діалоги",
                        NamedTextColor.DARK_RED
                )
        );

        fillBackground(holder.inventory);

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                dialogueIds.size()
        );

        int[] slots = createEntrySlots();

        for (int i = start; i < end; i++) {

            String id = dialogueIds.get(i);

            holder.entries.add(id);

            int slot = slots[i - start];

            holder.inventory.setItem(
                    slot,
                    createItem(
                            Material.WRITABLE_BOOK,
                            Component.text(
                                    id,
                                    NamedTextColor.GOLD
                            ),
                            List.of(
                                    Component.text(
                                            "Натисни, щоб відкрити редактор",
                                            NamedTextColor.GRAY
                                    ),
                                    Component.text(
                                            " ",
                                            NamedTextColor.GRAY
                                    ),
                                    Component.text(
                                            "ЛКМ — відкрити",
                                            NamedTextColor.YELLOW
                                    )
                            )
                    )
            );
        }

        holder.inventory.setItem(
                49,
                createItem(
                        Material.EMERALD,
                        Component.text(
                                "Створити діалог",
                                NamedTextColor.GREEN
                        ),
                        List.of(
                                Component.text(
                                        "Натисни, щоб створити новий діалог",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Назву введеш у чаті",
                                        NamedTextColor.YELLOW
                                )
                        )
                )
        );

        holder.inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        Component.text(
                                "Попередня сторінка",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "Сторінка " + (page + 1),
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                53,
                createItem(
                        Material.ARROW,
                        Component.text(
                                "Наступна сторінка",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "Сторінка " + (page + 1),
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                4,
                createItem(
                        Material.BOOK,
                        Component.text(
                                "FrittyDialogue",
                                NamedTextColor.RED
                        ),
                        List.of(
                                Component.text(
                                        "Редактор діалогів",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Усього діалогів: "
                                                + dialogueIds.size(),
                                        NamedTextColor.YELLOW
                                ),
                                Component.text(
                                        "Сторінка: "
                                                + (page + 1)
                                                + " / "
                                                + (maxPage + 1),
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        player.openInventory(holder.inventory);

        playGuiSound(
                player,
                Sound.UI_BUTTON_CLICK
        );
    }

    /*
     * ============================================================
     * INVENTORY DECORATION
     * ============================================================
     */

    private void fillBackground(Inventory inventory) {

        ItemStack filler = createItem(
                Material.BLACK_STAINED_GLASS_PANE,
                Component.text(" "),
                List.of()
        );

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private int[] createEntrySlots() {

        return new int[] {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
    }

    private ItemStack createItem(
            Material material,
            Component name,
            List<Component> lore
    ) {
        ItemStack item = new ItemStack(material);

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {

            meta.displayName(
                    name.decoration(
                            TextDecoration.ITALIC,
                            false
                    )
            );

            List<Component> formattedLore =
                    new ArrayList<>();

            for (Component line : lore) {
                formattedLore.add(
                        line.decoration(
                                TextDecoration.ITALIC,
                                false
                        )
                );
            }

            meta.lore(formattedLore);

            item.setItemMeta(meta);
        }

        return item;
    }

    /*
     * ============================================================
     * GUI SOUND
     * ============================================================
     */

    private void playGuiSound(
            Player player,
            Sound sound
    ) {
        player.playSound(
                player.getLocation(),
                sound,
                0.7f,
                1.0f
        );
    }

    /*
     * ============================================================
     * MAIN MENU CLICK HANDLER
     * ============================================================
     */

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof EditorHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() != top) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        if (holder.screen != Screen.MAIN) {
            handleOtherScreenClick(
                    player,
                    holder,
                    slot,
                    event.getClick()
            );
            return;
        }

        handleMainMenuClick(
                player,
                holder,
                slot
        );
    }

    private void handleMainMenuClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {

        if (slot == 45) {
            openMainMenu(
                    player,
                    Math.max(0, holder.page - 1)
            );
            return;
        }

        if (slot == 53) {
            openMainMenu(
                    player,
                    holder.page + 1
            );
            return;
        }

        if (slot == 49) {
            requestDialogueName(player);
            return;
        }

        int[] slots = createEntrySlots();

        for (int i = 0; i < slots.length; i++) {

            if (slots[i] != slot) {
                continue;
            }

            if (i >= holder.entries.size()) {
                return;
            }

            String dialogueId = holder.entries.get(i);

            openDialogueMenu(
                    player,
                    dialogueId,
                    0
            );

            return;
        }
    }

    /*
     * ============================================================
     * CHAT INPUT
     * ============================================================
     */

    private void requestDialogueName(Player player) {

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи назву нового діалогу в чаті.",
                        NamedTextColor.YELLOW
                )
        );

        player.sendMessage(
                Component.text(
                        "Введи cancel, щоб скасувати.",
                        NamedTextColor.GRAY
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        name -> createDialogueFromInput(
                                player,
                                name
                        ),
                        () -> openMainMenu(player, 0)
                )
        );
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {

        Player player = event.getPlayer();

        ChatPrompt prompt = chatPrompts.remove(
                player.getUniqueId()
        );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String message =
                PlainTextComponentSerializer.plainText()
                        .serialize(event.message())
                        .trim();

        Bukkit.getScheduler().runTask(
                plugin,
                () -> {

                    if (!player.isOnline()) {
                        return;
                    }

                    if (message.equalsIgnoreCase("cancel")) {
                        prompt.cancel().run();
                        return;
                    }

                    prompt.handler().accept(message);
                }
        );
    }

    private void createDialogueFromInput(
            Player player,
            String name
    ) {

        if (name == null || name.isBlank()) {
            player.sendMessage(
                    Component.text(
                            "Назва не може бути порожньою.",
                            NamedTextColor.RED
                    )
            );

            openMainMenu(player, 0);
            return;
        }

        if (!name.matches("[a-zA-Z0-9_-]{1,48}")) {
            player.sendMessage(
                    Component.text(
                            "Дозволені лише латинські літери, цифри, "
                                    + "_ та -.",
                            NamedTextColor.RED
                    )
            );

            openMainMenu(player, 0);
            return;
        }

        boolean created = core.createDialogue(
                name,
                null
        );

        if (!created) {
            player.sendMessage(
                    Component.text(
                            "Не вдалося створити діалог. "
                                    + "Можливо, така назва вже існує.",
                            NamedTextColor.RED
                    )
            );

            openMainMenu(player, 0);
            return;
        }

        player.sendMessage(
                Component.text(
                        "Діалог " + name + " створено.",
                        NamedTextColor.GREEN
                )
        );

        openMainMenu(player, 0);
    }

    /*
     * ============================================================
     * OTHER SCREEN ROUTING
     * ============================================================
     */

    private void handleOtherScreenClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        // Реалізація меню вузлів, відповідей і налаштувань
        // буде додана в наступних частинах файлу.
  }
  
    /*
     * ============================================================
     * DIALOGUE MENU
     * ============================================================
     */

    private void openDialogueMenu(
            Player player,
            String dialogueId,
            int page
    ) {
        DialogueCore.Dialogue dialogue =
                core.getDialogue(dialogueId);

        if (dialogue == null) {
            player.sendMessage(
                    Component.text(
                            "Діалог не знайдено.",
                            NamedTextColor.RED
                    )
            );

            openMainMenu(player, 0);
            return;
        }

        List<String> nodeIds = new ArrayList<>(
                dialogue.getNodes().keySet()
        );

        int maxPage = Math.max(
                0,
                (nodeIds.size() - 1) / ENTRIES_PER_PAGE
        );

        page = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = new EditorHolder(
                Screen.DIALOGUE,
                dialogueId,
                null,
                null,
                page
        );

        holder.inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text(
                        "Діалог • " + dialogueId,
                        NamedTextColor.DARK_RED
                )
        );

        fillBackground(holder.inventory);

        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                nodeIds.size()
        );

        int[] slots = createEntrySlots();

        for (int i = start; i < end; i++) {

            String nodeId = nodeIds.get(i);

            holder.entries.add(nodeId);

            boolean root = i == 0;

            Material icon = root
                    ? Material.BOOK
                    : Material.PAPER;

            Component title = Component.text(
                    nodeId,
                    root
                            ? NamedTextColor.GOLD
                            : NamedTextColor.WHITE
            );

            holder.inventory.setItem(
                    slots[i - start],
                    createItem(
                            icon,
                            title,
                            List.of(
                                    Component.text(
                                            root
                                                    ? "Головна гілка"
                                                    : "Дочірня гілка",
                                            NamedTextColor.GRAY
                                    ),
                                    Component.text(
                                            " ",
                                            NamedTextColor.GRAY
                                    ),
                                    Component.text(
                                            "Натисни, щоб редагувати",
                                            NamedTextColor.YELLOW
                                    )
                            )
                    )
            );
        }

        holder.inventory.setItem(
                4,
                createItem(
                        Material.BOOK,
                        Component.text(
                                dialogueId,
                                NamedTextColor.GOLD
                        ),
                        List.of(
                                Component.text(
                                        "Керування діалогом",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Гілок: " + nodeIds.size(),
                                        NamedTextColor.YELLOW
                                )
                        )
                )
        );

        holder.inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        Component.text(
                                "Назад",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "До списку діалогів",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                48,
                createItem(
                        Material.WRITABLE_BOOK,
                        Component.text(
                                "Створити гілку",
                                NamedTextColor.GREEN
                        ),
                        List.of(
                                Component.text(
                                        "Створити нову гілку діалогу",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                50,
                createItem(
                        Material.ENCHANTED_BOOK,
                        Component.text(
                                "Налаштування діалогу",
                                NamedTextColor.LIGHT_PURPLE
                        ),
                        List.of(
                                Component.text(
                                        "Загальні параметри діалогу",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Камера, текст, звук та інше",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                53,
                createItem(
                        Material.ARROW,
                        Component.text(
                                "Наступна сторінка",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "Сторінка " + (page + 1),
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        player.openInventory(holder.inventory);
        playGuiSound(player, Sound.UI_BUTTON_CLICK);
    }

    /*
     * ============================================================
     * DIALOGUE MENU CLICK
     * ============================================================
     */

    private void handleDialogueMenuClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == 45) {
            openMainMenu(player, 0);
            return;
        }

        if (slot == 48) {
            requestNodeName(
                    player,
                    holder.dialogueId,
                    holder.page
            );
            return;
        }

        if (slot == 50) {
            openSettingsMenu(
                    player,
                    holder.dialogueId
            );
            return;
        }

        if (slot == 53) {
            openDialogueMenu(
                    player,
                    holder.dialogueId,
                    holder.page + 1
            );
            return;
        }

        int[] slots = createEntrySlots();

        for (int i = 0; i < slots.length; i++) {

            if (slots[i] != slot) {
                continue;
            }

            if (i >= holder.entries.size()) {
                return;
            }

            String nodeId = holder.entries.get(i);

            openNodeMenu(
                    player,
                    holder.dialogueId,
                    nodeId
            );

            return;
        }
    }

    /*
     * ============================================================
     * CREATE NODE
     * ============================================================
     */

    private void requestNodeName(
            Player player,
            String dialogueId,
            int page
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи назву нової гілки.",
                        NamedTextColor.YELLOW
                )
        );

        player.sendMessage(
                Component.text(
                        "Введи cancel для скасування.",
                        NamedTextColor.GRAY
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        name -> createNodeFromInput(
                                player,
                                dialogueId,
                                page,
                                name
                        ),
                        () -> openDialogueMenu(
                                player,
                                dialogueId,
                                page
                        )
                )
        );
    }

    private void createNodeFromInput(
            Player player,
            String dialogueId,
            int page,
            String nodeId
    ) {
        if (nodeId == null || nodeId.isBlank()) {
            player.sendMessage(
                    Component.text(
                            "Назва гілки не може бути порожньою.",
                            NamedTextColor.RED
                    )
            );

            openDialogueMenu(player, dialogueId, page);
            return;
        }

        if (!nodeId.matches("[a-zA-Z0-9_-]{1,48}")) {
            player.sendMessage(
                    Component.text(
                            "Використовуй латинські літери, цифри, _ або -.",
                            NamedTextColor.RED
                    )
            );

            openDialogueMenu(player, dialogueId, page);
            return;
        }

        boolean created = core.createNode(
                dialogueId,
                nodeId
        );

        if (!created) {
            player.sendMessage(
                    Component.text(
                            "Не вдалося створити гілку. "
                                    + "Перевір, чи така назва вже існує.",
                            NamedTextColor.RED
                    )
            );

            openDialogueMenu(player, dialogueId, page);
            return;
        }

        player.sendMessage(
                Component.text(
                        "Гілку " + nodeId + " створено.",
                        NamedTextColor.GREEN
                )
        );

        openNodeMenu(
                player,
                dialogueId,
                nodeId
        );
    }

    /*
     * ============================================================
     * NODE MENU
     * ============================================================
     */

    private void openNodeMenu(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        DialogueCore.Dialogue dialogue =
                core.getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Node node =
                dialogue.getNode(nodeId);

        if (node == null) {
            openDialogueMenu(player, dialogueId, 0);
            return;
        }

        EditorHolder holder = new EditorHolder(
                Screen.NODE,
                dialogueId,
                nodeId,
                null,
                0
        );

        holder.inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text(
                        "Гілка • " + nodeId,
                        NamedTextColor.DARK_RED
                )
        );

        fillBackground(holder.inventory);

        holder.inventory.setItem(
                4,
                createItem(
                        Material.WRITABLE_BOOK,
                        Component.text(
                                nodeId,
                                NamedTextColor.GOLD
                        ),
                        List.of(
                                Component.text(
                                        "Редагування гілки",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Відповідей: "
                                                + node.getAnswers().size(),
                                        NamedTextColor.YELLOW
                                )
                        )
                )
        );

        holder.inventory.setItem(
                20,
                createItem(
                        Material.PAPER,
                        Component.text(
                                "Текст персонажа",
                                NamedTextColor.WHITE
                        ),
                        List.of(
                                Component.text(
                                        "Змінити текст цієї гілки",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Натисни для введення тексту в чаті",
                                        NamedTextColor.YELLOW
                                )
                        )
                )
        );

        holder.inventory.setItem(
                22,
                createItem(
                        Material.CLOCK,
                        Component.text(
                                "Швидкість друку",
                                NamedTextColor.AQUA
                        ),
                        List.of(
                                Component.text(
                                        "Налаштування затримки між літерами",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                24,
                createItem(
                        Material.COMPASS,
                        Component.text(
                                "Наступна гілка",
                                NamedTextColor.LIGHT_PURPLE
                        ),
                        List.of(
                                Component.text(
                                        "Перехід після завершення тексту",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                29,
                createItem(
                        Material.WRITABLE_BOOK,
                        Component.text(
                                "Додати відповідь",
                                NamedTextColor.GREEN
                        ),
                        List.of(
                                Component.text(
                                        "Створити новий варіант відповіді",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                31,
                createItem(
                        Material.BOOK,
                        Component.text(
                                "Список відповідей",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "Переглянути та редагувати відповіді",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Кількість: "
                                                + node.getAnswers().size(),
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        holder.inventory.setItem(
                33,
                createItem(
                        Material.REDSTONE,
                        Component.text(
                                "Видалити гілку",
                                NamedTextColor.RED
                        ),
                        List.of(
                                Component.text(
                                        "Видалити цю гілку діалогу",
                                        NamedTextColor.GRAY
                                ),
                                Component.text(
                                        "Цю дію потрібно підтвердити",
                                        NamedTextColor.RED
                                )
                        )
                )
        );

        holder.inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        Component.text(
                                "Назад",
                                NamedTextColor.YELLOW
                        ),
                        List.of(
                                Component.text(
                                        "До списку гілок",
                                        NamedTextColor.GRAY
                                )
                        )
                )
        );

        player.openInventory(holder.inventory);
        playGuiSound(player, Sound.UI_BUTTON_CLICK);
    }

    /*
     * ============================================================
     * NODE MENU CLICK
     * ============================================================
     */

    private void handleNodeMenuClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        String dialogueId = holder.dialogueId;
        String nodeId = holder.nodeId;

        if (slot == 45) {
            openDialogueMenu(player, dialogueId, 0);
            return;
        }

        if (slot == 20) {
            requestNodeText(
                    player,
                    dialogueId,
                    nodeId
            );
            return;
        }

        if (slot == 22) {
            requestNodeDelay(
                    player,
                    dialogueId,
                    nodeId
            );
            return;
        }

        if (slot == 24) {
            requestNodeNext(
                    player,
                    dialogueId,
                    nodeId
            );
            return;
        }

        if (slot == 29) {
            requestAnswerName(
                    player,
                    dialogueId,
                    nodeId
            );
            return;
        }

        if (slot == 31) {
            openAnswersMenu(
                    player,
                    dialogueId,
                    nodeId,
                    0
            );
            return;
        }

        if (slot == 33) {
            requestNodeDelete(
                    player,
                    dialogueId,
                    nodeId
            );
        }
    }

    /*
     * ============================================================
     * NODE TEXT INPUT
     * ============================================================
     */

    private void requestNodeText(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи новий текст персонажа.",
                        NamedTextColor.YELLOW
                )
        );

        player.sendMessage(
                Component.text(
                        "Використовуй cancel для скасування.",
                        NamedTextColor.GRAY
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        text -> {
                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            node.setText(text);
                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Текст гілки оновлено.",
                                            NamedTextColor.GREEN
                                    )
                            );
                        },
                        () -> openNodeMenu(
                                player,
                                dialogueId,
                                nodeId
                        )
                )
        );
    }

    /*
     * ============================================================
     * NODE DELAY INPUT
     * ============================================================
     */

    private void requestNodeDelay(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи затримку між літерами в тіках.",
                        NamedTextColor.YELLOW
                )
        );

        player.sendMessage(
                Component.text(
                        "Наприклад: 2. Мінімум 0, максимум 100.",
                        NamedTextColor.GRAY
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            try {
                                int delay = Integer.parseInt(value);

                                if (delay < 0 || delay > 100) {
                                    throw new NumberFormatException();
                                }

                                DialogueCore.Dialogue dialogue =
                                        core.getDialogue(dialogueId);

                                if (dialogue == null) {
                                    return;
                                }

                                DialogueCore.Node node =
                                        dialogue.getNode(nodeId);

                                if (node == null) {
                                    return;
                                }

                                node.setDelay(delay);
                                core.saveDialogue(dialogueId);

                                player.sendMessage(
                                        Component.text(
                                                "Швидкість друку оновлено.",
                                                NamedTextColor.GREEN
                                        )
                                );

                            } catch (NumberFormatException exception) {
                                player.sendMessage(
                                        Component.text(
                                                "Введи ціле число від 0 до 100.",
                                                NamedTextColor.RED
                                        )
                                );
                            }
                        },
                        () -> openNodeMenu(
                                player,
                                dialogueId,
                                nodeId
                        )
                )
        );
    }

    /*
     * ============================================================
     * NODE NEXT INPUT
     * ============================================================
     */

    private void requestNodeNext(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи ID наступної гілки.",
                        NamedTextColor.YELLOW
                )
        );

        player.sendMessage(
                Component.text(
                        "Введи none, щоб завершувати діалог після цієї гілки.",
                        NamedTextColor.GRAY
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            if (value.equalsIgnoreCase("none")) {
                                node.setNextNode(null);
                            } else {
                                if (dialogue.getNode(value) == null) {
                                    player.sendMessage(
                                            Component.text(
                                                    "Гілки з таким ID не існує.",
                                                    NamedTextColor.RED
                                            )
                                    );
                                    return;
                                }

                                node.setNextNode(value);
                            }

                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Перехід гілки оновлено.",
                                            NamedTextColor.GREEN
                                    )
                            );
                        },
                        () -> openNodeMenu(
                                player,
                                dialogueId,
                                nodeId
                        )
                )
        );
    }

    /*
     * ============================================================
     * CREATE ANSWER
     * ============================================================
     */

    private void requestAnswerName(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи текст нової відповіді.",
                        NamedTextColor.YELLOW
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        text -> {
                            if (text.isBlank()) {
                                player.sendMessage(
                                        Component.text(
                                                "Текст відповіді не може бути порожнім.",
                                                NamedTextColor.RED
                                        )
                                );
                                return;
                            }

                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            String answerId =
                                    "answer_" + (node.getAnswers().size() + 1);

                            node.addAnswer(
                                    new DialogueCore.Answer(
                                            answerId,
                                            text
                                    )
                            );

                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Відповідь створено.",
                                            NamedTextColor.GREEN
                                    )
                            );

                            openAnswersMenu(
                                    player,
                                    dialogueId,
                                    nodeId,
                                    0
                            );
                        },
                        () -> openNodeMenu(
                                player,
                                dialogueId,
                                nodeId
                        )
                )
        );
    }

    /*
     * ============================================================
     * DELETE NODE
     * ============================================================
     */

    private void requestNodeDelete(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Для підтвердження видалення введи DELETE.",
                        NamedTextColor.RED
                )
        );

        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            if (!value.equals("DELETE")) {
                                player.sendMessage(
                                        Component.text(
                                                "Видалення скасовано.",
                                        NamedTextColor.YELLOW
                                        )
                                );
                                openNodeMenu(player, dialogueId, nodeId);
                                return;
                            }

                            if (core.deleteNode(dialogueId, nodeId)) {
                                player.sendMessage(
                                        Component.text(
                                                "Гілку успішно видалено.",
                                                NamedTextColor.GREEN
                                        )
                                );
                                openDialogueMenu(player, dialogueId, 0);
                            } else {
                                player.sendMessage(
                                        Component.text(
                                                "Не вдалося видалити гілку.",
                                                NamedTextColor.RED
                                        )
                                );
                                openDialogueMenu(player, dialogueId, 0);
                            }
                        },
                        "Введи DELETE у чат, щоб підтвердити видалення гілки."
                )
        );
        player.closeInventory();
        player.sendMessage(
                Component.text(
                        "Для підтвердження видалення введи DELETE. Інший текст скасує операцію.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void openAnswerMenu(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);

        if (dialogue == null) {
            player.sendMessage(
                    Component.text("Діалог не знайдено.", NamedTextColor.RED)
            );
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Node node = dialogue.getNode(nodeId);

        if (node == null) {
            player.sendMessage(
                    Component.text("Гілку не знайдено.", NamedTextColor.RED)
            );
            openDialogueMenu(player, dialogueId, 0);
            return;
        }

        DialogueCore.Answer answer = findAnswer(node, answerId);

        if (answer == null) {
            player.sendMessage(
                    Component.text("Відповідь не знайдено.", NamedTextColor.RED)
            );
            openNodeMenu(player, dialogueId, nodeId);
            return;
        }

        EditorHolder holder = new EditorHolder(
                Screen.ANSWER,
                dialogueId,
                nodeId,
                answerId,
                0,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Редагування відповіді")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        inventory.setItem(
                10,
                createItem(
                        Material.NAME_TAG,
                        "§eТекст відповіді",
                        List.of(
                                "§7Поточний текст:",
                                "§f" + answer.getText(),
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                12,
                createItem(
                        Material.PAPER,
                        "§eПерехід до гілки",
                        List.of(
                                "§7Поточна гілка:",
                                "§f" + (
                                        answer.getTargetNode() == null
                                                || answer.getTargetNode().isBlank()
                                                ? "Не задано"
                                                : answer.getTargetNode()
                                ),
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                14,
                createItem(
                        answer.isEnabled()
                                ? Material.LIME_DYE
                                : Material.GRAY_DYE,
                        "§eДоступність відповіді",
                        List.of(
                                "§7Статус: " + (
                                        answer.isEnabled()
                                                ? "§aУвімкнена"
                                                : "§cВимкнена"
                                ),
                                "",
                                "§aНатисни, щоб перемкнути"
                        )
                )
        );

        inventory.setItem(
                16,
                createItem(
                        Material.NOTE_BLOCK,
                        "§eЗвук відповіді",
                        List.of(
                                "§7Поточний звук:",
                                "§f" + (
                                        answer.getSound() == null
                                                || answer.getSound().isBlank()
                                                ? "Не задано"
                                                : answer.getSound()
                                ),
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                29,
                createItem(
                        Material.COMMAND_BLOCK,
                        "§eКоманди відповіді",
                        List.of(
                                "§7Налаштування команд,",
                                "§7які виконуються після вибору.",
                                "",
                                "§aНатисни, щоб редагувати"
                        )
                )
        );

        inventory.setItem(
                31,
                createItem(
                        Material.COMPARATOR,
                        "§eУмови відповіді",
                        List.of(
                                "§7Визначають, коли відповідь",
                                "§7доступна гравцеві.",
                                "",
                                "§aНатисни, щоб редагувати"
                        )
                )
        );

        inventory.setItem(
                33,
                createItem(
                        Material.CHEST,
                        "§eНагороди відповіді",
                        List.of(
                                "§7Нагороди після вибору",
                                "§7цієї відповіді.",
                                "",
                                "§aНатисни, щоб редагувати"
                        )
                )
        );

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до гілки")
                )
        );

        player.openInventory(inventory);
    }

    private DialogueCore.Answer findAnswer(
            DialogueCore.Node node,
            String answerId
    ) {
        for (DialogueCore.Answer answer : node.getAnswers()) {
            if (answer.getId().equalsIgnoreCase(answerId)) {
                return answer;
            }
        }

        return null;
    }

    private void handleAnswerMenuClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        DialogueCore.Dialogue dialogue =
                core.getDialogue(holder.dialogueId);

        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Node node = dialogue.getNode(holder.nodeId);

        if (node == null) {
            openDialogueMenu(player, holder.dialogueId, 0);
            return;
        }

        DialogueCore.Answer answer =
                findAnswer(node, holder.answerId);

        if (answer == null) {
            openNodeMenu(player, holder.dialogueId, holder.nodeId);
            return;
        }

        switch (slot) {
            case 10 -> requestAnswerText(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 12 -> requestAnswerTarget(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 14 -> {
                answer.setEnabled(!answer.isEnabled());
                core.saveDialogue(holder.dialogueId);

                openAnswerMenu(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId
                );
            }

            case 16 -> openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    0
            );

            case 29 -> requestAnswerCommands(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 31 -> openConditionEditor(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 33 -> openRewardEditor(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case 45 -> openNodeMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );

            default -> {
            }
        }
    }

    private void requestAnswerText(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            DialogueCore.Answer answer =
                                    findAnswer(node, answerId);

                            if (answer == null) {
                                return;
                            }

                            answer.setText(value);
                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Текст відповіді оновлено.",
                                            NamedTextColor.GREEN
                                    )
                            );

                            openAnswerMenu(
                                    player,
                                    dialogueId,
                                    nodeId,
                                    answerId
                            );
                        },
                        "Введи новий текст відповіді в чат."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи новий текст відповіді в чат.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestAnswerTarget(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            DialogueCore.Answer answer =
                                    findAnswer(node, answerId);

                            if (answer == null) {
                                return;
                            }

                            if (!value.equalsIgnoreCase("none")
                                    && dialogue.getNode(value) == null) {
                                player.sendMessage(
                                        Component.text(
                                                "Такої гілки не існує. "
                                                        + "Перевір її ID або введи none.",
                                                NamedTextColor.RED
                                        )
                                );

                                openAnswerMenu(
                                        player,
                                        dialogueId,
                                        nodeId,
                                        answerId
                                );
                                return;
                            }

                            answer.setTargetNode(
                                    value.equalsIgnoreCase("none")
                                            ? null
                                            : value
                            );

                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Перехід відповіді оновлено.",
                                            NamedTextColor.GREEN
                                    )
                            );

                            openAnswerMenu(
                                    player,
                                    dialogueId,
                                    nodeId,
                                    answerId
                            );
                        },
                        "Введи ID цільової гілки або none, щоб прибрати перехід."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи ID гілки, до якої веде відповідь. "
                                + "Введи none, якщо переходу не повинно бути.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestAnswerCommands(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                return;
                            }

                            DialogueCore.Answer answer =
                                    findAnswer(node, answerId);

                            if (answer == null) {
                                return;
                            }

                            List<String> commands = new ArrayList<>();

                            if (!value.equalsIgnoreCase("none")) {
                                for (String command : value.split("\\|")) {
                                    String trimmed = command.trim();

                                    if (!trimmed.isEmpty()) {
                                        commands.add(trimmed);
                                    }
                                }
                            }

                            answer.setCommands(commands);
                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Команди відповіді збережено.",
                                            NamedTextColor.GREEN
                                    )
                            );

                            openAnswerMenu(
                                    player,
                                    dialogueId,
                                    nodeId,
                                    answerId
                            );
                        },
                        "Введи команди через | або none, щоб очистити список."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи команди через символ |. "
                                + "Наприклад: say Привіт|give %player% bread 1. "
                                + "Введи none, щоб очистити команди.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestNewAnswer(
            Player player,
            String dialogueId,
            String nodeId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            String answerId = value
                                    .trim()
                                    .toLowerCase(Locale.ROOT)
                                    .replaceAll("[^a-z0-9_-]", "_");

                            if (answerId.isBlank()) {
                                player.sendMessage(
                                        Component.text(
                                                "ID відповіді не може бути порожнім.",
                                                NamedTextColor.RED
                                        )
                                );
                                openNodeMenu(player, dialogueId, nodeId);
                                return;
                            }

                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                openMainMenu(player, 0);
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                openDialogueMenu(player, dialogueId, 0);
                                return;
                            }

                            if (findAnswer(node, answerId) != null) {
                                player.sendMessage(
                                        Component.text(
                                                "Відповідь із таким ID уже існує.",
                                                NamedTextColor.RED
                                        )
                                );
                                openNodeMenu(player, dialogueId, nodeId);
                                return;
                            }

                            DialogueCore.Answer answer =
                                    new DialogueCore.Answer(
                                            answerId,
                                            value.trim()
                                    );

                            node.addAnswer(answer);
                            core.saveDialogue(dialogueId);

                            player.sendMessage(
                                    Component.text(
                                            "Відповідь створено.",
                                            NamedTextColor.GREEN
                                    )
                            );

                            openAnswerMenu(
                                    player,
                                    dialogueId,
                                    nodeId,
                                    answerId
                            );
                        },
                        "Введи ID нової відповіді."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи ID нової відповіді. "
                                + "Використовуй латинські літери, цифри, _ або -.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void openAnswerList(
            Player player,
            String dialogueId,
            String nodeId,
            int page
    ) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Node node = dialogue.getNode(nodeId);

        if (node == null) {
            openDialogueMenu(player, dialogueId, 0);
            return;
        }

        List<DialogueCore.Answer> answers =
                new ArrayList<>(node.getAnswers());

        int maxPage = Math.max(
                0,
                (answers.size() - 1) / ENTRIES_PER_PAGE
        );

        int safePage = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = new EditorHolder(
                Screen.NODE,
                dialogueId,
                nodeId,
                null,
                safePage,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Відповіді: " + nodeId)
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                answers.size()
        );

        int[] slots = createEntrySlots();

        for (int i = start; i < end; i++) {
            DialogueCore.Answer answer = answers.get(i);
            holder.entries.add(answer.getId());

            inventory.setItem(
                    slots[i - start],
                    createItem(
                            answer.isEnabled()
                                    ? Material.PAPER
                                    : Material.GRAY_DYE,
                            "§e" + answer.getId(),
                            List.of(
                                    "§7" + answer.getText(),
                                    "",
                                    "§7Статус: " + (
                                            answer.isEnabled()
                                                    ? "§aУвімкнена"
                                                    : "§cВимкнена"
                                    ),
                                    "",
                                    "§aНатисни, щоб редагувати"
                            )
                    )
            );
        }

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до гілки")
                )
        );

        inventory.setItem(
                48,
                createItem(
                        Material.WRITABLE_BOOK,
                        "§aСтворити відповідь",
                        List.of("§7Додати нову відповідь")
                )
        );

        inventory.setItem(
                49,
                createItem(
                        Material.PAPER,
                        "§eСторінка " + (safePage + 1)
                                + " / " + (maxPage + 1),
                        List.of("§7Усього відповідей: " + answers.size())
                )
        );

        if (safePage > 0) {
            inventory.setItem(
                    51,
                    createItem(
                            Material.ARROW,
                            "§eПопередня сторінка",
                            List.of("§7Перейти назад")
                    )
            );
        }

        if (safePage < maxPage) {
            inventory.setItem(
                    53,
                    createItem(
                            Material.ARROW,
                            "§eНаступна сторінка",
                            List.of("§7Перейти вперед")
                    )
            );
        }

        player.openInventory(inventory);
    }

    private void openSettingsMenu(
            Player player,
            String dialogueId
    ) {
        DialogueCore.Dialogue dialogue = core.getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Settings settings = dialogue.getSettings();

        EditorHolder holder = new EditorHolder(
                Screen.SETTINGS,
                dialogueId,
                null,
                null,
                0,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Налаштування діалогу")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        inventory.setItem(
                10,
                createItem(
                        Material.ENDER_EYE,
                        "§eПозиція камери",
                        List.of(
                                "§7Поточна позиція:",
                                "§f" + formatLocation(
                                        settings.getCameraLocation()
                                ),
                                "",
                                "§aНатисни, щоб зберегти",
                                "§7поточну позицію гравця"
                        )
                )
        );

        inventory.setItem(
                12,
                createItem(
                        Material.ARMOR_STAND,
                        "§eПозиція тексту",
                        List.of(
                                "§7Визначає місце появи",
                                "§7тексту діалогу.",
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                14,
                createItem(
                        Material.GLASS,
                        "§eФон тексту",
                        List.of(
                                "§7Колір та прозорість",
                                "§7фону текстового вікна.",
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                16,
                createItem(
                        Material.PAINTING,
                        "§eКолір тексту",
                        List.of(
                                "§7Колір символів діалогу.",
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                28,
                createItem(
                        Material.CLOCK,
                        "§eШвидкість друку",
                        List.of(
                                "§7Затримка між символами.",
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                30,
                createItem(
                        Material.NOTE_BLOCK,
                        "§eЗвук друку",
                        List.of(
                                "§7Звук під час появи символів.",
                                "",
                                "§aНатисни, щоб вибрати"
                        )
                )
        );

        inventory.setItem(
                32,
                createItem(
                        Material.SPYGLASS,
                        "§eВідображення крізь блоки",
                        List.of(
                                "§7Керування видимістю тексту",
                                "§7за перешкодами.",
                                "",
                                "§aНатисни, щоб перемкнути"
                        )
                )
        );

        inventory.setItem(
                34,
                createItem(
                        Material.BOOK,
                        "§eІнші параметри",
                        List.of(
                                "§7Додаткові налаштування",
                                "§7поточного діалогу.",
                                "",
                                "§aНатисни, щоб відкрити"
                        )
                )
        );

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до діалогу")
                )
        );

        player.openInventory(inventory);
    }

    private String formatLocation(org.bukkit.Location location) {
        if (location == null) {
            return "§cНе задано";
        }

        return String.format(
                Locale.US,
                "§f%s: %.2f %.2f %.2f",
                location.getWorld() == null
                        ? "?"
                        : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ()
        );
    }

    private void openSoundPicker(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            int page
    ) {
        List<String> sounds = new ArrayList<>();

        for (Sound sound : Sound.values()) {
            sounds.add(sound.name().toLowerCase(Locale.ROOT));
        }

        sounds.sort(String::compareTo);

        int maxPage = Math.max(
                0,
                (sounds.size() - 1) / ENTRIES_PER_PAGE
        );

        int safePage = Math.max(0, Math.min(page, maxPage));

        EditorHolder holder = new EditorHolder(
                Screen.SOUND_PICKER,
                dialogueId,
                nodeId,
                answerId,
                safePage,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Вибір звуку")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        int start = safePage * ENTRIES_PER_PAGE;
        int end = Math.min(
                start + ENTRIES_PER_PAGE,
                sounds.size()
        );

        int[] slots = createEntrySlots();

        for (int i = start; i < end; i++) {
            String soundName = sounds.get(i);

            holder.entries.add(soundName);

            inventory.setItem(
                    slots[i - start],
                    createItem(
                            Material.NOTE_BLOCK,
                            "§e" + soundName,
                            List.of(
                                    "§7Звук Minecraft",
                                    "",
                                    "§aЛКМ — прослухати",
                                    "§eПКМ — вибрати"
                            )
                    )
            );
        }

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до відповіді")
                )
        );

        inventory.setItem(
                49,
                createItem(
                        Material.PAPER,
                        "§eСторінка " + (safePage + 1)
                                + " / " + (maxPage + 1),
                        List.of(
                                "§7Усього звуків: " + sounds.size()
                        )
                )
        );

        inventory.setItem(
                50,
                createItem(
                        Material.BARRIER,
                        "§cПрибрати звук",
                        List.of(
                                "§7Відповідь не матиме",
                                "§7окремого звуку."
                        )
                )
        );

        if (safePage > 0) {
            inventory.setItem(
                    51,
                    createItem(
                            Material.ARROW,
                            "§eПопередня сторінка",
                            List.of("§7Перейти назад")
                    )
            );
        }

        if (safePage < maxPage) {
            inventory.setItem(
                    53,
                    createItem(
                            Material.ARROW,
                            "§eНаступна сторінка",
                            List.of("§7Перейти вперед")
                    )
            );
        }

        player.openInventory(inventory);
    }

    private void handleSoundPickerClick(
            Player player,
            EditorHolder holder,
            int slot,
            ClickType click
    ) {
        if (slot == 45) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        if (slot == 50) {
            setAnswerSound(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    null
            );
            return;
        }

        if (slot == 51 && holder.page > 0) {
            openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page - 1
            );
            return;
        }

        if (slot == 53) {
            openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page + 1
            );
            return;
        }

        int[] slots = createEntrySlots();

        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != slot || i >= holder.entries.size()) {
                continue;
            }

            String soundName = holder.entries.get(i);

            if (click.isLeftClick()) {
                try {
                    Sound sound = Sound.valueOf(
                            soundName.toUpperCase(Locale.ROOT)
                    );

                    player.playSound(
                            player.getLocation(),
                            sound,
                            1.0f,
                            1.0f
                    );
                } catch (IllegalArgumentException exception) {
                    player.sendMessage(
                            Component.text(
                                    "Не вдалося відтворити цей звук.",
                                    NamedTextColor.RED
                            )
                    );
                }

                return;
            }

            if (click.isRightClick()) {
                setAnswerSound(
                        player,
                        holder.dialogueId,
                        holder.nodeId,
                        holder.answerId,
                        soundName
                );
                return;
            }
        }
    }

    private void setAnswerSound(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId,
            String soundName
    ) {
        DialogueCore.Dialogue dialogue =
                core.getDialogue(dialogueId);

        if (dialogue == null) {
            openMainMenu(player, 0);
            return;
        }

        DialogueCore.Node node = dialogue.getNode(nodeId);

        if (node == null) {
            openDialogueMenu(player, dialogueId, 0);
            return;
        }

        DialogueCore.Answer answer = findAnswer(node, answerId);

        if (answer == null) {
            openNodeMenu(player, dialogueId, nodeId);
            return;
        }

        answer.setSound(soundName);
        core.saveDialogue(dialogueId);

        player.sendMessage(
                Component.text(
                        soundName == null
                                ? "Звук відповіді прибрано."
                                : "Звук відповіді збережено: " + soundName,
                        NamedTextColor.GREEN
                )
        );

        openAnswerMenu(
                player,
                dialogueId,
                nodeId,
                answerId
        );
    }

    private void handleSettingsMenuClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        switch (slot) {
            case 10 -> saveCameraLocationFromPlayer(
                    player,
                    holder.dialogueId
            );

            case 12 -> requestTextLocation(
                    player,
                    holder.dialogueId
            );

            case 14 -> requestBackgroundColor(
                    player,
                    holder.dialogueId
            );

            case 16 -> requestTextColor(
                    player,
                    holder.dialogueId
            );

            case 28 -> requestTypingSpeed(
                    player,
                    holder.dialogueId
            );

            case 30 -> requestTypingSound(
                    player,
                    holder.dialogueId
            );

            case 32 -> toggleSeeThroughBlocks(
                    player,
                    holder.dialogueId
            );

            case 34 -> openAdvancedSettings(
                    player,
                    holder.dialogueId
            );

            case 45 -> openDialogueMenu(
                    player,
                    holder.dialogueId,
                    0
            );

            default -> {
            }
        }
    }

    private void saveCameraLocationFromPlayer(
            Player player,
            String dialogueId
    ) {
        boolean saved = core.setCameraLocation(
                dialogueId,
                player.getLocation().clone()
        );

        if (saved) {
            player.sendMessage(
                    Component.text(
                            "Позицію камери збережено.",
                            NamedTextColor.GREEN
                    )
            );
        } else {
            player.sendMessage(
                    Component.text(
                            "Не вдалося зберегти позицію камери.",
                            NamedTextColor.RED
                    )
            );
        }

        openSettingsMenu(player, dialogueId);
    }

    private void requestTextLocation(
            Player player,
            String dialogueId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            String[] parts = value.trim().split("\\s+");

                            if (parts.length != 3) {
                                player.sendMessage(
                                        Component.text(
                                                "Потрібно ввести рівно три числа: X Y Z.",
                                                NamedTextColor.RED
                                        )
                                );
                                openSettingsMenu(player, dialogueId);
                                return;
                            }

                            try {
                                double x = Double.parseDouble(parts[0]);
                                double y = Double.parseDouble(parts[1]);
                                double z = Double.parseDouble(parts[2]);

                                if (!Double.isFinite(x)
                                        || !Double.isFinite(y)
                                        || !Double.isFinite(z)) {
                                    throw new NumberFormatException();
                                }

                                player.sendMessage(
                                        Component.text(
                                                "Координати прийнято: "
                                                        + x + " " + y + " " + z
                                                        + ". Збереження буде підключено "
                                                        + "після узгодження API налаштувань.",
                                                NamedTextColor.YELLOW
                                        )
                                );
                            } catch (NumberFormatException exception) {
                                player.sendMessage(
                                        Component.text(
                                                "Некоректні координати. "
                                                        + "Приклад: 10.5 72 -30.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openSettingsMenu(player, dialogueId);
                        },
                        "Введи координати тексту у форматі X Y Z."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи три координати тексту через пробіл: X Y Z.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestBackgroundColor(
            Player player,
            String dialogueId
    ) {
        requestColorValue(
                player,
                dialogueId,
                "фону",
                "background"
        );
    }

    private void requestTextColor(
            Player player,
            String dialogueId
    ) {
        requestColorValue(
                player,
                dialogueId,
                "тексту",
                "text-color"
        );
    }

    private void requestColorValue(
            Player player,
            String dialogueId,
            String displayName,
            String settingKey
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            String color = value.trim();

                            if (!color.matches(
                                    "#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"
                            )
                                    && !color.equalsIgnoreCase("none")
                                    && !color.matches("[a-zA-Z_-]+")) {
                                player.sendMessage(
                                        Component.text(
                                                "Некоректний колір. "
                                                        + "Використовуй HEX, назву кольору "
                                                        + "або none для фону.",
                                                NamedTextColor.RED
                                        )
                                );
                            } else {
                                player.sendMessage(
                                        Component.text(
                                                "Колір " + displayName
                                                        + " прийнято. "
                                                        + "Збереження параметра "
                                                        + "підключимо після звірки Settings API.",
                                                NamedTextColor.YELLOW
                                        )
                                );
                            }

                            openSettingsMenu(player, dialogueId);
                        },
                        "Введи колір " + displayName
                                + " у форматі HEX або назву кольору."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи колір " + displayName
                                + ". Наприклад: #FFFFFF.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestTypingSpeed(
            Player player,
            String dialogueId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            try {
                                int speed = Integer.parseInt(value.trim());

                                if (speed < 0 || speed > 1000) {
                                    player.sendMessage(
                                            Component.text(
                                                    "Швидкість має бути від 0 до 1000 мс.",
                                                    NamedTextColor.RED
                                            )
                                    );
                                } else {
                                    player.sendMessage(
                                            Component.text(
                                                    "Значення швидкості прийнято: "
                                                            + speed + " мс.",
                                                    NamedTextColor.YELLOW
                                            )
                                    );
                                }
                            } catch (NumberFormatException exception) {
                                player.sendMessage(
                                        Component.text(
                                                "Введи ціле число.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openSettingsMenu(player, dialogueId);
                        },
                        "Введи затримку між символами в мілісекундах."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи затримку між символами. "
                                + "0 — миттєвий текст, 50 — повільніше.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestTypingSound(
            Player player,
            String dialogueId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            String soundName = value.trim();

                            if (soundName.equalsIgnoreCase("none")) {
                                player.sendMessage(
                                        Component.text(
                                                "Звук друку вимкнено. "
                                                        + "Збереження параметра "
                                                        + "підключимо після звірки Settings API.",
                                                NamedTextColor.YELLOW
                                        )
                                );
                                openSettingsMenu(player, dialogueId);
                                return;
                            }

                            try {
                                Sound.valueOf(
                                        soundName.toUpperCase(Locale.ROOT)
                                );

                                player.sendMessage(
                                        Component.text(
                                                "Звук перевірено: "
                                                        + soundName
                                                        + ". Збереження підключимо "
                                                        + "після звірки Settings API.",
                                                NamedTextColor.YELLOW
                                        )
                                );
                            } catch (IllegalArgumentException exception) {
                                player.sendMessage(
                                        Component.text(
                                                "Такого звуку немає в переліку Minecraft.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openSettingsMenu(player, dialogueId);
                        },
                        "Введи назву звуку або none."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи назву звуку Minecraft. "
                                + "Наприклад: block_note_block_harp. "
                                + "Введи none, щоб вимкнути звук.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void toggleSeeThroughBlocks(
            Player player,
            String dialogueId
    ) {
        player.sendMessage(
                Component.text(
                        "Перемикання видимості крізь блоки "
                                + "буде підключено після звірки Settings API.",
                        NamedTextColor.YELLOW
                )
        );

        openSettingsMenu(player, dialogueId);
    }

    private void openAdvancedSettings(
            Player player,
            String dialogueId
    ) {
        EditorHolder holder = new EditorHolder(
                Screen.SETTINGS,
                dialogueId,
                null,
                null,
                1,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Додаткові налаштування")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        inventory.setItem(
                10,
                createItem(
                        Material.PLAYER_HEAD,
                        "§eПриховування інших гравців",
                        List.of(
                                "§7Під час діалогу інші гравці",
                                "§7можуть бути приховані.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                12,
                createItem(
                        Material.CLOCK,
                        "§eЗатримка перед продовженням",
                        List.of(
                                "§7Час очікування перед",
                                "§7наступним етапом діалогу.",
                                "",
                                "§aНатисни, щоб змінити"
                        )
                )
        );

        inventory.setItem(
                14,
                createItem(
                        Material.REPEATER,
                        "§eПовторне відтворення",
                        List.of(
                                "§7Дозволяє повторно",
                                "§7запускати цей діалог.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                16,
                createItem(
                        Material.COMMAND_BLOCK,
                        "§eКоманди запуску",
                        List.of(
                                "§7Команди при початку діалогу.",
                                "",
                                "§aНатисни, щоб редагувати"
                        )
                )
        );

        inventory.setItem(
                28,
                createItem(
                        Material.COMMAND_BLOCK,
                        "§eКоманди завершення",
                        List.of(
                                "§7Команди після завершення",
                                "§7діалогу.",
                                "",
                                "§aНатисни, щоб редагувати"
                        )
                )
        );

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до налаштувань")
                )
        );

        player.openInventory(inventory);
    }

    private void handleAdvancedSettingsClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        switch (slot) {
            case 10 -> player.sendMessage(
                    Component.text(
                            "Налаштування приховування гравців "
                                    + "буде підключено після звірки Settings API.",
                            NamedTextColor.YELLOW
                    )
            );

            case 12 -> requestAdvancedNumber(
                    player,
                    holder.dialogueId,
                    "Затримка перед продовженням"
            );

            case 14 -> player.sendMessage(
                    Component.text(
                            "Налаштування повторного відтворення "
                                    + "буде підключено після звірки Settings API.",
                            NamedTextColor.YELLOW
                    )
            );

            case 16 -> requestDialogueCommands(
                    player,
                    holder.dialogueId,
                    "entry-commands"
            );

            case 28 -> requestDialogueCommands(
                    player,
                    holder.dialogueId,
                    "completion-commands"
            );

            case 45 -> openSettingsMenu(
                    player,
                    holder.dialogueId
            );

            default -> {
            }
        }

        if (slot == 10 || slot == 14) {
            openAdvancedSettings(player, holder.dialogueId);
        }
    }

    private void requestAdvancedNumber(
            Player player,
            String dialogueId,
            String description
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            try {
                                long number = Long.parseLong(value.trim());

                                if (number < 0 || number > 600000) {
                                    player.sendMessage(
                                            Component.text(
                                                    "Значення має бути від 0 до 600000.",
                                                    NamedTextColor.RED
                                            )
                                    );
                                } else {
                                    player.sendMessage(
                                            Component.text(
                                                    description + ": "
                                                            + number + " мс. "
                                                            + "Збереження підключимо "
                                                            + "після звірки Settings API.",
                                                    NamedTextColor.YELLOW
                                            )
                                    );
                                }
                            } catch (NumberFormatException exception) {
                                player.sendMessage(
                                        Component.text(
                                                "Введи ціле число.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openAdvancedSettings(player, dialogueId);
                        },
                        "Введи значення в мілісекундах."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи значення для параметра «"
                                + description + "» у мілісекундах.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestDialogueCommands(
            Player player,
            String dialogueId,
            String settingKey
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            List<String> commands = new ArrayList<>();

                            if (!value.equalsIgnoreCase("none")) {
                                for (String command : value.split("\\|")) {
                                    String trimmed = command.trim();

                                    if (!trimmed.isEmpty()) {
                                        commands.add(trimmed);
                                    }
                                }
                            }

                            player.sendMessage(
                                    Component.text(
                                            "Отримано команд: "
                                                    + commands.size()
                                                    + ". Збереження підключимо "
                                                    + "після звірки Settings API.",
                                            NamedTextColor.YELLOW
                                    )
                            );

                            openAdvancedSettings(player, dialogueId);
                        },
                        "Введи команди через | або none."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи команди через |. "
                                + "Введи none, щоб очистити список.",
                        NamedTextColor.YELLOW
                )
        );
  }

    private void openConditionEditor(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        EditorHolder holder = new EditorHolder(
                Screen.CONDITION_EDITOR,
                dialogueId,
                nodeId,
                answerId,
                0,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Умови відповіді")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        inventory.setItem(
                10,
                createItem(
                        Material.BOOK,
                        "§eУмова виконання діалогу",
                        List.of(
                                "§7Перевіряє, чи завершив",
                                "§7гравець інший діалог.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                12,
                createItem(
                        Material.PAPER,
                        "§eУмова виконання гілки",
                        List.of(
                                "§7Перевіряє проходження",
                                "§7певної гілки діалогу.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                14,
                createItem(
                        Material.NAME_TAG,
                        "§eВибрана відповідь",
                        List.of(
                                "§7Перевіряє, чи вибирав",
                                "§7гравець певну відповідь.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                16,
                createItem(
                        Material.IRON_DOOR,
                        "§eДозвіл LuckPerms",
                        List.of(
                                "§7Перевіряє наявність",
                                "§7певного permission.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                28,
                createItem(
                        Material.NAME_TAG,
                        "§eТег гравця",
                        List.of(
                                "§7Перевіряє тег гравця.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                30,
                createItem(
                        Material.CHEST,
                        "§eПредмет у гравця",
                        List.of(
                                "§7Перевіряє наявність",
                                "§7потрібного предмета.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                32,
                createItem(
                        Material.REPEATER,
                        "§eЛогіка умов",
                        List.of(
                                "§7AND — усі умови мають",
                                "§7бути виконані.",
                                "§7OR — достатньо однієї.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до відповіді")
                )
        );

        player.openInventory(inventory);
    }

    private void openRewardEditor(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        EditorHolder holder = new EditorHolder(
                Screen.REWARD_EDITOR,
                dialogueId,
                nodeId,
                answerId,
                0,
                new ArrayList<>()
        );

        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Нагороди відповіді")
        );

        holder.inventory = inventory;
        fillBackground(inventory);

        inventory.setItem(
                11,
                createItem(
                        Material.EMERALD,
                        "§eГрошова нагорода",
                        List.of(
                                "§7Видає гравцеві гроші.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                13,
                createItem(
                        Material.EXPERIENCE_BOTTLE,
                        "§eДосвід",
                        List.of(
                                "§7Видає рівні або очки досвіду.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                15,
                createItem(
                        Material.CHEST,
                        "§eПредмет",
                        List.of(
                                "§7Видає предмет гравцеві.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                29,
                createItem(
                        Material.COMMAND_BLOCK,
                        "§eКоманда",
                        List.of(
                                "§7Виконує команду",
                                "§7від імені консолі.",
                                "",
                                "§aНатисни, щоб налаштувати"
                        )
                )
        );

        inventory.setItem(
                31,
                createItem(
                        Material.BARRIER,
                        "§cОчистити нагороди",
                        List.of(
                                "§7Прибирає всі нагороди",
                                "§7цієї відповіді."
                        )
                )
        );

        inventory.setItem(
                45,
                createItem(
                        Material.ARROW,
                        "§cНазад",
                        List.of("§7Повернутися до відповіді")
                )
        );

        player.openInventory(inventory);
    }

    private void handleConditionEditorClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == 45) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        String conditionType = switch (slot) {
            case 10 -> "completed_dialogue";
            case 12 -> "completed_branch";
            case 14 -> "selected_answer";
            case 16 -> "permission";
            case 28 -> "player_tag";
            case 30 -> "required_item";
            case 32 -> "logic";
            default -> null;
        };

        if (conditionType == null) {
            return;
        }

        player.sendMessage(
                Component.text(
                        "Вибрано тип умови: " + conditionType
                                + ". Збереження та редагування умови "
                                + "підключимо після узгодження моделі Condition.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void handleRewardEditorClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == 45) {
            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
            return;
        }

        String rewardType = switch (slot) {
            case 11 -> "money";
            case 13 -> "experience";
            case 15 -> "item";
            case 29 -> "command";
            case 31 -> "clear";
            default -> null;
        };

        if (rewardType == null) {
            return;
        }

        player.sendMessage(
                Component.text(
                        "Вибрано тип нагороди: " + rewardType
                                + ". Збереження нагород підключимо "
                                + "після звірки моделі Reward.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void handleAnswerListClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == 45) {
            openNodeMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
            return;
        }

        if (slot == 48) {
            requestNewAnswer(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
            return;
        }

        if (slot == 51 && holder.page > 0) {
            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page - 1
            );
            return;
        }

        if (slot == 53) {
            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.page + 1
            );
            return;
        }

        int[] slots = createEntrySlots();

        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != slot || i >= holder.entries.size()) {
                continue;
            }

            String answerId = holder.entries.get(i);

            openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    answerId
            );
            return;
        }
    }

    private void handleNodeAnswerListEntry(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (slot == 31) {
            openAnswerList(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    0
            );
            return;
        }

        if (slot == 29) {
            requestNewAnswer(
                    player,
                    holder.dialogueId,
                    holder.nodeId
            );
        }
    }

    private void handleSettingsScreenClick(
            Player player,
            EditorHolder holder,
            int slot
    ) {
        if (holder.page == 1) {
            handleAdvancedSettingsClick(
                    player,
                    holder,
                    slot
            );
            return;
        }

        handleSettingsMenuClick(
                player,
                holder,
                slot
        );
    }

    private void returnToCurrentScreen(
            Player player,
            EditorHolder holder
    ) {
        switch (holder.screen) {
            case MAIN -> openMainMenu(
                    player,
                    holder.page
            );

            case DIALOGUE -> openDialogueMenu(
                    player,
                    holder.dialogueId,
                    holder.page
            );

            case NODE -> {
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
                            holder.page
                    );
                }
            }

            case ANSWER -> openAnswerMenu(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case SETTINGS -> {
                if (holder.page == 1) {
                    openAdvancedSettings(
                            player,
                            holder.dialogueId
                    );
                } else {
                    openSettingsMenu(
                            player,
                            holder.dialogueId
                    );
                }
            }

            case SOUND_PICKER -> openSoundPicker(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId,
                    holder.page
            );

            case CONDITION_EDITOR -> openConditionEditor(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );

            case REWARD_EDITOR -> openRewardEditor(
                    player,
                    holder.dialogueId,
                    holder.nodeId,
                    holder.answerId
            );
        }
    }

    private void requestAnswerDelete(
            Player player,
            String dialogueId,
            String nodeId,
            String answerId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            if (!value.equalsIgnoreCase("DELETE")) {
                                player.sendMessage(
                                        Component.text(
                                                "Видалення відповіді скасовано.",
                                                NamedTextColor.YELLOW
                                        )
                                );

                                openAnswerMenu(
                                        player,
                                        dialogueId,
                                        nodeId,
                                        answerId
                                );
                                return;
                            }

                            DialogueCore.Dialogue dialogue =
                                    core.getDialogue(dialogueId);

                            if (dialogue == null) {
                                openMainMenu(player, 0);
                                return;
                            }

                            DialogueCore.Node node =
                                    dialogue.getNode(nodeId);

                            if (node == null) {
                                openDialogueMenu(
                                        player,
                                        dialogueId,
                                        0
                                );
                                return;
                            }

                            boolean removed = node.getAnswers().removeIf(
                                    answer -> answer.getId()
                                            .equalsIgnoreCase(answerId)
                            );

                            if (removed) {
                                core.saveDialogue(dialogueId);

                                player.sendMessage(
                                        Component.text(
                                                "Відповідь видалено.",
                                                NamedTextColor.GREEN
                                        )
                                );
                            } else {
                                player.sendMessage(
                                        Component.text(
                                                "Не вдалося знайти відповідь.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openNodeMenu(
                                    player,
                                    dialogueId,
                                    nodeId
                            );
                        },
                        "Введи DELETE для підтвердження видалення."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи DELETE, щоб видалити відповідь. "
                                + "Будь-який інший текст скасує операцію.",
                        NamedTextColor.YELLOW
                )
        );
    }

    private void requestDialogueDelete(
            Player player,
            String dialogueId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            if (!value.equalsIgnoreCase("DELETE")) {
                                player.sendMessage(
                                        Component.text(
                                                "Видалення діалогу скасовано.",
                                                NamedTextColor.YELLOW
                                        )
                                );

                                openDialogueMenu(
                                        player,
                                        dialogueId,
                                        0
                                );
                                return;
                            }

                            boolean deleted =
                                    core.deleteDialogue(dialogueId);

                            if (deleted) {
                                player.sendMessage(
                                        Component.text(
                                                "Діалог видалено.",
                                                NamedTextColor.GREEN
                                        )
                                );
                            } else {
                                player.sendMessage(
                                        Component.text(
                                                "Не вдалося видалити діалог.",
                                                NamedTextColor.RED
                                        )
                                );
                            }

                            openMainMenu(player, 0);
                        },
                        "Введи DELETE для підтвердження видалення діалогу."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи DELETE, щоб остаточно видалити діалог "
                                + dialogueId + ".",
                        NamedTextColor.RED
                )
        );
    }

    private void requestDialogueRename(
            Player player,
            String dialogueId
    ) {
        chatPrompts.put(
                player.getUniqueId(),
                new ChatPrompt(
                        value -> {
                            String newName = value.trim();

                            if (newName.isBlank()) {
                                player.sendMessage(
                                        Component.text(
                                                "Назва не може бути порожньою.",
                                                NamedTextColor.RED
                                        )
                                );
                                openDialogueMenu(
                                        player,
                                        dialogueId,
                                        0
                                );
                                return;
                            }

                            player.sendMessage(
                                    Component.text(
                                            "Нову назву прийнято: "
                                                    + newName
                                                    + ". Підключення перейменування "
                                                    + "потребує методу моделі DialogueCore.",
                                            NamedTextColor.YELLOW
                                    )
                            );

                            openDialogueMenu(
                                    player,
                                    dialogueId,
                                    0
                            );
                        },
                        "Введи нову назву діалогу."
                )
        );

        player.closeInventory();

        player.sendMessage(
                Component.text(
                        "Введи нову назву діалогу.",
                        NamedTextColor.YELLOW
                )
        );
    }

    @EventHandler
    public void onEditorInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory =
                event.getView().getTopInventory();

        if (topInventory.getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEditorPlayerQuit(
            org.bukkit.event.player.PlayerQuitEvent event
    ) {
        UUID playerId = event.getPlayer().getUniqueId();

        chatPrompts.remove(playerId);
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

    private void reopenEditor(
            Player player,
            EditorHolder holder
    ) {
        if (holder == null) {
            openMainMenu(player, 0);
            return;
        }

        switch (holder.screen) {
            case MAIN ->
                    openMainMenu(player, holder.page);

            case DIALOGUE ->
                    openDialogueMenu(
                            player,
                            holder.dialogueId,
                            holder.page
                    );

            case NODE ->
                    openNodeMenu(
                            player,
                            holder.dialogueId,
                            holder.nodeId
                    );

            case ANSWER ->
                    openAnswerMenu(
                            player,
                            holder.dialogueId,
                            holder.nodeId,
                            holder.answerId
                    );

            case SETTINGS ->
                    openSettingsMenu(
                            player,
                            holder.dialogueId
                    );

            case SOUND_PICKER ->
                    openSoundPicker(
                            player,
                            holder.dialogueId,
                            holder.nodeId,
                            holder.answerId,
                            holder.page
                    );

            case NODES ->
                    openDialogueMenu(
                            player,
                            holder.dialogueId,
                            holder.page
                    );

            case CONDITION_EDITOR, REWARD_EDITOR ->
                    openNodeMenu(
                            player,
                            holder.dialogueId,
                            holder.nodeId
                    );
        }
    }

    private boolean isValidId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        return value.matches("[a-zA-Z0-9_-]{1,48}");
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }

        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidHexColor(String value) {
        if (value == null) {
            return false;
        }

        String color = value.trim();

        if (color.startsWith("#")) {
            color = color.substring(1);
        }

        return color.matches("(?i)[0-9a-f]{6}");
    }

    private boolean isValidNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            double number = Double.parseDouble(value.trim());
            return Double.isFinite(number);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isValidInteger(
            String value,
            int minimum,
            int maximum
    ) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            int number = Integer.parseInt(value.trim());

            return number >= minimum
                    && number <= maximum;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String normalizeColor(String value) {
        if (value == null) {
            return "";
        }

        String color = value.trim();

        if (color.startsWith("#")) {
            color = color.substring(1);
        }

        if (color.matches("(?i)[0-9a-f]{6}")) {
            return "#" + color.toUpperCase(Locale.ROOT);
        }

        return value.trim();
    }

    private void sendError(Player player, String message) {
        player.sendMessage(
                Component.text(
                        message,
                        NamedTextColor.RED
                )
        );
    }

    private void sendSuccess(Player player, String message) {
        player.sendMessage(
                Component.text(
                        message,
                        NamedTextColor.GREEN
                )
        );
    }

    private void sendInfo(Player player, String message) {
        player.sendMessage(
                Component.text(
                        message,
                        NamedTextColor.YELLOW
                )
        );
    }
}
  
