package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

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
     * 构建当前这一轮的 latest_reminder 内容。
     *
     * @param userTimeZone App 端 IANA 时区，如 {@code Asia/Shanghai}；为空回退系统时区
     * @param userLocale   App 端 BCP-47 locale，如 {@code zh-CN}；仅用于时区无法映射国家时的兜底
     * @return 例如 {@code 2026-11-26,星期三,中国,App,中文}
     */
    public String buildCurrent(String userTimeZone, String userLocale) {
        ZoneId zone = resolveZone(userTimeZone);
        LocalDate today = LocalDate.now(zone);
        Region region = resolveRegion(zone, userLocale);
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, region.locale());
        return today + "," + weekday + "," + region.location() + ",App," + region.language();
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

        String languageTag = country == null || country.isBlank()
                ? fallback.getLanguage()
                : COUNTRY_LANGUAGE.getOrDefault(country, fallback.getLanguage());
        if (languageTag == null || languageTag.isBlank()) {
            languageTag = "en";
        }
        Locale languageLocale = Locale.forLanguageTag(languageTag);
        String language = languageLocale.getDisplayLanguage(languageLocale);
        if (language == null || language.isBlank()) {
            language = languageTag;
        }

        String location = "";
        if (country != null && !country.isBlank()) {
            location = new Locale("", country).getDisplayCountry(languageLocale);
        }
        if (location == null || location.isBlank()) {
            // 时区/国家都取不到时，用 IANA 时区的城市段兜底（如 Asia/Shanghai → Shanghai）
            String id = zone.getId();
            int slash = id.lastIndexOf('/');
            location = slash >= 0 ? id.substring(slash + 1).replace('_', ' ') : id;
        }
        return new Region(location, language, languageLocale);
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
