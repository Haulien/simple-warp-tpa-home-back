package zy.swthb.command;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Tab-completion helper.
 * <p>
 * The client shows whatever the server suggests without filtering it, so suggestions must be
 * filtered here against what the player has typed so far (case-insensitive prefix match).
 */
public final class Suggest {

    private Suggest() {}

    public static CompletableFuture<Suggestions> matching(Iterable<String> candidates, SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                sorted.add(candidate);
            }
        }
        sorted.forEach(builder::suggest);
        return builder.buildFuture();
    }
}
