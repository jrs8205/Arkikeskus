package org.jrs82.fsclock.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Rakentaa itsenäisen (offline-toimivan) HTML-yhteenvedon askel- ja kalorihistoriasta ja
 *  tallentaa sen Download/Arkikeskus-kansioon MediaStorella. Mallina {@code CsvExporter}.
 *
 *  HTML on tarkoituksella riippumaton ulkoisista resursseista (inline-CSS + inline-SVG, ei
 *  skriptejä eikä verkkofontteja), joten se aukeaa selaimessa ilman verkkoa. Teema on askel-/
 *  kalorihenkinen: vihreä askeleille, lämmin oranssi kaloreille. Kaikki teksti suomeksi.
 *  Kaikki dynaaminen teksti HTML-escapataan. */
public final class StepsHtmlExporter {

    /** Yksi rivi (päivä / viikko / kuukausi). */
    public static final class Row {
        public final String label;
        public final long steps;
        public final int activeKcal;   // 0 = ei tietoa
        public final int totalKcal;    // 0 = ei tietoa
        public final boolean estimated; // true = kalorit oma arvio (ei Health Connect)

        public Row(String label, long steps, int activeKcal, int totalKcal, boolean estimated) {
            this.label = label;
            this.steps = steps;
            this.activeKcal = activeKcal;
            this.totalKcal = totalKcal;
            this.estimated = estimated;
        }
    }

    public static final class Report {
        public final String sourceLabel;
        public final long todaySteps;
        public final int todayActive;
        public final int todayTotal;
        public final boolean todayEstimated;
        public final List<Row> days;
        public final List<Row> weeks;
        public final List<Row> months;

