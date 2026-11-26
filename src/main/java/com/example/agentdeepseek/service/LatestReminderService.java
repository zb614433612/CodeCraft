package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DeepSeek {@code latest_reminder} 用户元信息构建器。
 *
 * <p>格式（官方文档）：{@code <date>,<weekday>,<location>,<platform>,<language>}。</p>
 *
 * <p><b>位置来源</b>：优先使用 App 端上报的 IANA 时区（{@code userTimeZone}），
 * 缺省回退到本机系统时区。这里<b>不做 IP 地理定位</b>——本机可能挂着代理，
 * 用出口 IP 推断会把代理机房当成用户真实位置。</p>
 *
 * <p><b>语言来源</b>：由位置（时区 → 国家）决定，而不是直接用操作系统语言。
 * 例如系统语言是英文、但时区是 {@code Asia/Shanghai}，仍按中文输出。</p>
 *
 * <p><b>⚠️ 语言字段必须取自官方白名单，不能用 JVM 现算</b>：官方要求 {@code language}
 * 是「用该语言自身书写的语言名」（{@code English} / {@code 中文}），并对取值做枚举校验，
 * 非法值直接返回
 * {@code Invalid 'messages[x].content': unknown language 'Chinese'} 400。</p>
 *
 * <p>而 {@link Locale#getDisplayLanguage(Locale)} 依赖 JRE 的 locale 数据：安装包内置 JRE
 * 由 {@code jlink} 裁剪，未含 {@code jdk.localedata} 时除英文外的语言名全部回退成英文
 * （{@code zh → Chinese}）——开发机能跑、安装版必挂。因此语言名与星期名都改为内置表，
 * 不依赖运行时的 locale 数据。</p>
 */
@Slf4j
@Service
public class LatestReminderService {

    /** 时区 → ISO 国家/地区代码（只收录常见时区，未命中的回退 App/系统 locale 的国家） */
    private static final Map<String, String> ZONE_COUNTRY = Map.ofEntries(
            Map.entry("Asia/Shanghai", "CN"),
            Map.entry("Asia/Chongqing", "CN"),
            Map.entry("Asia/Harbin", "CN"),
            Map.entry("Asia/Urumqi", "CN"),
            Map.entry("Asia/Hong_Kong", "HK"),
            Map.entry("Asia/Macau", "MO"),
            Map.entry("Asia/Taipei", "TW"),
            Map.entry("Asia/Singapore", "SG"),
            Map.entry("Asia/Tokyo", "JP"),
            Map.entry("Asia/Seoul", "KR"),
            Map.entry("Asia/Bangkok", "TH"),
            Map.entry("Asia/Ho_Chi_Minh", "VN"),
            Map.entry("Asia/Jakarta", "ID"),
            Map.entry("Asia/Kuala_Lumpur", "MY"),
            Map.entry("Asia/Manila", "PH"),
            Map.entry("Asia/Kolkata", "IN"),
            Map.entry("Asia/Dubai", "AE"),
            Map.entry("Asia/Riyadh", "SA"),
            Map.entry("Asia/Jerusalem", "IL"),
            Map.entry("Asia/Istanbul", "TR"),
            Map.entry("Europe/London", "GB"),
            Map.entry("Europe/Paris", "FR"),
            Map.entry("Europe/Berlin", "DE"),
            Map.entry("Europe/Madrid", "ES"),
            Map.entry("Europe/Rome", "IT"),
            Map.entry("Europe/Lisbon", "PT"),
            Map.entry("Europe/Moscow", "RU"),
            Map.entry("Europe/Amsterdam", "NL"),
            Map.entry("Europe/Brussels", "BE"),
            Map.entry("Europe/Zurich", "CH"),
            Map.entry("Europe/Vienna", "AT"),
            Map.entry("Europe/Stockholm", "SE"),
            Map.entry("Europe/Oslo", "NO"),
            Map.entry("Europe/Copenhagen", "DK"),
            Map.entry("Europe/Helsinki", "FI"),
            Map.entry("Europe/Warsaw", "PL"),
            Map.entry("Europe/Prague", "CZ"),
            Map.entry("Europe/Athens", "GR"),
            Map.entry("Europe/Budapest", "HU"),
            Map.entry("Europe/Bucharest", "RO"),
            Map.entry("Europe/Kiev", "UA"),
            Map.entry("Europe/Kyiv", "UA"),
            Map.entry("America/New_York", "US"),
            Map.entry("America/Chicago", "US"),
            Map.entry("America/Denver", "US"),
            Map.entry("America/Los_Angeles", "US"),
            Map.entry("America/Phoenix", "US"),
            Map.entry("America/Anchorage", "US"),
            Map.entry("America/Toronto", "CA"),
            Map.entry("America/Vancouver", "CA"),
            Map.entry("America/Mexico_City", "MX"),
            Map.entry("America/Sao_Paulo", "BR"),
            Map.entry("America/Argentina/Buenos_Aires", "AR"),
            Map.entry("Australia/Sydney", "AU"),
            Map.entry("Australia/Melbourne", "AU"),
            Map.entry("Australia/Brisbane", "AU"),
            Map.entry("Australia/Perth", "AU"),
            Map.entry("Pacific/Auckland", "NZ"),
            Map.entry("Africa/Cairo", "EG"),
            Map.entry("Africa/Johannesburg", "ZA"),
            Map.entry("Africa/Lagos", "NG")
    );

    /** 国家/地区 → 默认语言（BCP-47 语言子标签） */
    private static final Map<String, String> COUNTRY_LANGUAGE = Map.ofEntries(
            Map.entry("CN", "zh"),
            Map.entry("HK", "zh"),
            Map.entry("MO", "zh"),
            Map.entry("TW", "zh"),
            Map.entry("SG", "en"),
            Map.entry("MY", "ms"),
            Map.entry("JP", "ja"),
            Map.entry("KR", "ko"),
            Map.entry("TH", "th"),
            Map.entry("VN", "vi"),
            Map.entry("ID", "id"),
            Map.entry("PH", "en"),
            Map.entry("IN", "en"),
            Map.entry("US", "en"),
            Map.entry("GB", "en"),
            Map.entry("CA", "en"),
            Map.entry("AU", "en"),
            Map.entry("NZ", "en"),
            Map.entry("IE", "en"),
            Map.entry("ZA", "en"),
            Map.entry("DE", "de"),
            Map.entry("AT", "de"),
            Map.entry("CH", "de"),
            Map.entry("FR", "fr"),
            Map.entry("BE", "nl"),
            Map.entry("NL", "nl"),
            Map.entry("ES", "es"),
            Map.entry("MX", "es"),
            Map.entry("AR", "es"),
            Map.entry("IT", "it"),
            Map.entry("PT", "pt"),
            Map.entry("BR", "pt"),
            Map.entry("RU", "ru"),
            Map.entry("SE", "sv"),
            Map.entry("NO", "no"),
            Map.entry("DK", "da"),
            Map.entry("FI", "fi"),
            Map.entry("PL", "pl"),
            Map.entry("CZ", "cs"),
            Map.entry("GR", "el"),
            Map.entry("HU", "hu"),
            Map.entry("RO", "ro"),
            Map.entry("UA", "uk"),
            Map.entry("TR", "tr"),
            Map.entry("IL", "he"),
            Map.entry("AE", "ar"),
            Map.entry("SA", "ar"),
            Map.entry("EG", "ar")
    );

    /**
     * 语言代码 → DeepSeek 官方认可的语言值（用该语言自身书写）。
     *
     * <p>取值必须严格来自官方文档的 Supported language values 清单，
     * 一个字符都不能改（{@code português} 的 ê、{@code қазақтілі} 的原样写法都要保留）。</p>
     *
     * <p><b>不要改回 {@link Locale#getDisplayLanguage(Locale)}</b>：裁剪 JRE 缺 locale 数据时
     * 会返回英文名（{@code Chinese}），DeepSeek 直接 400。</p>
     */
    private static final Map<String, String> LANGUAGE_LABELS = Map.ofEntries(
            Map.entry("af", "Afrikaans"),
            Map.entry("am", "አማርኛ"),
            Map.entry("ar", "العربية"),
            Map.entry("az", "Azərbaycanca"),
            Map.entry("be", "беларуская"),
            Map.entry("bg", "български"),
            Map.entry("bn", "বাংলা"),
            Map.entry("ca", "català"),
            Map.entry("cs", "čeština"),
            Map.entry("da", "dansk"),
            Map.entry("de", "Deutsch"),
            Map.entry("el", "Ελληνικά"),
            Map.entry("en", "English"),
            Map.entry("es", "español"),
            Map.entry("et", "eesti"),
            Map.entry("eu", "euskara"),
            Map.entry("fa", "فارسی"),
            Map.entry("fi", "suomi"),
            Map.entry("fil", "Filipino"),
            Map.entry("fr", "français"),
            Map.entry("gl", "galego"),
            Map.entry("gu", "ગુજરાતી"),
            Map.entry("he", "עברית"),
            Map.entry("hi", "हिन्दी"),
            Map.entry("hr", "hrvatski"),
            Map.entry("hu", "magyar"),
            Map.entry("hy", "հայերեն"),
            Map.entry("id", "Indonesia"),
            Map.entry("is", "íslenska"),
            Map.entry("it", "italiano"),
            Map.entry("ja", "日本語"),
            Map.entry("ka", "ქართული"),
            Map.entry("kk", "қазақтілі"),
            Map.entry("km", "ខ្មែរ"),
            Map.entry("kn", "ಕನ್ನಡ"),
            Map.entry("ko", "한국어"),
            Map.entry("ky", "кыргызча"),
            Map.entry("lo", "ລາວ"),
            Map.entry("lt", "lietuvių"),
            Map.entry("lv", "latviešu"),
            Map.entry("mk", "македонски"),
            Map.entry("ml", "മലയാളം"),
            Map.entry("mn", "монгол"),
            Map.entry("mr", "मराठी"),
            Map.entry("ms", "Melayu"),
            Map.entry("my", "မြန်မာ"),
            Map.entry("ne", "नेपाली"),
            Map.entry("nl", "Nederlands"),
            Map.entry("pa", "ਪੰਜਾਬੀ"),
            Map.entry("pl", "polski"),
            Map.entry("pt", "português"),
            Map.entry("ro", "română"),
            Map.entry("ru", "русский"),
            Map.entry("si", "සිංහල"),
            Map.entry("sk", "slovenčina"),
            Map.entry("sl", "slovenščina"),
            Map.entry("sq", "shqip"),
            Map.entry("sr", "српски"),
            Map.entry("sv", "svenska"),
            Map.entry("sw", "Kiswahili"),
            Map.entry("ta", "தமிழ்"),
            Map.entry("te", "తెలుగు"),
            Map.entry("th", "ไทย"),
            Map.entry("tr", "Türkçe"),
            Map.entry("uk", "українська"),
            Map.entry("ur", "اردو"),
            Map.entry("vi", "TiếngViệt"),
            Map.entry("zh", "中文"),
            Map.entry("zu", "isiZulu")
    );

    /** 旧版/别名语言代码 → 现行 ISO 639-1 代码（Java 老版本仍会返回 in / iw / tl / nb） */
    private static final Map<String, String> LEGACY_LANGUAGE_CODES = Map.of(
            "in", "id",
            "iw", "he",
            "ji", "yi",
            "tl", "fil",
            "nb", "no",
            "nn", "no"
    );

    private static final String LANGUAGE_ZH = "中文";
    private static final String LANGUAGE_ZH_HANT = "中文繁體";
    private static final String LANGUAGE_ZH_HK = "中文香港";

    /**
     * DeepSeek 官方支持的语言值全集（用于校验历史落库内容是否仍可下发）。
     *
     * <p>中文按地区拆成三个官方值：{@code 中文} / {@code 中文繁體} / {@code 中文香港}。</p>
     */
    private static final Set<String> SUPPORTED_LANGUAGE_VALUES = buildSupportedLanguageValues();

    /** 中文星期表（简体/繁体同形，不受 JRE locale 数据影响） */
    private static final String[] WEEKDAYS_ZH =
            {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    /** 英文星期表（同上，保证英文也不再依赖 JRE locale 数据） */
    private static final String[] WEEKDAYS_EN =
            {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    private static Set<String> buildSupportedLanguageValues() {
        Set<String> values = new LinkedHashSet<>(LANGUAGE_LABELS.values());
        values.add(LANGUAGE_ZH);
        values.add(LANGUAGE_ZH_HANT);
        values.add(LANGUAGE_ZH_HK);
        return Set.copyOf(values);
    }

    /**
     * 构建当前这一轮的 latest_reminder 内容。
     *
     * @param userTimeZone App 端 IANA 时区，如 {@code Asia/Shanghai}；为空回退系统时区
     * @param userLocale   App 端 BCP-47 locale，如 {@code zh-CN}；仅用于时区无法映射国家时的兜底
     * @return 例如 {@code 2026-11-26,星期四,中国,App,中文}
     */
    public String buildCurrent(String userTimeZone, String userLocale) {
        ZoneId zone = resolveZone(userTimeZone);
        LocalDate today = LocalDate.now(zone);
        Region region = resolveRegion(zone, userLocale);
        return today + "," + resolveWeekday(today, region) + "," + region.location() + ",App," + region.language();
    }

    /**
     * 校验并归一化「历史落库」的 latest_reminder 内容。
     *
     * <p><b>为什么需要</b>：v3.6.1 安装包的内置 JRE 由 jlink 裁剪、未含 {@code jdk.localedata}，
     * 当时落库的语言名是英文（{@code Chinese}）。这些脏数据会随历史一起重建进请求体，
     * 升级后仍然 400。官方允许省略历史 reminder（只是降低上下文缓存命中率），
     * 因此非法值一律丢弃，绝不再发给 API。</p>
     *
     * @param stored 落库内容，可为 {@code null}
     * @return 合法内容原样返回；结构不符或语言不在官方白名单时返回 {@code null}
     */
    public String sanitizeForSend(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String trimmed = stored.trim();
        String[] parts = trimmed.split(",", -1);
        if (parts.length < 5) {
            log.debug("丢弃结构不合法的 latest_reminder（段数={}）: {}", parts.length, trimmed);
            return null;
        }
        try {
            LocalDate.parse(parts[0].trim());
        } catch (Exception e) {
            log.debug("丢弃日期不合法的 latest_reminder: {}", trimmed);
            return null;
        }
        String language = parts[parts.length - 1].trim();
        if (!SUPPORTED_LANGUAGE_VALUES.contains(language)) {
            log.debug("丢弃语言值不合法的 latest_reminder（language={}）: {}", language, trimmed);
            return null;
        }
        return trimmed;
    }

    private ZoneId resolveZone(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return ZoneId.of(raw.trim());
            } catch (Exception e) {
                log.debug("无效的 App 时区，回退系统时区: {}", raw);
            }
        }
        return ZoneId.systemDefault();
    }

    private Region resolveRegion(ZoneId zone, String userLocale) {
        Locale fallback = parseLocale(userLocale);
        String country = ZONE_COUNTRY.get(zone.getId());
        if (country == null || country.isBlank()) {
            country = fallback.getCountry();
        }

        String languageCode = country == null || country.isBlank()
                ? fallback.getLanguage()
                : COUNTRY_LANGUAGE.getOrDefault(country, fallback.getLanguage());
        languageCode = normalizeLanguageCode(languageCode);

        String language = resolveLanguageLabel(languageCode, country);
        Locale displayLocale = resolveDisplayLocale(languageCode, language);

        String location = "";
        if (country != null && !country.isBlank()) {
            location = new Locale("", country).getDisplayCountry(displayLocale);
        }
        if (location == null || location.isBlank()) {
            // 时区/国家都取不到时，用 IANA 时区的城市段兜底（如 Asia/Shanghai → Shanghai）
            String id = zone.getId();
            int slash = id.lastIndexOf('/');
            location = slash >= 0 ? id.substring(slash + 1).replace('_', ' ') : id;
        }
        return new Region(location, language, displayLocale);
    }

    /**
     * 语言代码归一化：只取主语言子标签并转小写，旧的 Java 别名映射回现行代码。
     */
    private String normalizeLanguageCode(String raw) {
        String code = raw == null || raw.isBlank() ? "en" : raw.trim().toLowerCase(Locale.ROOT);
        int sep = code.indexOf('-');
        if (sep > 0) {
            code = code.substring(0, sep);
        }
        int underscore = code.indexOf('_');
        if (underscore > 0) {
            code = code.substring(0, underscore);
        }
        return LEGACY_LANGUAGE_CODES.getOrDefault(code, code);
    }

    /**
     * 语言代码 + 地区 → DeepSeek 官方语言值。
     *
     * <p>中文按地区细分：港澳 → {@code 中文香港}，台湾 → {@code 中文繁體}，其余 → {@code 中文}。</p>
     *
     * <p>不在官方白名单的语言（如挪威语 {@code no}）统一回退 {@code English}——
     * 官方对 language 做枚举校验，宁可少一条本地化信息，也不能让整轮请求 400。</p>
     */
    private String resolveLanguageLabel(String languageCode, String country) {
        if ("zh".equals(languageCode)) {
            if ("HK".equals(country) || "MO".equals(country)) {
                return LANGUAGE_ZH_HK;
            }
            if ("TW".equals(country)) {
                return LANGUAGE_ZH_HANT;
            }
            return LANGUAGE_ZH;
        }
        return LANGUAGE_LABELS.getOrDefault(languageCode, "English");
    }

    /**
     * 该语言的 display locale：仅用于 location 的国家名展示（weekday 已改内置表）。
     */
    private Locale resolveDisplayLocale(String languageCode, String language) {
        if (LANGUAGE_ZH_HK.equals(language)) {
            return Locale.forLanguageTag("zh-HK");
        }
        if (LANGUAGE_ZH_HANT.equals(language)) {
            return Locale.forLanguageTag("zh-TW");
        }
        if (LANGUAGE_ZH.equals(language)) {
            return Locale.forLanguageTag("zh-CN");
        }
        if ("English".equals(language)) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(languageCode);
    }

    /**
     * 星期名：官方要求「用 language 指定的语言书写」。
     *
     * <p>中文/英文走内置表，不再依赖 JRE 的 locale 数据（裁剪 JRE 会回退成英文，
     * 出现 {@code language=中文} 却配 {@code Thursday} 的错配）；其他语言仍用 JVM 显示名，
     * 缺失时回退英文。</p>
     */
    private String resolveWeekday(LocalDate date, Region region) {
        int index = date.getDayOfWeek().getValue() - 1;
        if (region.language().startsWith(LANGUAGE_ZH)) {
            return WEEKDAYS_ZH[index];
        }
        if ("English".equals(region.language())) {
            return WEEKDAYS_EN[index];
        }
        String display = date.getDayOfWeek().getDisplayName(TextStyle.FULL, region.locale());
        return display == null || display.isBlank() ? WEEKDAYS_EN[index] : display;
    }

    private Locale parseLocale(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                Locale parsed = Locale.forLanguageTag(raw.trim().replace('_', '-'));
                if (parsed.getLanguage() != null && !parsed.getLanguage().isBlank()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("无效的 App locale，回退系统 locale: {}", raw);
            }
        }
        return Locale.getDefault();
    }

    private record Region(String location, String language, Locale locale) {
    }
}
