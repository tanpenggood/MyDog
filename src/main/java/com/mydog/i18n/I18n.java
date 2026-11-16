package com.mydog.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Static i18n facade. All UI text goes through {@link #tr(String, Object...)}.
 * Locale changes rebuild the whole Scene, so no listener bookkeeping is needed here.
 */
public final class I18n {

    private static final String BASE_NAME = "com.mydog.i18n.Messages";

    private static volatile ResourceBundle bundle = load(Locale.getDefault());
    private static volatile Locale locale = Locale.getDefault();

    private I18n() {
    }

    private static ResourceBundle load(Locale loc) {
        return ResourceBundle.getBundle(BASE_NAME, loc, I18n.class.getModule());
    }

    /** Translate with optional MessageFormat arguments. */
    public static String tr(String key, Object... args) {
        ResourceBundle b = bundle;
        String pattern = b.containsKey(key) ? b.getString(key) : "!" + key + "!";
        return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }

    /** Current effective locale tag, e.g. "zh_CN" or "en". */
    public static String localeTag() {
        Locale l = locale;
        return l.getLanguage() + (l.getCountry().isEmpty() ? "" : "_" + l.getCountry());
    }

    /** Switch locale. Caller is responsible for rebuilding the Scene afterwards. */
    public static void setLocale(String tag) {
        Locale loc = switch (tag == null ? "" : tag) {
            case "zh_CN" -> Locale.SIMPLIFIED_CHINESE;
            case "en" -> Locale.ENGLISH;
            default -> Locale.getDefault();
        };
        locale = loc;
        bundle = load(loc);
    }
}
