package com.example.vintedbot.service;

// Traduit depuis le russe par Ayoub Lemsoudi.

import com.example.vintedbot.config.MonitorProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.SendTarget;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Near-real-time monitor for saved catalog searches. Primary path polls
 * Vinted's lightweight JSON API (cards built directly from API data). Search
 * notifications deliberately never fall back to HTML: catalog HTML can mix
 * genuine results with promoted/recommended listings and has no trustworthy
 * publication timestamp. After an API failure we wait for the next cycle;
 * after an anti-bot block, polling pauses for a backoff window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMonitorService {

    /** Telegram delivery seam, registered by the bot on construction. */
    @FunctionalInterface
    public interface Notifier {
        void sendHtml(SendTarget target, String html);
    }

    private final SearchSubscriptionService subscriptions;
    private final VintedApiClient apiClient;
    private final VintedParserService parser;
    private final HistoryService historyService;
    private final UserRepository userRepository;
    private final MessageFormatter formatter;
    private final MonitorProperties props;

    private volatile Notifier notifier;
    /** Global pause-until timestamp after an anti-bot block. */
    private volatile Instant pausedUntil = Instant.EPOCH;

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${vinted.monitor.interval-ms:60000}",
            initialDelayString = "${vinted.monitor.initial-delay-ms:15000}")
    public void scheduledCheck() {
        if (!props.isEnabled() || notifier == null) return;
        if (Instant.now().isBefore(pausedUntil)) {
            log.debug("Monitor paused until {} (anti-bot backoff)", pausedUntil);
            return;
        }
        checkAll();
    }

    /** Checks every active subscription; returns total pushed listings. */
    public synchronized int checkAll() {
        if (isAntiBotPaused()) return 0;
        int pushed = 0;
        List<SearchSubscription> active = subscriptions.allActive();
        for (SearchSubscription sub : active) {
            try {
                pushed += checkOne(sub);
            } catch (VintedParseException e) {
                if (e.getReason() == VintedParseException.Reason.BLOCKED) {
                    activateAntiBotPause();
                    log.warn("Anti-bot block while checking sub {} — pausing monitor for {} ms",
                            sub.getId(), props.getBackoffMs());
                    break;
                }
                log.warn("Monitor check failed for sub {} ({}): {}",
                        sub.getId(), sub.getCatalogUrl(), e.getMessage());
            } catch (Exception e) {
                log.warn("Monitor check failed for sub {} ({}): {}",
                        sub.getId(), sub.getCatalogUrl(), e.getMessage());
            }
        }
        return pushed;
    }

    /**
     * Manual checks share the same anti-bot pause and lock as scheduled checks.
     * This prevents repeated button presses from bypassing the backoff or
     * launching concurrent requests against Vinted.
     */
    public synchronized int checkOneManually(SearchSubscription sub) {
        long remaining = antiBotPauseRemainingSeconds();
        if (remaining > 0) {
            throw new VintedParseException(VintedParseException.Reason.BLOCKED,
                    "Anti-bot pause active for another " + remaining + " seconds");
        }
        try {
            return checkOne(sub);
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) {
                activateAntiBotPause();
            }
            throw e;
        }
    }

    public boolean isAntiBotPaused() {
        return antiBotPauseRemainingSeconds() > 0;
    }

    public long antiBotPauseRemainingSeconds() {
        long millis = pausedUntil.toEpochMilli() - Instant.now().toEpochMilli();
        return millis <= 0 ? 0 : Math.max(1, (millis + 999) / 1000);
    }

    private void activateAntiBotPause() {
        Instant candidate = Instant.now().plusMillis(props.getBackoffMs());
        if (candidate.isAfter(pausedUntil)) pausedUntil = candidate;
    }

    /**
     * Checks one subscription via the ordered catalog API, diffs ids against
     * the seen set and pushes compact cards for genuinely new results.
     */
    public int checkOne(SearchSubscription sub) {
        List<CatalogItemSummary> summaries;
        try {
            summaries = apiClient.fetchCatalog(sub.getCatalogUrl(), props.getPerPage());
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) throw e;
            log.warn("Catalog API unavailable for sub {} ({}); skipping this cycle to avoid "
                    + "unreliable HTML notifications", sub.getId(), e.getMessage());
            return 0;
        }

        Set<String> seen = subscriptions.seenIds(sub);
        if (seen == null) {   // never checked: seed quietly, don't flood
            subscriptions.markCheckedIds(sub, idsOf(summaries));
            return 0;
        }

        List<CatalogItemSummary> fresh = new ArrayList<>();
        for (CatalogItemSummary s : summaries) {
            if (s.getId() != null && !seen.contains(s.getId())) fresh.add(s);
        }

        int sent = 0;
        SendTarget target = targetOf(sub);
        if (target != null && notifier != null) {
            for (CatalogItemSummary s : fresh) {
                if (sent >= props.getMaxNewPerCycle()) break;
                notifier.sendHtml(target, pushHeader(sub) + formatter.formatSummary(s));
                saveToHistory(sub.getUserId(), s);
                sent++;
            }
        }
        subscriptions.markCheckedIds(sub, idsOf(summaries));
        if (!fresh.isEmpty()) {
            log.info("Sub {}: {} new listing(s), pushed {} (api)", sub.getId(), fresh.size(), sent);
        }
        return sent;
    }

    /** Fallback: HTML catalog page + full item-page parse for each new listing. */
    private int checkOneViaHtml(SearchSubscription sub) {
        List<String> current = parser.fetchCatalogItemUrls(sub.getCatalogUrl());
        Set<String> seen = subscriptions.seenIds(sub);
        if (seen == null) {
            subscriptions.markChecked(sub, current);
            return 0;
        }
        List<String> fresh = new ArrayList<>();
        for (String url : current) {
            String id = VintedParserService.extractItemId(url);
            if (id != null && !seen.contains(id)) fresh.add(url);
        }
        int sent = 0;
        SendTarget target = targetOf(sub);
        if (target != null && notifier != null) {
            for (String url : fresh) {
                if (sent >= props.getMaxNewPerCycle()) break;
                try {
                    VintedItem item = parser.parseVintedUrl(url);
                    historyService.save(sub.getUserId(), item);
                    notifier.sendHtml(target, pushHeader(sub) + formatter.formatItem(item));
                    sent++;
                } catch (Exception e) {
                    log.warn("Failed to parse/push new listing {}: {}", url, e.getMessage());
                }
            }
        }
        subscriptions.markChecked(sub, current);
        if (!fresh.isEmpty()) {
            log.info("Sub {}: {} new listing(s), pushed {} (html)", sub.getId(), fresh.size(), sent);
        }
        return sent;
    }

    // ----------------------------------------------------------------- utils

    private void saveToHistory(Long userId, CatalogItemSummary s) {
        try {
            historyService.save(userId, VintedItem.builder()
                    .url(s.getUrl()).title(s.getTitle())
                    .price(s.getPrice()).currency(s.getCurrency())
                    .brand(s.getBrand()).size(s.getSize())
                    .condition(s.getCondition())
                    .imageUrls(s.getPhotoUrl() == null ? List.of() : List.of(s.getPhotoUrl()))
                    .build());
        } catch (Exception e) {
            log.debug("History save failed for pushed listing {}: {}", s.getUrl(), e.getMessage());
        }
    }

    private List<String> idsOf(List<CatalogItemSummary> summaries) {
        List<String> ids = new ArrayList<>();
        for (CatalogItemSummary s : summaries) {
            if (s.getId() != null) ids.add(s.getId());
        }
        return ids;
    }

    /** Delivery target: the subscription's chat/topic, or the owner's private chat. */
    private SendTarget targetOf(SearchSubscription sub) {
        Long chatId = sub.getChatId();
        if (chatId == null) {
            chatId = userRepository.findById(sub.getUserId()).map(u -> u.getChatId()).orElse(null);
        }
        return chatId == null ? null : new SendTarget(chatId, sub.getMessageThreadId());
    }

    private String pushHeader(SearchSubscription sub) {
        String label = sub.getLabel() != null && !sub.getLabel().isBlank()
                ? sub.getLabel() : "Recherche Vinted";
        return "🔔 <b>Nouvelle annonce</b> pour l’abonnement «" + esc(label) + "»\n\n";
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
