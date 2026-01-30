package fr.phylisium.firebaul;

import fr.phylisium.firebaul.keyword.KeywordRegistry;
import net.kyori.adventure.text.Component;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/**
 * Formatter responsable de sanitiser le texte et construire un Component Adventure
 * où seules les portions correspondant aux matches sont colorées.
 */
public class ActionBarFormatter {
    public String sanitizePreserveLength(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE || type == Character.UNASSIGNED) { sb.append(' '); continue; }
            if (c == '§') { sb.append(' '); continue; }
            if (c == '&') {
                if (i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    char ln = Character.toLowerCase(n);
                    boolean isCode = (ln >= '0' && ln <= '9') || (ln >= 'a' && ln <= 'f') || (ln >= 'k' && ln <= 'o') || ln == 'r';
                    sb.append(' ');
                    if (isCode) { sb.append(' '); i++; }
                    continue;
                } else { sb.append(' '); continue; }
            }
            if (Character.isISOControl(c)) { sb.append(' '); continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    public Component buildComponentWithHighlights(String text, List<KeywordRegistry.Match> matches) {
        if (matches == null || matches.isEmpty()) return Component.text(text);
        matches.sort(Comparator.comparingInt(a -> a.start));
        java.util.List<Component> parts = new java.util.ArrayList<>();
        int idx = 0;
        for (KeywordRegistry.Match m : matches) {
            if (m.start > idx) parts.add(Component.text(text.substring(idx, m.start)));
            parts.add(Component.text(text.substring(m.start, m.end)).color(m.action.getColor()));
            idx = Math.max(idx, m.end);
        }
        if (idx < text.length()) parts.add(Component.text(text.substring(idx)));
        Component res = Component.empty();
        for (Component p : parts) res = res.append(p);
        return res;
    }

    public Component formatForActionBar(String rawText, List<KeywordRegistry.Match> matches) {
        if (rawText == null) return Component.empty();
        String normalized = Normalizer.normalize(rawText, Normalizer.Form.NFC);
        String sanitized = sanitizePreserveLength(normalized);
        return (matches == null || matches.isEmpty()) ? Component.text(sanitized) : buildComponentWithHighlights(sanitized, matches);
    }
}

