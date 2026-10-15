package zy.swthb.lang;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server-side translations.
 * <p>
 * Players without this mod installed have no copy of our lang files, so a plain translatable
 * component would show its raw key (or the hard-coded fallback). Every chat / action-bar message
 * goes through {@link #forPlayer}, which fills in the fallback text in the language that player's
 * client reports. Players who do have the mod still resolve the key from their own lang files.
 * <p>
 * Lookup order for a language, per key:
 * <ol>
 *     <li>the exact language (e.g. {@code en_au})</li>
 *     <li>its regional parents (e.g. {@code en_au -> en_gb}, {@code zh_hk -> zh_tw})</li>
 *     <li>the main variant of the same language (e.g. {@code es_mx -> es_es}, {@code fr_ca -> fr_fr})</li>
 *     <li>the server's language (see {@link #serverLanguage()})</li>
 *     <li>{@code en_us}</li>
 * </ol>
 */
public final class ModLang {

    private static final Logger LOGGER = LoggerFactory.getLogger("simple-warp-tpa-home-back-lang");

    private static final String MOD_ID = "simple-warp-tpa-home-back";
    private static final String KEY_PREFIX = "swthb.";
    private static final String DEFAULT = Language.DEFAULT; // en_us

    /** Optional override for the server/console language, e.g. {@code -Dswthb.language=zh_cn}. */
    private static final String LANGUAGE_PROPERTY = "swthb.language";

    /** Same placeholder syntax as vanilla's TranslatableContents: %s, %1$s, %%. */
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    /** Regional variants that should prefer another variant before falling back further. */
    private static final Map<String, String> PARENTS = Map.of(
            "en_au", "en_gb",
            "en_nz", "en_gb",
            "en_ca", "en_gb",
            "zh_hk", "zh_tw",
            "zh_tw", "zh_hk",
            "lzh", "zh_tw"
    );

    /** Main variant of a language when it isn't simply {@code xx_xx}. */
    private static final Map<String, String> PRIMARY = Map.of(
            "en", "en_us",
            "zh", "zh_cn",
            "ar", "ar_sa",
            "hi", "hi_in",
            "pt", "pt_br",
            "ja", "ja_jp"
    );

    private static final Map<String, Map<String, String>> TABLES = loadTables();
    private static final Map<String, List<String>> CHAINS = new ConcurrentHashMap<>();
    private static final String SERVER_LANGUAGE = detectServerLanguage();

    private ModLang() {}

    // ---------- public API ----------

    /** Language used for the console and log output, and as the fallback for players. */
    public static String serverLanguage() {
        return SERVER_LANGUAGE;
    }

    /** Translates a key into the server's language, formatted with the given arguments. For log output. */
    public static String server(String key, Object... args) {
        String template = lookup(chain(SERVER_LANGUAGE), key);
        return format(template != null ? template : key, args);
    }

    /**
     * Fills in our translations for a message about to be sent to a player.
     * The key is kept so a client with the mod (or a resource pack) can still override it.
     */
    public static Component forPlayer(Component message, String clientLanguage) {
        List<String> chain = chain(clientLanguage);
        return rewrite(message, (tc, args) -> {
            String text = lookup(chain, tc.getKey());
            return text == null ? null : new TranslatableContents(tc.getKey(), text, args);
        });
    }

    /** Fully resolves our translations to plain text in the server's language, for the console. */
    public static Component forConsole(Component message) {
        List<String> chain = chain(SERVER_LANGUAGE);
        return rewrite(message, (tc, args) -> {
            String text = lookup(chain, tc.getKey());
            return text == null ? null : PlainTextContents.create(format(text, args));
        });
    }

    /** The ordered list of languages tried for the given language code. */
    public static List<String> chain(String language) {
        return CHAINS.computeIfAbsent(normalise(language), ModLang::buildChain);
    }

    // ---------- fallback chain ----------

    private static List<String> buildChain(String language) {
        Set<String> out = new LinkedHashSet<>();
        addWithRegionalParents(out, language);
        addSameLanguage(out, language);

        // Client language unknown to us: respect the server's language before using en_us.
        if (SERVER_LANGUAGE != null && !SERVER_LANGUAGE.equals(language)) {
            addWithRegionalParents(out, SERVER_LANGUAGE);
            addSameLanguage(out, SERVER_LANGUAGE);
        }
        out.add(DEFAULT);

        // Only keep languages we actually ship, so lookups stay cheap.
        List<String> result = new ArrayList<>();
        for (String lang : out) {
            if (TABLES.containsKey(lang)) result.add(lang);
        }
        return List.copyOf(result);
    }

    private static void addWithRegionalParents(Set<String> out, String language) {
        String current = language;
        while (current != null && out.add(current)) {
            current = PARENTS.get(current);
        }
    }

    private static void addSameLanguage(Set<String> out, String language) {
        String base = language.split("_", 2)[0];
        out.add(PRIMARY.getOrDefault(base, base + "_" + base));
        // Any other variant we ship for the same language, e.g. pt_pt -> pt_br.
        TABLES.keySet().stream()
                .filter(lang -> lang.startsWith(base + "_"))
                .sorted()
                .forEach(out::add);
    }

    private static String lookup(List<String> chain, String key) {
        for (String lang : chain) {
            String value = TABLES.get(lang).get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String normalise(String language) {
        if (language == null || language.isBlank()) return DEFAULT;
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String detectServerLanguage() {
        String configured = System.getProperty(LANGUAGE_PROPERTY);
        if (configured != null && !configured.isBlank()) return normalise(configured);

        Locale locale = Locale.getDefault();
        if (locale.getLanguage().isEmpty()) return DEFAULT;
        return normalise(locale.getCountry().isEmpty()
                ? locale.getLanguage()
                : locale.getLanguage() + "_" + locale.getCountry());
    }

    // ---------- loading ----------

    private static Map<String, Map<String, String>> loadTables() {
        Map<String, Map<String, String>> tables = new HashMap<>();
        Optional<Path> dir = FabricLoader.getInstance().getModContainer(MOD_ID)
                .flatMap(container -> container.findPath("assets/" + MOD_ID + "/lang"));

        if (dir.isEmpty()) {
            LOGGER.error("Could not find the lang folder; messages will show untranslated keys");
            return tables;
        }

        try (Stream<Path> files = Files.list(dir.get())) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(file -> {
                String name = file.getFileName().toString();
                String lang = name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT);
                Map<String, String> table = new HashMap<>();
                try (InputStream in = Files.newInputStream(file)) {
                    Language.loadFromJson(in, table::put);
                    tables.put(lang, Map.copyOf(table));
                } catch (Exception e) {
                    LOGGER.error("Failed to load {}: {}", name, e.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to list lang files: {}", e.getMessage());
        }
        return Map.copyOf(tables);
    }

    // ---------- formatting ----------

    /** Formats a template the same way vanilla TranslatableContents does, but to a plain string. */
    private static String format(String template, Object[] args) {
        Matcher m = FORMAT.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int nextArg = 0;
        try {
            while (m.find()) {
                sb.append(template, last, m.start());
                last = m.end();
                String type = m.group(2);
                if ("%".equals(type) && m.group(1) == null) {
                    sb.append('%');
                } else if ("s".equals(type)) {
                    int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : nextArg++;
                    Object arg = index >= 0 && index < args.length ? args[index] : null;
                    sb.append(arg instanceof Component c ? c.getString() : String.valueOf(arg));
                } else {
                    return template; // unsupported placeholder, same as vanilla: show the raw template
                }
            }
            sb.append(template, last, template.length());
            return sb.toString();
        } catch (RuntimeException e) {
            return template;
        }
    }

    // ---------- component rewriting ----------

    @FunctionalInterface
    private interface Translator {
        /** Returns replacement contents for one of our translatable nodes, or null to leave it alone. */
        ComponentContents translate(TranslatableContents contents, Object[] rewrittenArgs);
    }

    /** Walks a component tree (arguments, hover text and siblings included). Returns the input if nothing changed. */
    private static Component rewrite(Component component, Translator translator) {
        ComponentContents contents = component.getContents();
        ComponentContents newContents = contents;

        if (contents instanceof TranslatableContents tc) {
            Object[] args = tc.getArgs();
            Object[] newArgs = rewriteArgs(args, translator);
            ComponentContents translated = tc.getKey().startsWith(KEY_PREFIX)
                    ? translator.translate(tc, newArgs) : null;
            if (translated != null) {
                newContents = translated;
            } else if (newArgs != args) {
                newContents = new TranslatableContents(tc.getKey(), tc.getFallback(), newArgs);
            }
        }

        Style style = component.getStyle();
        Style newStyle = style;
        if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component hoverText)) {
            Component newHover = rewrite(hoverText, translator);
            if (newHover != hoverText) newStyle = style.withHoverEvent(new HoverEvent.ShowText(newHover));
        }

        List<Component> siblings = component.getSiblings();
        List<Component> newSiblings = null;
        for (int i = 0; i < siblings.size(); i++) {
            Component sibling = siblings.get(i);
            Component newSibling = rewrite(sibling, translator);
            if (newSibling != sibling && newSiblings == null) {
                newSiblings = new ArrayList<>(siblings.subList(0, i));
            }
            if (newSiblings != null) newSiblings.add(newSibling);
        }

        if (newContents == contents && newStyle == style && newSiblings == null) {
            return component;
        }

        MutableComponent out = MutableComponent.create(newContents).setStyle(newStyle);
        for (Component sibling : newSiblings != null ? newSiblings : siblings) {
            out.append(sibling);
        }
        return out;
    }

    private static Object[] rewriteArgs(Object[] args, Translator translator) {
        Object[] out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Component arg) {
                Component rewritten = rewrite(arg, translator);
                if (rewritten != arg) {
                    if (out == null) out = args.clone();
                    out[i] = rewritten;
                }
            }
        }
        return out != null ? out : args;
    }
}
