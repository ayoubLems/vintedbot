package com.example.vintedbot.bot;

// Traduit depuis le russe par Ayoub Lemsoudi.

import com.example.vintedbot.config.BotProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.SendTarget;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.model.SubscriptionLevel;
import com.example.vintedbot.model.User;
import com.example.vintedbot.model.WatchedItem;
import com.example.vintedbot.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class VintedTelegramBot extends TelegramLongPollingBot {

    private static final int MAX_LINKS_PER_MESSAGE = 10;
    private static final int MAX_CATALOGS_PER_MESSAGE = 3;
    private static final int CATALOG_PREVIEW_COUNT = 3;
    private static final int WATCH_PAGE_SIZE = 5;
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?vinted\\.[a-z.]+/\\S+", Pattern.CASE_INSENSITIVE);

    // Reply-keyboard button captions (private chats) mapped to commands.
    private static final String BTN_ADD = "➕ Ajouter";
    private static final String BTN_SUBS = "🔔 Mes abonnements";
    private static final String BTN_LIST = "⭐ Mes favoris";
    private static final String BTN_HISTORY = "📜 Historique";
    private static final String BTN_STATS = "📊 Statistiques";
    private static final String BTN_HELP = "ℹ️ Aide";

    private final BotProperties botProperties;
    private final UserService userService;
    private final RateLimitService rateLimitService;
    private final VintedParserService parserService;
    private final VintedApiClient apiClient;
    private final HistoryService historyService;
    private final WatchlistService watchlistService;
    private final SearchSubscriptionService subscriptionService;
    private final SearchMonitorService monitorService;
    private final MessageFormatter formatter;

    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Type type, Long targetId) {
        enum Type { EDIT_WATCH_LABEL, EDIT_SUB_LABEL, ADD_SEARCH, ADD_ITEM }
    }

    /** Context of an incoming message: where and from whom. */
    private record Ctx(Long chatId, Long threadId, boolean forum, boolean group,
                       String chatTitle, String username, User user) {
        SendTarget reply() {
            return new SendTarget(chatId, threadId);
        }
    }

    public VintedTelegramBot(BotProperties botProperties,
                             UserService userService,
                             RateLimitService rateLimitService,
                             VintedParserService parserService,
                             VintedApiClient apiClient,
                             HistoryService historyService,
                             WatchlistService watchlistService,
                             SearchSubscriptionService subscriptionService,
                             SearchMonitorService monitorService,
                             MessageFormatter formatter) {
        super(botProperties.getToken());
        this.botProperties = botProperties;
        this.userService = userService;
        this.rateLimitService = rateLimitService;
        this.parserService = parserService;
        this.apiClient = apiClient;
        this.historyService = historyService;
        this.watchlistService = watchlistService;
        this.subscriptionService = subscriptionService;
        this.monitorService = monitorService;
        this.formatter = formatter;
        monitorService.setNotifier(this::sendExternal);
    }

    /** Delivery channel the monitor uses to push into a chat/topic. */
    public void sendExternal(SendTarget target, String html) {
        send(target, html);
    }

    @Override
    public String getBotUsername() {
        return botProperties.getUsername();
    }

    /** Registers the "/" command menu shown in the Telegram UI. */
    public void registerCommands() {
        List<BotCommand> cmds = List.of(
                new BotCommand("start", "Démarrer et afficher le menu"),
                new BotCommand("add", "➕ Ajouter une recherche ou une annonce"),
                new BotCommand("subs", "🔔 Mes abonnements de recherche"),
                new BotCommand("list", "⭐ Mes annonces enregistrées"),
                new BotCommand("history", "📜 Historique des analyses"),
                new BotCommand("stats", "📊 Statistiques"),
                new BotCommand("setup", "👥 Configuration dans un groupe"),
                new BotCommand("clear_history", "🗑 Effacer l’historique"),
                new BotCommand("help", "ℹ️ Aide"));
        try {
            execute(SetMyCommands.builder().commands(cmds).build());
            log.info("Bot command menu registered ({} commands)", cmds.size());
        } catch (Exception e) {
            log.warn("Failed to set command menu: {}", e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMyChatMember()) {
                handleMyChatMember(update.getMyChatMember());
                return;
            }
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleText(buildCtx(update.getMessage()), update.getMessage().getText().trim());
            }
        } catch (Exception e) {
            log.error("Unhandled error processing update", e);
        }
    }

    /** Posts a setup guide when the bot is added to a group. */
    private void handleMyChatMember(ChatMemberUpdated upd) {
        Chat chat = upd.getChat();
        String type = chat.getType() == null ? "" : chat.getType();
        boolean group = "group".equals(type) || "supergroup".equals(type);
        if (!group || upd.getNewChatMember() == null) return;
        String status = upd.getNewChatMember().getStatus();
        if ("member".equals(status) || "administrator".equals(status)) {
            send(chat.getId(), groupSetupMessage(chat.getId().toString().startsWith("-100")
                    && Boolean.TRUE.equals(chat.getIsForum())));
        }
    }

    private Ctx buildCtx(Message m) {
        Chat chat = m.getChat();
        String type = chat.getType() == null ? "private" : chat.getType();
        boolean group = "group".equals(type) || "supergroup".equals(type);
        String username = m.getFrom() != null ? m.getFrom().getUserName() : null;
        User user = userService.registerOrGet(chat.getId(), username);
        Long threadId = m.getMessageThreadId() == null ? null : m.getMessageThreadId().longValue();
        return new Ctx(chat.getId(), threadId, Boolean.TRUE.equals(chat.getIsForum()),
                group, chat.getTitle(), username, user);
    }

    // ------------------------------------------------------------- text flow

    private void handleText(Ctx ctx, String rawText) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        String text = mapMenuButton(rawText);

        Pending p = pending.get(chatId);
        if (p != null && !text.startsWith("/")) {
            pending.remove(chatId);
            consumePending(ctx, p, text);
            return;
        }
        if (text.startsWith("/")) {
            pending.remove(chatId);
        }

        if (text.startsWith("/start")) {
            sendMenu(ctx, welcomeMessage(ctx));
        } else if (text.startsWith("/help")) {
            sendMenu(ctx, helpMessage());
        } else if (text.startsWith("/add")) {
            sendAddMenu(chatId);
        } else if (text.startsWith("/setup")) {
            send(chatId, groupSetupMessage(ctx.forum()));
        } else if (text.startsWith("/search") || text.startsWith("/track")) {
            String rest = text.replaceFirst("^/\\w+(@\\S+)?", "").trim();
            if (rest.isBlank()) {
                pending.put(chatId, new Pending(Pending.Type.ADD_SEARCH, null));
                send(chatId, addSearchPrompt());
            } else {
                handleLinks(ctx, rest, false);
            }
        } else if (text.startsWith("/parse_link")) {
            handleLinks(ctx, stripCmd(text, "/parse_link"), false);
        } else if (text.startsWith("/watch")) {
            handleLinks(ctx, stripCmd(text, "/watch"), true);
        } else if (text.startsWith("/subs")) {
            sendSubsPage(chatId, user, 0);
        } else if (text.startsWith("/list")) {
            sendWatchlistPage(chatId, user, 0);
        } else if (text.startsWith("/history")) {
            sendHistoryPage(chatId, user, 0);
        } else if (text.startsWith("/clear_history")) {
            long removed = historyService.clear(user.getId());
            send(chatId, "🗑️ Historique effacé. Entrées supprimées : " + removed);
        } else if (text.startsWith("/stats")) {
            handleStats(chatId, user);
        } else if (URL_PATTERN.matcher(text).find()) {
            handleLinks(ctx, text, false);
        } else if (text.startsWith("/")) {
            send(chatId, "❓ Commande inconnue. Saisissez /help.");
        } else if (!ctx.group()) {
            send(ctx.reply(), "Envoyez un lien vers une annonce ou une recherche Vinted, "
                    + "ou appuyez sur «➕ Ajouter» dans le menu ci-dessous. /help", null);
        }
        // In groups: ignore free-form non-link chatter.
    }

    /** Maps reply-keyboard captions to their command equivalents. */
    private String mapMenuButton(String text) {
        return switch (text) {
            case BTN_ADD -> "/add";
            case BTN_SUBS -> "/subs";
            case BTN_LIST -> "/list";
            case BTN_HISTORY -> "/history";
            case BTN_STATS -> "/stats";
            case BTN_HELP -> "/help";
            default -> text;
        };
    }

    /** Strips a leading command token (and optional @botname) from the text. */
    private String stripCmd(String text, String cmd) {
        String rest = text.substring(cmd.length());
        // handle "/watch@botname args"
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            rest = sp < 0 ? "" : rest.substring(sp);
        }
        return rest;
    }

    private void consumePending(Ctx ctx, Pending p, String text) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        switch (p.type()) {
            case EDIT_WATCH_LABEL -> {
                boolean ok = watchlistService.updateLabel(user.getId(), p.targetId(), text.trim());
                send(chatId, ok ? "✅ Nom mis à jour." : "⚠️ Entrée introuvable.");
                if (ok) sendWatchlistPage(chatId, user, 0);
            }
            case EDIT_SUB_LABEL -> {
                boolean ok = subscriptionService.updateLabel(user.getId(), p.targetId(), text.trim());
                send(chatId, ok ? "✅ Nom de l’abonnement mis à jour." : "⚠️ Abonnement introuvable.");
                if (ok) sendSubsPage(chatId, user, 0);
            }
            case ADD_SEARCH -> {
                if (!URL_PATTERN.matcher(text).find()) {
                    send(chatId, "🤔 Cela ne ressemble pas à un lien. Envoyez un lien de recherche Vinted "
                            + "(<code>…/catalog?...</code>) ou saisissez de nouveau /add.");
                } else {
                    handleLinks(ctx, text, false);
                }
            }
            case ADD_ITEM -> {
                if (!URL_PATTERN.matcher(text).find()) {
                    send(chatId, "🤔 Cela ne ressemble pas à un lien d’annonce. Envoyez "
                            + "<code>…/items/…</code> ou saisissez de nouveau /add.");
                } else {
                    handleLinks(ctx, text, false);
                }
            }
        }
    }

    // ------------------------------------------------------------- add menu

    private void sendAddMenu(Long chatId) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(
                List.of(button("🔔 Suivre une recherche", "add:search")),
                List.of(button("👗 Analyser une annonce", "add:item")),
                List.of(button("❓ Où trouver le lien ?", "add:help"))));
        send(chatId, """
                <b>Que souhaitez-vous ajouter ?</b>

                🔔 <b>Suivre une recherche</b> — envoyez le lien d’une recherche Vinted
                avec vos filtres. Je montrerai les résultats récents et enverrai les nouveaux.
                👗 <b>Analyser une annonce</b> — obtenir sa fiche à partir du lien.""",
                m);
    }

    private String addSearchPrompt() {
        return """
                🔔 D’accord ! Envoyez le lien de votre <b>recherche Vinted</b>.

                Pour l’obtenir : ouvrez Vinted → définissez vos filtres (recherche,
                tailles, marques) → triez par « Plus récent » → copiez le lien
                dans la barre d’adresse. Il ressemble à ceci :
                <code>https://www.vinted.de/catalog?search_text=nike&amp;order=newest_first</code>""";
    }

    private String addItemPrompt() {
        return "👗 Envoyez un lien d’annonce de la forme <code>…/items/123…</code> "
                + "(vous pouvez en envoyer plusieurs, un par ligne).";
    }

    // ---------------------------------------------------- parsing (batch)

    private void handleLinks(Ctx ctx, String payload, boolean addToWatch) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        List<String> all = extractUrls(payload);
        if (all.isEmpty()) {
            send(ctx.reply(), "⚠️ Aucun lien Vinted trouvé.\n"
                    + "Exemple d’annonce : <code>https://www.vinted.com/items/123456789</code>\n"
                    + "Exemple de recherche : <code>https://www.vinted.fr/catalog?search_text=…</code>", null);
            return;
        }

        List<String> catalogUrls = all.stream().filter(parserService::isCatalogUrl).toList();
        List<String> urls = all.stream().filter(u -> !parserService.isCatalogUrl(u)).toList();

        int catalogHandled = 0;
        for (String c : catalogUrls) {
            if (catalogHandled++ >= MAX_CATALOGS_PER_MESSAGE) {
                send(chatId, "⚠️ Pas plus de " + MAX_CATALOGS_PER_MESSAGE + " liens de recherche à la fois.");
                break;
            }
            handleCatalog(ctx, c);
        }
        if (urls.isEmpty()) return;

        if (urls.size() > MAX_LINKS_PER_MESSAGE) {
            send(chatId, "⚠️ Trop de liens à la fois (maximum " + MAX_LINKS_PER_MESSAGE
                    + "). Je traite les " + MAX_LINKS_PER_MESSAGE + " premiers.");
            urls = urls.subList(0, MAX_LINKS_PER_MESSAGE);
        }

        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        SendTarget reply = ctx.reply();
        if (urls.size() > 1) {
            send(reply, "🔎 Liens trouvés : " + urls.size() + ". Je les traite un par un…", null);
        } else {
            send(reply, "🔎 J’analyse l’annonce, cela prendra quelques secondes…", null);
        }

        int ok = 0, failed = 0, added = 0;
        for (String url : urls) {
            if (!rateLimitService.tryAcquire(user.getId(), level)) {
                send(reply, "🚦 Limite de requêtes atteinte (" + level + "). "
                        + "Les autres liens ont été ignorés. Réessayez plus tard.", null);
                break;
            }
            try {
                VintedItem item = parserService.parseVintedUrl(url);
                ParsedItem saved = historyService.save(user.getId(), item);
                if (addToWatch) {
                    WatchlistService.AddResult r =
                            watchlistService.add(user.getId(), item.getUrl(), null, item);
                    if (r == WatchlistService.AddResult.ADDED) added++;
                    send(reply, formatter.formatItem(item), saveOrManageKeyboard(saved.getId(), r));
                } else {
                    send(reply, formatter.formatItem(item), saveButton(saved.getId()));
                }
                ok++;
            } catch (VintedParseException e) {
                failed++;
                send(reply, userFacingError(e) + "\n<code>" + esc(url) + "</code>", null);
                log.warn("Parse failed for {} ({}): {}", url, e.getReason(), e.getMessage());
            } catch (Exception e) {
                failed++;
                send(reply, "❌ Impossible de traiter le lien :\n<code>" + esc(url) + "</code>", null);
                log.error("Unexpected parse error for {}", url, e);
            }
        }

        if (urls.size() > 1) {
            StringBuilder sb = new StringBuilder("📊 Terminé : ✅ " + ok + "  ❌ " + failed);
            if (addToWatch) sb.append("  ⭐ ajoutés aux favoris : ").append(added);
            send(reply, sb.toString(), null);
        }
    }

    // ------------------------------------------------- catalog subscriptions

    /**
     * Search/filter link flow. In a forum group we create a dedicated topic for
     * the search and deliver everything there; otherwise we deliver into the
     * current chat. Shows the freshest listings and subscribes the monitor.
     */
    private void handleCatalog(Ctx ctx, String rawUrl) {
        User user = ctx.user();
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            send(ctx.reply(), "🚦 Limite de requêtes atteinte. Réessayez plus tard.", null);
            return;
        }

        String url = VintedParserService.normalizeCatalogUrl(rawUrl);
        String label = labelFromCatalogUrl(url);

        // Decide the delivery target: a fresh forum topic, or the current chat.
        SendTarget target = ctx.reply();
        Long topicThreadId = null;
        if (ctx.group() && ctx.forum()) {
            Integer newThread = createForumTopic(ctx.chatId(), topicName(label));
            if (newThread != null) {
                topicThreadId = newThread.longValue();
                target = SendTarget.topic(ctx.chatId(), topicThreadId);
                send(ctx.reply(), "🧵 Sujet «" + esc(topicName(label))
                        + "» créé : les annonces récentes et nouvelles de cette recherche y seront publiées.", null);
            } else {
                send(ctx.reply(), "⚠️ Impossible de créer le sujet (l’autorisation « Gérer les sujets » est requise). "
                        + "Je publierai ici.", null);
            }
        } else if (ctx.group()) {
            send(ctx.reply(), "ℹ️ Conseil : activez les « Sujets » dans le groupe et autorisez-moi "
                    + "à les créer. Chaque recherche aura alors son propre sujet.", null);
        }

        send(target, "🔎 Je consulte la recherche «" + esc(label) + "»…", null);

        List<String> seedIds = new ArrayList<>();
        boolean previewSent = false;
        try {
            List<CatalogItemSummary> summaries = apiClient.fetchCatalog(url, 24);
            for (CatalogItemSummary s : summaries) {
                if (s.getId() != null) seedIds.add(s.getId());
            }
            if (summaries.isEmpty()) {
                send(target, "😕 Aucun résultat pour le moment. L’abonnement est créé et je vous préviendrai dès qu’une annonce apparaîtra.", null);
            } else {
                int n = Math.min(CATALOG_PREVIEW_COUNT, summaries.size());
                send(target, "🆕 Annonces récentes (" + n + " sur " + summaries.size() + ") :", null);
                for (int i = 0; i < n; i++) {
                    send(target, formatter.formatSummary(summaries.get(i)), null);
                }
            }
            previewSent = true;
        } catch (Exception apiFail) {
            log.info("Catalog API preview failed for {} ({}), falling back to HTML",
                    url, apiFail.getMessage());
        }

        if (!previewSent && !previewViaHtml(target, user, url, seedIds)) {
            return;
        }

        SearchSubscriptionService.NewSubscription ns = new SearchSubscriptionService.NewSubscription(
                user.getId(), url, label, ctx.chatId(), topicThreadId,
                ctx.group() ? ctx.chatTitle() : null, seedIds);
        switch (subscriptionService.create(ns)) {
            case ADDED -> send(target, "🔔 <b>Abonnement créé !</b> Je vous enverrai automatiquement "
                    + "les nouvelles annonces correspondant à cette recherche (généralement en quelques secondes).\n"
                    + "Gestion : /subs", null);
            case ALREADY_EXISTS -> send(target, "ℹ️ Vous suivez déjà cette recherche. /subs", null);
            case LIMIT_REACHED -> send(target, "⚠️ Limite d’abonnements atteinte ("
                    + SearchSubscriptionService.MAX_PER_USER + "). Supprimez-en dans /subs", null);
        }
    }

    /** HTML fallback preview; returns false if the page couldn't be read. */
    private boolean previewViaHtml(SendTarget target, User user, String url, List<String> seedIds) {
        List<String> itemUrls;
        try {
            itemUrls = parserService.fetchCatalogItemUrls(url);
        } catch (VintedParseException e) {
            send(target, userFacingError(e), null);
            return false;
        } catch (Exception e) {
            send(target, "❌ Impossible de lire la page de recherche. Réessayez plus tard.", null);
            log.error("Unexpected catalog error for {}", url, e);
            return false;
        }
        for (String u : itemUrls) {
            String id = VintedParserService.extractItemId(u);
            if (id != null) seedIds.add(id);
        }
        if (itemUrls.isEmpty()) {
            send(target, "😕 Aucun résultat pour le moment. L’abonnement est créé et je vous préviendrai dès qu’une annonce apparaîtra.", null);
        } else {
            int n = Math.min(CATALOG_PREVIEW_COUNT, itemUrls.size());
            send(target, "🆕 Annonces récentes (" + n + " sur " + itemUrls.size() + ") :", null);
            for (int i = 0; i < n; i++) {
                try {
                    VintedItem item = parserService.parseVintedUrl(itemUrls.get(i));
                    historyService.save(user.getId(), item);
                    send(target, formatter.formatItem(item), null);
                } catch (Exception e) {
                    log.warn("Catalog preview parse failed for {}: {}", itemUrls.get(i), e.getMessage());
                }
            }
        }
        return true;
    }

    /** Human label from the search_text query param, e.g. "Recherche : sweat". */
    public static String labelFromCatalogUrl(String url) {
        Matcher m = Pattern.compile("[?&]search_text=([^&]*)").matcher(url);
        if (m.find()) {
            String s = java.net.URLDecoder.decode(
                    m.group(1).replace("+", " "), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!s.isEmpty()) return "Recherche : " + s;
        }
        return "Recherche Vinted";
    }

    private String topicName(String label) {
        String n = "Vinted · " + label;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    /** Creates a forum topic; returns its thread id, or null on failure. */
    protected Integer createForumTopic(Long chatId, String name) {
        try {
            ForumTopic topic = execute(CreateForumTopic.builder()
                    .chatId(chatId.toString()).name(name).build());
            return topic.getMessageThreadId();
        } catch (Exception e) {
            log.warn("createForumTopic failed for chat {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    private void sendSubsPage(Long chatId, User user, int page) {
        List<SearchSubscription> all = subscriptionService.list(user.getId());
        if (all.isEmpty()) {
            send(chatId, "🔔 Vous n’avez aucun abonnement de recherche.\n"
                    + "Envoyez un lien de recherche Vinted avec vos filtres "
                    + "(<code>…/catalog?search_text=…&amp;order=newest_first</code>) — "
                    + "Je montrerai les annonces récentes et vous préviendrai des nouvelles.");
            return;
        }
        int totalPages = (int) Math.ceil(all.size() / (double) WATCH_PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * WATCH_PAGE_SIZE;
        int to = Math.min(from + WATCH_PAGE_SIZE, all.size());

        StringBuilder sb = new StringBuilder("🔔 <b>Abonnements de recherche</b> (")
                .append(all.size()).append(" au total, page ")
                .append(page + 1).append("/").append(totalPages).append(")\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            SearchSubscription s = all.get(i);
            sb.append(formatter.formatSubEntry(s, i + 1)).append("\n\n");
            String toggle = s.isActive() ? "⏸" : "▶️";
            rows.add(List.of(
                    button("🔄 #" + (i + 1), "sub:chk:" + s.getId()),
                    button(toggle, "sub:tgl:" + s.getId()),
                    button("✏️", "sub:edit:" + s.getId()),
                    button("🗑", "sub:del:" + s.getId())
            ));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(button("⬅️", "sub:list:" + (page - 1)));
        if (page < totalPages - 1) nav.add(button("➡️", "sub:list:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    // -------------------------------------------------------------- watchlist

    private void sendWatchlistPage(Long chatId, User user, int page) {
        List<WatchedItem> all = watchlistService.list(user.getId());
        if (all.isEmpty()) {
            send(chatId, "⭐ Votre liste de favoris est vide.\n"
                    + "Ajoutez des liens avec <code>/watch &lt;url&gt;</code> "
                    + "ou avec le bouton « Ajouter aux favoris » sous une annonce.");
            return;
        }
        int totalPages = (int) Math.ceil(all.size() / (double) WATCH_PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * WATCH_PAGE_SIZE;
        int to = Math.min(from + WATCH_PAGE_SIZE, all.size());

        StringBuilder sb = new StringBuilder("⭐ <b>Mes favoris</b> (")
                .append(all.size()).append(" au total, page ")
                .append(page + 1).append("/").append(totalPages).append(")\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            WatchedItem w = all.get(i);
            sb.append(formatter.formatWatchEntry(w, i + 1)).append("\n\n");
            rows.add(List.of(
                    button("🔄 #" + (i + 1), "wl:ref:" + w.getId()),
                    button("✏️", "wl:edit:" + w.getId()),
                    button("🗑", "wl:del:" + w.getId())
            ));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(button("⬅️", "wl:list:" + (page - 1)));
        if (page < totalPages - 1) nav.add(button("➡️", "wl:list:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    // -------------------------------------------------------------- history

    private void sendHistoryPage(Long chatId, User user, int page) {
        Page<ParsedItem> pageData = historyService.history(user.getId(), page);
        if (pageData.getTotalElements() == 0) {
            send(chatId, "📭 L’historique est vide. Envoyez un lien Vinted pour commencer.");
            return;
        }
        StringBuilder sb = new StringBuilder("📜 <b>Historique</b> (page ")
                .append(page + 1).append("/").append(pageData.getTotalPages()).append(")\n\n");
        int idx = page * HistoryService.PAGE_SIZE + 1;
        for (ParsedItem item : pageData.getContent()) {
            sb.append(formatter.formatHistoryEntry(item, idx++)).append("\n\n");
        }
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (page > 0) row.add(button("⬅️ Précédent", "hist:" + (page - 1)));
        if (page < pageData.getTotalPages() - 1) row.add(button("Suivant ➡️", "hist:" + (page + 1)));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(row.isEmpty() ? List.of() : List.of(row));
        send(chatId, sb.toString(), markup);
    }

    private void handleStats(Long chatId, User user) {
        long total = historyService.totalCount(user.getId());
        long watched = watchlistService.count(user.getId());
        long subs = subscriptionService.count(user.getId());
        OffsetDateTime earliest = historyService.earliest(user.getId());
        StringBuilder sb = new StringBuilder("📊 <b>Statistiques</b>\n\n");
        sb.append("Annonces analysées : <b>").append(total).append("</b>\n");
        sb.append("Abonnements de recherche : <b>").append(subs).append("</b>\n");
        sb.append("Annonces dans les favoris : <b>").append(watched).append("</b>\n");
        if (earliest != null) {
            long days = Math.max(1, ChronoUnit.DAYS.between(earliest.toInstant(), OffsetDateTime.now().toInstant()));
            sb.append("Période : <b>").append(days).append("</b> jour(s)\n");
            sb.append("Moyenne : <b>").append(String.format("%.1f", (double) total / days)).append("</b>/jour");
        } else {
            sb.append("\nVous n’avez encore analysé aucune annonce.");
        }
        send(chatId, sb.toString());
    }

    // ------------------------------------------------------------- callbacks

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        User user = userService.registerOrGet(chatId, cb.getFrom().getUserName());
        if (data == null) { ack(cb, null); return; }

        try {
            if (data.equals("add:search")) {
                pending.put(chatId, new Pending(Pending.Type.ADD_SEARCH, null));
                send(chatId, addSearchPrompt());
                ack(cb, null);
            } else if (data.equals("add:item")) {
                pending.put(chatId, new Pending(Pending.Type.ADD_ITEM, null));
                send(chatId, addItemPrompt());
                ack(cb, null);
            } else if (data.equals("add:help")) {
                send(chatId, whereToGetLinkMessage());
                ack(cb, null);
            } else if (data.startsWith("hist:")) {
                sendHistoryPage(chatId, user, Integer.parseInt(data.substring(5)));
                ack(cb, null);
            } else if (data.startsWith("wl:list:")) {
                sendWatchlistPage(chatId, user, Integer.parseInt(data.substring(8)));
                ack(cb, null);
            } else if (data.startsWith("save:")) {
                handleSave(user, Long.parseLong(data.substring(5)), cb);
            } else if (data.startsWith("wl:ref:")) {
                handleRefresh(chatId, user, Long.parseLong(data.substring(7)), cb);
            } else if (data.startsWith("wl:edit:")) {
                pending.put(chatId, new Pending(Pending.Type.EDIT_WATCH_LABEL, Long.parseLong(data.substring(8))));
                send(chatId, "✏️ Envoyez le nouveau nom de cette entrée (ou /list pour annuler).");
                ack(cb, null);
            } else if (data.startsWith("wl:del:")) {
                boolean ok = watchlistService.delete(user.getId(), Long.parseLong(data.substring(7)));
                ack(cb, ok ? "Supprimé" : "Introuvable");
                sendWatchlistPage(chatId, user, 0);
            } else if (data.startsWith("sub:list:")) {
                sendSubsPage(chatId, user, Integer.parseInt(data.substring(9)));
                ack(cb, null);
            } else if (data.startsWith("sub:chk:")) {
                handleSubCheckNow(chatId, user, Long.parseLong(data.substring(8)), cb);
            } else if (data.startsWith("sub:tgl:")) {
                Boolean active = subscriptionService.toggleActive(user.getId(), Long.parseLong(data.substring(8)));
                ack(cb, active == null ? "Introuvable" : (active ? "▶️ Repris" : "⏸ En pause"));
                sendSubsPage(chatId, user, 0);
            } else if (data.startsWith("sub:edit:")) {
                pending.put(chatId, new Pending(Pending.Type.EDIT_SUB_LABEL, Long.parseLong(data.substring(9))));
                send(chatId, "✏️ Envoyez le nouveau nom de l’abonnement (ou /subs pour annuler).");
                ack(cb, null);
            } else if (data.startsWith("sub:del:")) {
                boolean ok = subscriptionService.delete(user.getId(), Long.parseLong(data.substring(8)));
                ack(cb, ok ? "Abonnement supprimé" : "Introuvable");
                sendSubsPage(chatId, user, 0);
            } else {
                ack(cb, null);
            }
        } catch (Exception e) {
            log.error("Callback handling failed for data={}", data, e);
            ack(cb, "Erreur");
        }
    }

    private void handleSave(User user, Long parsedItemId, CallbackQuery cb) {
        historyService.get(user.getId(), parsedItemId).ifPresentOrElse(pi -> {
            VintedItem snap = VintedItem.builder()
                    .url(pi.getVintedUrl()).title(pi.getTitle())
                    .price(pi.getPrice()).currency(pi.getCurrency()).build();
            WatchlistService.AddResult r =
                    watchlistService.add(user.getId(), pi.getVintedUrl(), null, snap);
            ack(cb, switch (r) {
                case ADDED -> "⭐ Ajouté aux favoris";
                case ALREADY_EXISTS -> "Déjà dans les favoris";
                case LIMIT_REACHED -> "Liste de favoris pleine";
            });
        }, () -> ack(cb, "Entrée introuvable"));
    }

    private void handleSubCheckNow(Long chatId, User user, Long subId, CallbackQuery cb) {
        var opt = subscriptionService.get(user.getId(), subId);
        if (opt.isEmpty()) { ack(cb, "Introuvable"); return; }
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            ack(cb, "Limite de requêtes atteinte");
            return;
        }
        ack(cb, "Vérification…");
        try {
            int sent = monitorService.checkOneManually(opt.get());
            if (sent == 0) {
                send(chatId, "✅ Aucune nouvelle annonce pour cette recherche pour le moment.");
            }
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) {
                long seconds = monitorService.antiBotPauseRemainingSeconds();
                long minutes = Math.max(1, (seconds + 59) / 60);
                send(chatId, "🛡️ Vinted bloque temporairement les requêtes (protection anti-bot). "
                        + "La vérification est mise en pause pendant environ " + minutes
                        + " minute(s). N’appuyez pas de nouveau sur 🔄 pendant ce délai.");
            } else {
                send(chatId, userFacingError(e));
            }
            log.warn("Manual check rejected for sub {} ({}): {}", subId, e.getReason(), e.getMessage());
        } catch (Exception e) {
            send(chatId, "❌ Impossible de vérifier l’abonnement. Réessayez plus tard.");
            log.error("Manual check failed for sub {}", subId, e);
        }
    }

    private void handleRefresh(Long chatId, User user, Long watchId, CallbackQuery cb) {
        var opt = watchlistService.get(user.getId(), watchId);
        if (opt.isEmpty()) { ack(cb, "Introuvable"); return; }
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            ack(cb, "Limite de requêtes atteinte");
            return;
        }
        ack(cb, "Actualisation…");
        WatchedItem w = opt.get();
        try {
            VintedItem item = parserService.parseVintedUrl(w.getVintedUrl());
            historyService.save(user.getId(), item);
            watchlistService.applySnapshot(user.getId(), watchId, item);
            send(chatId, formatter.formatItem(item));
        } catch (VintedParseException e) {
            send(chatId, userFacingError(e));
        } catch (Exception e) {
            send(chatId, "❌ Impossible d’actualiser l’entrée.");
            log.error("Refresh failed for watchId={}", watchId, e);
        }
    }

    // ---------------------------------------------------------------- output

    private void send(Long chatId, String text) {
        send(SendTarget.chat(chatId), text, null);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup markup) {
        send(SendTarget.chat(chatId), text, markup);
    }

    private void send(SendTarget target, String text) {
        send(target, text, null);
    }

    private void send(SendTarget target, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(target.chatId().toString());
        if (target.messageThreadId() != null) {
            msg.setMessageThreadId(target.messageThreadId().intValue());
        }
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setDisableWebPagePreview(false);
        if (markup != null) msg.setReplyMarkup(markup);
        try {
            dispatch(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}: {}", target.chatId(), e.getMessage());
        }
    }

    /** Sends with the persistent reply-keyboard menu (private chats only). */
    private void sendMenu(Ctx ctx, String text) {
        if (ctx.group()) {
            send(ctx.reply(), text, null);
            return;
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(ctx.chatId().toString());
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setDisableWebPagePreview(true);
        msg.setReplyMarkup(mainMenuKeyboard());
        try {
            dispatch(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send menu to {}: {}", ctx.chatId(), e.getMessage());
        }
    }

    private ReplyKeyboardMarkup mainMenuKeyboard() {
        KeyboardRow r1 = new KeyboardRow();
        r1.add(new KeyboardButton(BTN_SUBS));
        r1.add(new KeyboardButton(BTN_LIST));
        KeyboardRow r2 = new KeyboardRow();
        r2.add(new KeyboardButton(BTN_HISTORY));
        r2.add(new KeyboardButton(BTN_STATS));
        KeyboardRow r3 = new KeyboardRow();
        r3.add(new KeyboardButton(BTN_ADD));
        r3.add(new KeyboardButton(BTN_HELP));
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setKeyboard(List.of(r1, r2, r3));
        kb.setResizeKeyboard(true);
        kb.setIsPersistent(true);
        return kb;
    }

    protected void dispatch(SendMessage msg) throws TelegramApiException {
        super.execute(msg);
    }

    protected void dispatch(AnswerCallbackQuery a) throws TelegramApiException {
        super.execute(a);
    }

    private void ack(CallbackQuery cb, String text) {
        AnswerCallbackQuery a = new AnswerCallbackQuery();
        a.setCallbackQueryId(cb.getId());
        if (text != null) a.setText(text);
        try {
            dispatch(a);
        } catch (TelegramApiException e) {
            log.debug("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    private InlineKeyboardMarkup saveButton(Long parsedItemId) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(List.of(button("⭐ Ajouter aux favoris", "save:" + parsedItemId))));
        return m;
    }

    private InlineKeyboardMarkup saveOrManageKeyboard(Long parsedItemId, WatchlistService.AddResult r) {
        String label = r == WatchlistService.AddResult.ADDED ? "✅ Dans les favoris" : "⭐ Ajouter aux favoris";
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(List.of(
                button(label, "save:" + parsedItemId),
                button("📋 Ouvrir les favoris", "wl:list:0"))));
        return m;
    }

    private InlineKeyboardButton button(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(data);
        return b;
    }

    // ----------------------------------------------------------------- utils

    /** Extracts distinct Vinted URLs from arbitrary text. */
    public static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) return urls;
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String u = m.group();
            if (!urls.contains(u)) urls.add(u);
        }
        return urls;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String userFacingError(VintedParseException e) {
        return switch (e.getReason()) {
            case INVALID_URL -> "⚠️ Cela ne ressemble pas à un lien d’annonce Vinted.";
            case NOT_FOUND -> "🔍 Annonce introuvable ou supprimée.";
            case BLOCKED -> "🛡️ Vinted a temporairement limité l’accès (contrôle anti-bot). "
                    + "Patientez un peu puis réessayez.";
            case TIMEOUT -> "⏱️ Délai d’attente dépassé. Réessayez.";
            default -> "❌ Impossible d’analyser l’annonce. Réessayez plus tard.";
        };
    }

    private String welcomeMessage(Ctx ctx) {
        if (ctx.group()) {
            return """
                    👋 <b>Bonjour ! Je surveille Vinted pour vous.</b>

                    Ajoutez une recherche avec <code>/search lien</code> ou envoyez
                    simplement le lien dans le chat (le mode confidentialité doit
                    être désactivé — voir /setup).

                    Si les <b>Sujets</b> sont activés et que je peux en créer,
                    chaque recherche aura son propre sujet 🧵.
                    Configuration : /setup · Abonnements : /subs · Aide : /help""";
        }
        return """
                👋 <b>Bonjour ! Je suis votre bot de recherche Vinted.</b>

                • Lien d’une <b>annonce</b> → fiche avec prix, taille et marque.
                • Lien d’une <b>recherche filtrée</b> → annonces récentes
                  + abonnement : j’envoie automatiquement les nouvelles 🔔.

                Appuyez sur <b>«➕ Ajouter»</b> dans le menu 👇 et suivez les étapes.
                Vous pouvez aussi envoyer directement un lien. Aide : /help""";
    }

    private String helpMessage() {
        return """
                ℹ️ <b>Comment utiliser le bot</b>

                <b>Le plus simple :</b> appuyez sur «➕ Ajouter» dans le menu et
                suivez les instructions. Vous pouvez aussi envoyer directement un lien.

                <b>🔔 Suivre une recherche (fonction principale).</b>
                Configurez vos filtres sur Vinted, triez par « Plus récent », copiez
                le lien <code>…/catalog?search_text=…</code> et envoyez-le ici.
                Je montrerai les résultats récents et vous enverrai les nouveaux.
                Gestion : /subs — 🔄 vérifier · ⏸/▶️ pause · ✏️ renommer · 🗑 supprimer.

                <b>👗 Annonce.</b> Un lien <code>…/items/123…</code> produit une fiche.
                Annonces enregistrées : /list.

                <b>👥 Dans les groupes.</b> Saisissez /setup dans le groupe pour
                recevoir les instructions (un sujet par recherche).

                <b>Commandes :</b> /add · /subs · /list · /history · /stats · /setup
                <b>Limites gratuites :</b> 10 requêtes/heure, jusqu’à """
                + SearchSubscriptionService.MAX_PER_USER + " abonnements.";
    }

    private String whereToGetLinkMessage() {
        return """
                ❓ <b>Où trouver le lien d’une recherche ?</b>

                <b>Dans l’application Vinted :</b>
                1. Ouvrez la recherche et choisissez vos filtres (marque, taille, prix…).
                2. Tri → « Plus récent ».
                3. Menu (⋯) → « Partager » / « Copier le lien ».

                <b>Dans le navigateur (plus simple) :</b> ouvrez Vinted → appliquez
                vos filtres → copiez l’adresse dans la barre d’adresse. Exemple :
                <code>https://www.vinted.fr/catalog?search_text=nike&amp;order=newest_first</code>

                Envoyez ensuite ce lien ici 👇""";
    }

    private String groupSetupMessage(boolean forum) {
        return """
                👥 <b>Configuration du bot dans un groupe</b>

                Pour que je fonctionne dans le groupe et crée un sujet par recherche :

                <b>1. Ajoutez-moi comme administrateur.</b> Paramètres du groupe →
                Administrateurs → ajouter @""" + esc(botProperties.getUsername()) + """
                . Activez l’autorisation <b>« Gérer les sujets » (Manage Topics)</b>.

                <b>2. Activez les « Sujets » (Topics)</b> dans les paramètres du groupe.
                Chaque recherche pourra alors avoir son propre sujet 🧵.

                <b>3. IMPORTANT — mode confidentialité.</b> Pour que je voie les liens
                envoyés dans le chat, le propriétaire du bot doit ouvrir @BotFather :
                <code>/setprivacy</code> → choisir le bot → <b>Disable</b>.
                Sinon, ajoutez la recherche avec <code>/search lien</code>
                (je reçois toujours les commandes).

                C’est prêt ! Envoyez dans le groupe un lien de recherche Vinted ou
                <code>/search lien</code>. Gestion : /subs""";
    }
}