        public Report(String sourceLabel, long todaySteps, int todayActive, int todayTotal,
                      boolean todayEstimated, List<Row> days, List<Row> weeks, List<Row> months) {
            this.sourceLabel = sourceLabel;
            this.todaySteps = todaySteps;
            this.todayActive = todayActive;
            this.todayTotal = todayTotal;
            this.todayEstimated = todayEstimated;
            this.days = days;
            this.weeks = weeks;
            this.months = months;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String fileName;
        public final Uri uri;
        public final String error;

        private Result(boolean ok, String fileName, Uri uri, String error) {
            this.ok = ok;
            this.fileName = fileName;
            this.uri = uri;
            this.error = error;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("d.M.yyyy 'klo' HH:mm");
    private static final String RELATIVE_SUBDIR = Environment.DIRECTORY_DOWNLOADS + "/Arkikeskus";

    // Inline-SVG-ikonit (currentColor; yksinkertaiset attribuutit, jotta Java-literaalit pysyvät siisteinä).
    private static final String SVG_FOOT =
            "<svg viewBox='0 0 24 24' aria-hidden='true'><path fill='currentColor' d='M13.5 5.5c1.1 0 "
            + "2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9 7 23h2.1l1.4-6.3 2.5 2.3V23h2v-6.5l-2.1-2 "
            + ".6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5 0-.8.1L6 "
            + "8.3V13h2V9.6z'/></svg>";
    private static final String SVG_FIRE =
            "<svg viewBox='0 0 24 24' aria-hidden='true'><path fill='currentColor' d='M13.5.67s.74 2.65.74 "
            + "4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 "
            + "4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-"
            + "1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-"
            + "4.8 4.8z'/></svg>";
    private static final String SVG_CAL =
            "<svg viewBox='0 0 24 24' aria-hidden='true'><path fill='currentColor' d='M17 12h-5v5h5v-5zM16 "
            + "1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-"
            + "1V1h-2zm3 18H5V8h14z'/></svg>";

    private StepsHtmlExporter() {}

    public static String buildFileName() {
        return "arkikeskus_askeleet_" + LocalDateTime.now().format(STAMP)
                + "_" + safeModel() + ".html";
    }

    /** Kirjoittaa HTML:n Download/Arkikeskus-kansioon. Ei koskaan heitä Exceptionia. */
    public static Result export(Context context, Report report, String fileName) {
        Uri uri = null;
        try {
            String html = buildHtml(report);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/html");
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_SUBDIR);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                return new Result(false, fileName, null, "MediaStore insert palautti null");
            }

            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(html);
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);

            return new Result(true, fileName, uri, null);
        } catch (Exception e) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
            return new Result(false, fileName, null, String.valueOf(e.getMessage()));
        }
    }

    private static String buildHtml(Report r) {
        // Yleiskatsausluvut tarkimmasta saatavilla olevasta jaosta (päivät).
        long totalSteps = 0, bestDay = 0, totalActive = 0, totalTotal = 0;
        for (Row row : r.days) {
            totalSteps += row.steps;
            bestDay = Math.max(bestDay, row.steps);
            totalActive += Math.max(0, row.activeKcal);
            totalTotal += Math.max(0, row.totalKcal);
        }
        int dayCount = r.days.size();
        long avgSteps = dayCount > 0 ? Math.round((double) totalSteps / dayCount) : 0;

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html>\n<html lang=\"fi\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Arkikeskus – Askeleet ja kalorit</title>\n")
                .append("<style>").append(CSS).append("</style>\n</head>\n<body>\n<div class=\"wrap\">\n");

        // Hero: otsikko + tämän päivän iso luku + kalori-chipit.
        sb.append("<header class=\"hero\">\n")
                .append("<div class=\"htop\"><span class=\"logo\">").append(SVG_FOOT).append("</span>")
                .append("<div><h1>Askeleet &amp; kalorit</h1>")
                .append("<p class=\"sub\">Arkikeskus-yhteenveto</p></div></div>\n")
                .append("<div class=\"today\"><div class=\"tnum\">").append(num(r.todaySteps))
                .append("</div><div class=\"tlabel\">askelta tänään</div>");
        String chips = todayChips(r);
        if (!chips.isEmpty()) sb.append("<div class=\"chips\">").append(chips).append("</div>");
        sb.append("</div>\n<p class=\"meta\">").append(esc(model())).append(" · viety ")
                .append(esc(LocalDateTime.now().format(HUMAN))).append(" · lähde: ")
                .append(esc(r.sourceLabel)).append("</p>\n</header>\n");

        // Yleiskatsauskortit.
        sb.append("<section class=\"stats\">\n");
        sb.append(statCard("step", num(totalSteps), "Askeleet yhteensä",
                dayCount > 0 ? "viimeiset " + dayCount + " pv" : "ei dataa"));
        sb.append(statCard("best", num(bestDay), "Paras päivä", "askelta"));
        sb.append(statCard("avg", num(avgSteps), "Keskiarvo / päivä", "askelta"));
        if (totalActive > 0 || totalTotal > 0) {
            boolean useActive = totalActive > 0;
            sb.append(statCard("fire", num(useActive ? totalActive : totalTotal), "Kalorit yhteensä",
                    useActive ? "aktiiviset kcal" : "kcal yhteensä"));
        }
        sb.append("</section>\n");

        appendBlock(sb, "Päivät", "day", r.days);
        appendBlock(sb, "Viikot", "week", r.weeks);
        appendBlock(sb, "Kuukaudet", "month", r.months);

        sb.append("<footer>Luotu Arkikeskus-sovelluksella. Data Health Connectista tai puhelimen "
                + "askelanturista. Merkintä <em>~arvio</em> on Arkikeskuksen oma kalorilaskelma "
                + "(askelista, ei tarkka).</footer>\n");
        sb.append("</div>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static String todayChips(Report r) {
        StringBuilder c = new StringBuilder();
        String pre = r.todayEstimated ? "~" : "";
        if (r.todayActive > 0) {
            c.append("<span class=\"chip\">").append(SVG_FIRE).append(pre).append(r.todayActive)
                    .append(" kcal aktiiviset</span>");
        }
        if (r.todayTotal > 0) {
            c.append("<span class=\"chip chip-tot\">").append(pre).append(r.todayTotal)
                    .append(" kcal yhteensä</span>");
        }
        return c.toString();
    }

    private static String statCard(String kind, String value, String label, String caption) {
        return "<div class=\"stat stat-" + kind + "\"><div class=\"sval\">" + value + "</div>"
                + "<div class=\"slabel\">" + esc(label) + "</div>"
                + "<div class=\"scap\">" + esc(caption) + "</div></div>";
    }

    private static void appendBlock(StringBuilder sb, String title, String kind, List<Row> rows) {
        String icon = "day".equals(kind) ? SVG_FOOT : SVG_CAL;
        sb.append("<section class=\"block\">\n<div class=\"bhead\"><span class=\"badge badge-")
                .append(kind).append("\">").append(icon).append("</span><h2>").append(esc(title))
                .append("</h2>");
        if (rows != null && !rows.isEmpty()) {
            long tot = 0;
            for (Row row : rows) tot += row.steps;
            sb.append("<span class=\"count\">").append(rows.size()).append(" kpl · ")
                    .append(num(tot)).append(" askelta</span>");
        }
        sb.append("</div>\n");
        if (rows == null || rows.isEmpty()) {
            sb.append("<p class=\"empty\">Ei vielä dataa.</p>\n</section>\n");
            return;
        }
        long max = 1;
        for (Row row : rows) max = Math.max(max, row.steps);
        sb.append("<div class=\"tw\"><table>\n<thead><tr><th>Ajanjakso</th><th class=\"r\">Askeleet</th>")
                .append("<th class=\"r\">Aktiiviset</th><th class=\"r\">Yhteensä</th></tr></thead>\n<tbody>\n");
        for (Row row : rows) {
            int pct = (int) Math.round(row.steps * 100.0 / max);
            sb.append("<tr><td class=\"period\">").append(esc(row.label)).append("</td>")
                    .append("<td class=\"r steps\" data-label=\"Askeleet\"><div class=\"bar\" style=\"width:").append(pct)
                    .append("%\"></div><span>").append(num(row.steps)).append("</span></td>")
                    .append("<td class=\"r kcal\" data-label=\"Aktiiviset\">").append(kcalCell(row.activeKcal, row.estimated)).append("</td>")
                    .append("<td class=\"r\" data-label=\"Yhteensä\">").append(kcalCell(row.totalKcal, row.estimated)).append("</td>")
                    .append("</tr>\n");
        }
        sb.append("</tbody>\n</table></div>\n</section>\n");
    }

    private static String kcalCell(int kcal, boolean estimated) {
        if (kcal <= 0) return "<span class=\"muted\">–</span>";
        if (estimated) return "~" + kcal + " <span class=\"muted\">arvio</span>";
        return String.valueOf(kcal);
    }

    /** Tuhaterotin välilyönnillä (suomalainen tapa). */
    private static String num(long v) {
        return String.format(Locale.US, "%,d", v).replace(',', ' ');
    }

    private static String model() {
        return Build.MODEL == null || Build.MODEL.isEmpty() ? "?" : Build.MODEL;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String safeModel() {
        String m = Build.MODEL;
        if (m == null || m.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(m.length());
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '+') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static final String CSS =
            ":root{--green:#10B981;--green-d:#0E9F6E;--teal:#0EA5A4;--amber:#F59E0B;--amber-d:#D97706;"
            + "--violet:#7C3AED;--blue:#2563EB;--ink:#10241C;--muted:#5B6B62;--line:#E2E8E2;--card:#fff}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;color:var(--ink);font-family:-apple-system,Roboto,'Segoe UI',Helvetica,Arial,sans-serif;"
            + "line-height:1.45;background:linear-gradient(165deg,#E8F7F0 0%,#EEF2F0 38%,#FBF1E6 100%);"
            + "background-attachment:fixed;-webkit-text-size-adjust:100%}"
            + ".wrap{max-width:780px;margin:0 auto;padding:18px 16px 40px}"
            // Hero
            + ".hero{background:linear-gradient(135deg,var(--green) 0%,var(--teal) 100%);color:#fff;"
            + "border-radius:22px;padding:22px 22px 20px;box-shadow:0 12px 30px rgba(16,185,129,.28);"
            + "position:relative;overflow:hidden}"
            + ".hero:after{content:'';position:absolute;right:-40px;bottom:-50px;width:200px;height:200px;"
            + "background:radial-gradient(circle,rgba(255,255,255,.18),transparent 70%)}"
            + ".htop{display:flex;align-items:center;gap:14px}"
            + ".logo{display:inline-flex;align-items:center;justify-content:center;width:48px;height:48px;"
            + "background:rgba(255,255,255,.22);border-radius:14px;flex:0 0 auto}"
            + ".logo svg{width:28px;height:28px;color:#fff}"
            + ".hero h1{margin:0;font-size:22px;font-weight:800;letter-spacing:-.2px}"
            + ".hero .sub{margin:2px 0 0;font-size:13px;opacity:.9}"
            + ".today{margin-top:16px;text-align:center;position:relative;z-index:1}"
            + ".tnum{font-size:62px;font-weight:800;line-height:1;letter-spacing:-1px;"
            + "font-variant-numeric:tabular-nums;text-shadow:0 2px 8px rgba(0,0,0,.12)}"
            + ".tlabel{font-size:14px;opacity:.92;margin-top:2px}"
            + ".chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin-top:14px}"
            + ".chip{display:inline-flex;align-items:center;gap:6px;background:rgba(255,255,255,.96);"
            + "color:var(--amber-d);font-weight:700;font-size:13px;padding:7px 12px;border-radius:999px}"
            + ".chip svg{width:15px;height:15px;color:var(--amber)}"
            + ".chip-tot{color:#374151}"
            + ".meta{margin:14px 0 0;font-size:12px;opacity:.85;text-align:center;position:relative;z-index:1}"
            // Stat cards
            + ".stats{display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin:16px 0}"
            + ".stat{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px 16px;"
            + "box-shadow:0 4px 14px rgba(16,36,28,.05);position:relative;overflow:hidden}"
            + ".stat:before{content:'';position:absolute;left:0;top:0;bottom:0;width:5px}"
            + ".stat-step:before{background:var(--green)}.stat-best:before{background:var(--violet)}"
            + ".stat-avg:before{background:var(--blue)}.stat-fire:before{background:var(--amber)}"
            + ".sval{font-size:27px;font-weight:800;line-height:1.1;font-variant-numeric:tabular-nums}"
            + ".stat-fire .sval{color:var(--amber-d)}.stat-step .sval{color:var(--green-d)}"
            + ".slabel{font-size:13px;font-weight:600;margin-top:3px}"
            + ".scap{font-size:11.5px;color:var(--muted);margin-top:1px}"
            // Blocks / tables
            + ".block{margin:22px 0 0}"
            + ".bhead{display:flex;align-items:center;gap:10px;margin-bottom:10px}"
            + ".badge{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;"
            + "border-radius:11px;flex:0 0 auto}.badge svg{width:19px;height:19px;color:#fff}"
            + ".badge-day{background:linear-gradient(135deg,var(--green),var(--teal))}"
            + ".badge-week{background:linear-gradient(135deg,var(--blue),#4F46E5)}"
            + ".badge-month{background:linear-gradient(135deg,var(--violet),#9333EA)}"
            + ".bhead h2{margin:0;font-size:17px;font-weight:700;flex:1}"
            + ".count{font-size:12px;color:var(--muted);font-weight:600;text-align:right}"
            + ".empty{color:var(--muted);font-size:14px;margin:0 0 4px}"
            + ".tw{background:var(--card);border:1px solid var(--line);border-radius:16px;overflow:hidden;"
            + "box-shadow:0 4px 14px rgba(16,36,28,.05)}"
            + "table{width:100%;border-collapse:collapse}"
            + "th,td{padding:10px 14px;font-size:14px;border-bottom:1px solid var(--line);text-align:left}"
            + "thead th{background:#F1F6F2;color:#37463E;font-weight:700;font-size:12.5px;"
            + "text-transform:uppercase;letter-spacing:.4px}"
            + "tbody tr:nth-child(even){background:#FAFCFA}tbody tr:last-child td{border-bottom:0}"
            + ".r{text-align:right}.period{font-weight:600;white-space:nowrap}"
            + ".muted{color:#9AA8A0;font-weight:400}.kcal{color:var(--amber-d);font-weight:600}"
            + "td.steps{position:relative;min-width:120px}"
            + "td.steps .bar{position:absolute;left:0;top:6px;bottom:6px;border-radius:6px;z-index:0;"
            + "background:linear-gradient(90deg,rgba(16,185,129,.18),rgba(14,165,164,.32))}"
            + "td.steps span{position:relative;z-index:1;font-weight:700;font-variant-numeric:tabular-nums}"
            + "footer{margin:26px 2px 0;color:var(--muted);font-size:12px;text-align:center;line-height:1.5}"
            + "footer em{color:var(--amber-d);font-style:normal}"
            + "@media(max-width:430px){.stats{grid-template-columns:1fr 1fr}.tnum{font-size:54px}"
            + "th,td{padding:9px 10px;font-size:13px}}"
            // Kapeilla näytöillä (puhelin) taulukot pinotaan korteiksi: ei vaakavieritystä eikä
            // oikean reunan leikkautumista. data-label tuo sarakeotsikon jokaiselle riville.
            + "@media(max-width:560px){.tw{overflow:visible;border:0;background:transparent;"
            + "box-shadow:none;border-radius:0}table,tbody,tr,td{display:block;width:100%}thead{display:none}"
            + "tr{background:var(--card);border:1px solid var(--line);border-radius:14px;margin:0 0 10px;"
            + "padding:8px 14px;box-shadow:0 4px 14px rgba(16,36,28,.05)}"
            + "tbody tr:nth-child(even){background:var(--card)}"
            + "td{border:0;padding:5px 0;text-align:right;overflow:hidden}"
            + "td::before{content:attr(data-label);float:left;color:var(--muted);font-weight:600;font-size:12.5px}"
            + "td.period{text-align:left;font-weight:800;font-size:15px;border-bottom:1px solid var(--line);"
            + "padding:2px 0 7px;margin-bottom:4px;white-space:normal}td.period::before{content:''}"
            + "td.steps{min-width:0;position:static}td.steps .bar{display:none}}";
}
