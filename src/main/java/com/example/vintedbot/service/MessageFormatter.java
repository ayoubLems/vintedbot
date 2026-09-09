package com.example.vintedbot.service;

// Traduit depuis le russe par Ayoub Lemsoudi.

import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.model.WatchedItem;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds Telegram messages using HTML parse mode (safer escaping than MarkdownV2).
 */
@Component
public class MessageFormatter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private static final int DESC_LIMIT = 400;

    public String formatItem(VintedItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("👗 <b>").append(esc(orDash(item.getTitle()))).append("</b>\n\n");

        sb.append("💰 <b>").append(formatPrice(item.getPrice(), item.getCurrency())).append("</b>\n");
        sb.append("📦 Taille : ").append(esc(orDash(item.getSize()))).append("\n");
        sb.append("🏷️ Marque : ").append(esc(orDash(item.getBrand()))).append("\n");
        sb.append("⭐ État : ").append(esc(orDash(item.getCondition()))).append("\n");
        if (item.getColor() != null && !item.getColor().isBlank()) {
            sb.append("🎨 Couleur : ").append(esc(item.getColor())).append("\n");
        }

        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            sb.append("\n📝 Description : ").append(esc(truncate(item.getDescription()))).append("\n");
        }

        sb.append("\n🔗 <a href=\"").append(esc(item.getUrl())).append("\">Voir sur Vinted</a>\n");
        sb.append("⏰ Analysé le : ").append(TIME.format(OffsetDateTime.now()));
        return sb.toString();
    }

    public String formatHistoryEntry(ParsedItem item, int index) {
        return String.format("%d. <b>%s</b> — %s\n   🔗 <a href=\"%s\">lien</a> · ⏰ %s",
                index,
                esc(orDash(item.getTitle())),
                formatPrice(item.getPrice(), item.getCurrency()),
                esc(item.getVintedUrl()),
                TIME.format(item.getParsedAt()));
    }

    /** Compact card built from catalog-API data (no item-page fetch → instant). */
    public String formatSummary(CatalogItemSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("👗 <b>").append(esc(orDash(s.getTitle()))).append("</b>\n");
        sb.append("💰 <b>").append(formatPrice(s.getPrice(), s.getCurrency())).append("</b>");
        if (s.getBrand() != null && !s.getBrand().isBlank()) {
            sb.append(" · 🏷️ ").append(esc(s.getBrand()));
        }
        if (s.getSize() != null && !s.getSize().isBlank()) {
            sb.append(" · 📦 ").append(esc(s.getSize()));
        }
        if (s.getCondition() != null && !s.getCondition().isBlank()) {
            sb.append(" · ⭐ ").append(esc(s.getCondition()));
        }
        sb.append("\n🔗 <a href=\"").append(esc(s.getUrl())).append("\">Voir sur Vinted</a>");
        return sb.toString();
    }

    /** One-line summary of a search subscription. */
    public String formatSubEntry(SearchSubscription s, int index) {
        String name = s.getLabel() != null && !s.getLabel().isBlank() ? s.getLabel() : "Recherche Vinted";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(index).append(".</b> ")
                .append(s.isActive() ? "🔔 " : "⏸ ").append(esc(name));
        if (!s.isActive()) sb.append(" <i>(en pause)</i>");
        if (s.getChatTitle() != null && !s.getChatTitle().isBlank()) {
            sb.append(s.getMessageThreadId() != null ? " · 🧵 sujet dans «" : " · 👥 «")
                    .append(esc(s.getChatTitle())).append("»");
        }
        sb.append("\n   🔗 <a href=\"").append(esc(s.getCatalogUrl())).append("\">ouvrir la recherche</a>");
        if (s.getLastCheckedAt() != null) {
            sb.append(" · vérifié le ").append(TIME.format(s.getLastCheckedAt()));
        }
        return sb.toString();
    }

    /** One-line summary of a watchlist entry (index shown to the user). */
    public String formatWatchEntry(WatchedItem w, int index) {
        String name = w.getLabel() != null && !w.getLabel().isBlank()
                ? w.getLabel()
                : (w.getLastTitle() != null ? w.getLastTitle() : "sans titre");
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(index).append(".</b> ").append(esc(name));
        if (w.getLastPrice() != null) {
            sb.append(" — ").append(formatPrice(w.getLastPrice(), w.getLastCurrency()));
        }
        sb.append("\n   🔗 <a href=\"").append(esc(w.getVintedUrl())).append("\">lien</a>");
        return sb.toString();
    }

    public String formatPrice(Double price, String currency) {
        if (price == null) return "—";
        String symbol = switch (currency == null ? "" : currency) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            default -> currency == null ? "" : currency + " ";
        };
        String amount = price == Math.floor(price)
                ? String.valueOf(price.intValue())
                : String.valueOf(price);
        return "EUR".equals(currency) || "USD".equals(currency) || "GBP".equals(currency)
                ? symbol + amount
                : (symbol + amount).trim();
    }

    private String truncate(String s) {
        if (s.length() <= DESC_LIMIT) return s;
        return s.substring(0, DESC_LIMIT).trim() + "…";
    }

    private String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /** Escapes the five HTML entities Telegram's HTML parse mode cares about. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
