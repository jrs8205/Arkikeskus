package org.jrs82.fsclock.mobile;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Display;

import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/** Lukee passiivisesti saatavilla olevat laitetiedot ja muotoilee ne valmiiksi näytettäväksi.
 *  Kaikki metodit on tarkoitettu ajettavaksi taustasäikeessä (MobileMainActivity hoitaa).
 *  /proc- ja /sys-luvut sekä valmistajakohtaiset kentät on suojattu try/catchilla — palautetaan
 *  vain se mitä saadaan, ei koskaan kaaduta puuttuvaan arvoon. */
final class DeviceInfoReaders {

    private DeviceInfoReaders() {}

    // ---- Akku (ei lupia) ----
    static CharSequence battery(Context ctx) {
        StringBuilder sb = new StringBuilder();
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (bm != null) {
            int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (cap >= 0 && cap <= 100) row(sb, "Varaus", cap + " %");
        }
        Integer voltageMv = null;
        if (i != null) {
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            row(sb, "Lataustila", batteryStatus(status));
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (plugged > 0) row(sb, "Lähde", pluggedText(plugged));
            int volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (volt > 0) { row(sb, "Jännite", volt + " mV"); voltageMv = volt; }
            int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE && temp > -1000) {
                row(sb, "Lämpötila", String.format(Locale.US, "%.1f °C", temp / 10.0));
            }
            int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            if (health > 0) row(sb, "Kunto", healthText(health));
            String tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (!TextUtils.isEmpty(tech)) row(sb, "Teknologia", tech);
        }
        if (bm != null) {
            int currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentUa != Integer.MIN_VALUE && currentUa != 0) {
                row(sb, "Virta", (currentUa / 1000) + " mA");
                if (voltageMv != null) {
                    double watts = (voltageMv / 1000.0) * (currentUa / 1_000_000.0);
                    row(sb, "Teho", String.format(Locale.US, "%.1f W", Math.abs(watts)));
                }
            }
            long charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (charge != Long.MIN_VALUE && charge > 0) row(sb, "Varausta jäljellä", (charge / 1000) + " mAh");
            long energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
            if (energy != Long.MIN_VALUE && energy > 0) {
                row(sb, "Energia", String.format(Locale.US, "%.0f mWh", energy / 1_000_000.0));
            }
        }
        Integer cycles = tryReadCycleCount();
        if (cycles != null) row(sb, "Latauskertoja", String.valueOf(cycles));
        return trimmed(sb, "Akun tietoja ei saatu.");
    }

    // ---- Laitteisto (ei lupia) ----
    static CharSequence hardware() {
        StringBuilder sb = new StringBuilder();
        row(sb, "Valmistaja", Build.MANUFACTURER);
        row(sb, "Malli", Build.MODEL);
        row(sb, "Laitetunnus", Build.DEVICE);
        row(sb, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        if (!TextUtils.isEmpty(Build.VERSION.SECURITY_PATCH)) {
            row(sb, "Tietoturvapäivitys", Build.VERSION.SECURITY_PATCH);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            String soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
            if (!soc.isEmpty() && !soc.equalsIgnoreCase("unknown unknown")) row(sb, "SoC", soc);
        }
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            row(sb, "ABIt", TextUtils.join(", ", Build.SUPPORTED_ABIS));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        row(sb, "CPU-ytimiä", String.valueOf(cores));
        String cpuModel = parseCpuModel();
        if (cpuModel != null) row(sb, "CPU", cpuModel);
        String freqs = cpuMaxFreqSummary(cores);
        if (freqs != null) row(sb, "Maksimikello", freqs);
        return trimmed(sb, "Laitteistotietoja ei saatu.");
    }

    // ---- Muisti ja tallennustila (ei lupia) ----
    static CharSequence memory(Context ctx) {
        StringBuilder sb = new StringBuilder();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            row(sb, "RAM yhteensä", fmtBytes(mi.totalMem));
            row(sb, "RAM vapaana", fmtBytes(mi.availMem));
            row(sb, "Vähän muistia", mi.lowMemory ? "kyllä" : "ei");
        }
        try {
            Debug.MemoryInfo dmi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(dmi);
            row(sb, "Sovelluksen muisti (PSS)", (dmi.getTotalPss() / 1024) + " MB");
        } catch (Exception ignored) { }
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            row(sb, "Tallennustila yhteensä", fmtBytes(stat.getTotalBytes()));
            row(sb, "Tallennustila vapaana", fmtBytes(stat.getAvailableBytes()));
        } catch (Exception ignored) { }
        return trimmed(sb, "Muistitietoja ei saatu.");
    }

    // ---- Näyttö (ei lupia; vaatii visuaalisen kontekstin = Activity) ----
    static CharSequence display(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            row(sb, "Tarkkuus", dm.widthPixels + " × " + dm.heightPixels + " px");
            row(sb, "Tiheys", dm.densityDpi + " dpi (" + String.format(Locale.US, "%.1f×", dm.density) + ")");
        } catch (Exception ignored) { }
        try {
            Display d = ctx.getDisplay();
            if (d != null) {
                row(sb, "Virkistystaajuus", Math.round(d.getRefreshRate()) + " Hz");
                Display.Mode[] modes = d.getSupportedModes();
                if (modes != null && modes.length > 1) {
                    StringBuilder hz = new StringBuilder();
                    for (Display.Mode m : modes) {
                        if (hz.length() > 0) hz.append(", ");
                        hz.append(Math.round(m.getRefreshRate())).append(" Hz");
                    }
                    row(sb, "Tuetut tilat", hz.toString());
                }
                if (d.getHdrCapabilities() != null
                        && d.getHdrCapabilities().getSupportedHdrTypes().length > 0) {
                    row(sb, "HDR", "tuettu");
                }
            }
        } catch (Exception ignored) { }
        return trimmed(sb, "Näyttötietoja ei saatu.");
    }

    // ---- Sensorit (ei lupia, vain listaus) ----
    static CharSequence sensors(Context ctx) {
        StringBuilder sb = new StringBuilder();
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
            if (list != null && !list.isEmpty()) {
                row(sb, "Antureita", String.valueOf(list.size()));
                sb.append('\n');
                for (Sensor s : list) {
                    sb.append("• ").append(s.getName());
                    if (!TextUtils.isEmpty(s.getVendor())) sb.append("  —  ").append(s.getVendor());
                    sb.append('\n');
                }
            }
        }
        return trimmed(sb, "Sensoreita ei löytynyt.");
    }

    // ---- WiFi (perustiedot ilman lupaa; SSID ja skannaus vaativat sijaintiluvan) ----
    static CharSequence wifi(Context ctx) {
        StringBuilder sb = new StringBuilder();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            return "WiFi ei ole käytössä.";
        }
        WifiInfo info = wm.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            if (ssid != null && !ssid.contains("unknown") && !ssid.equals("0x")) {
                row(sb, "Verkko", ssid.replace("\"", ""));
            }
            int rssi = info.getRssi();
            if (rssi > -127 && rssi <= 0) row(sb, "Signaali", rssi + " dBm");
            int link = info.getLinkSpeed();
            if (link > 0) row(sb, "Linkkinopeus", link + " Mbps");
            int rx = info.getRxLinkSpeedMbps();
            int tx = info.getTxLinkSpeedMbps();
            if (rx > 0) row(sb, "Vastaanotto", rx + " Mbps");
            if (tx > 0) row(sb, "Lähetys", tx + " Mbps");
            int freq = info.getFrequency();
            if (freq > 0) row(sb, "Taajuus", freq + " MHz (" + band(freq) + ", kanava " + channel(freq) + ")");
            row(sb, "WiFi-standardi", wifiStandard(info.getWifiStandard()));
        }
        appendIpAndDns(ctx, sb);
        return trimmed(sb, "WiFi-tietoja ei saatu.");
    }

    private static void appendIpAndDns(Context ctx, StringBuilder sb) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr != null && addr.getHostAddress() != null
                            && addr.getHostAddress().indexOf(':') < 0) {
                        row(sb, "IP-osoite", addr.getHostAddress());
                        break;
                    }
                }
                if (lp.getDnsServers() != null && !lp.getDnsServers().isEmpty()) {
                    StringBuilder dns = new StringBuilder();
                    for (InetAddress d : lp.getDnsServers()) {
                        if (d.getHostAddress() != null && d.getHostAddress().indexOf(':') < 0) {
                            if (dns.length() > 0) dns.append(", ");
                            dns.append(d.getHostAddress());
                        }
                    }
                    if (dns.length() > 0) row(sb, "DNS", dns.toString());
                }
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null) {
                int down = caps.getLinkDownstreamBandwidthKbps();
                int up = caps.getLinkUpstreamBandwidthKbps();
                if (down > 0) row(sb, "Arvioitu lataus", (down / 1000) + " Mbps");
                if (up > 0) row(sb, "Arvioitu lähetys", (up / 1000) + " Mbps");
            }
        } catch (Exception ignored) { }
    }

    // ---- Mobiiliverkko (best-effort; verkkotyyppi vaatii READ_PHONE_STATE, solutiedot FINE) ----
    static CharSequence cellular(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "Laitteessa ei ole mobiiliverkkoa.";
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return "Mobiiliverkon tietoja ei saatu.";
        StringBuilder sb = new StringBuilder();
        try {
            String op = tm.getNetworkOperatorName();
            if (!TextUtils.isEmpty(op)) row(sb, "Operaattori", op);
        } catch (Exception ignored) { }
        try {
            String mm = tm.getNetworkOperator();
            if (mm != null && mm.length() >= 5) {
                row(sb, "MCC / MNC", mm.substring(0, 3) + " / " + mm.substring(3));
            }
        } catch (Exception ignored) { }
        try {
            row(sb, "Roaming", tm.isNetworkRoaming() ? "kyllä" : "ei");
        } catch (Exception ignored) { }

        boolean nrSignal = false;
        try {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) {
                for (CellSignalStrength c : ss.getCellSignalStrengths()) {
                    if (c instanceof CellSignalStrengthLte) {
                        CellSignalStrengthLte lte = (CellSignalStrengthLte) c;
                        if (valid(lte.getDbm())) row(sb, "LTE-signaali", lte.getDbm() + " dBm");
                        if (valid(lte.getRsrp())) row(sb, "  RSRP", lte.getRsrp() + " dBm");
                        if (valid(lte.getRsrq())) row(sb, "  RSRQ", lte.getRsrq() + " dB");
                        if (valid(lte.getRssnr())) row(sb, "  RSSNR", lte.getRssnr() + " dB");
                    } else if (c instanceof CellSignalStrengthNr) {
                        nrSignal = true;
                        CellSignalStrengthNr nr = (CellSignalStrengthNr) c;
                        if (valid(nr.getDbm())) row(sb, "5G-signaali", nr.getDbm() + " dBm");
                        if (valid(nr.getSsRsrp())) row(sb, "  SS-RSRP", nr.getSsRsrp() + " dBm");
                        if (valid(nr.getSsRsrq())) row(sb, "  SS-RSRQ", nr.getSsRsrq() + " dB");
                        if (valid(nr.getSsSinr())) row(sb, "  SS-SINR", nr.getSsSinr() + " dB");
                    } else if (valid(c.getDbm())) {
                        row(sb, "Signaali", c.getDbm() + " dBm");
                    }
                }
            }
        } catch (Exception ignored) { }

        try {
            int nt = tm.getDataNetworkType();
            if (nt == TelephonyManager.NETWORK_TYPE_NR) {
                row(sb, "Verkkotyyppi", "5G (SA, arvio)");
            } else if (nrSignal && nt == TelephonyManager.NETWORK_TYPE_LTE) {
                row(sb, "Verkkotyyppi", "5G (NSA, arvio) · LTE");
            } else {
                row(sb, "Verkkotyyppi", networkTypeName(nt));
            }
        } catch (SecurityException e) {
            row(sb, "Verkkotyyppi", "vaatii puhelimen tila -luvan");
        } catch (Exception ignored) { }

        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) {
                for (CellInfo info : cells) {
                    if (!info.isRegistered()) continue;
                    if (info instanceof CellInfoLte) {
                        CellIdentityLte id = ((CellInfoLte) info).getCellIdentity();
                        if (valid(id.getCi())) row(sb, "Solu-ID (LTE)", String.valueOf(id.getCi()));
                        if (valid(id.getPci())) row(sb, "  PCI", String.valueOf(id.getPci()));
                        if (valid(id.getTac())) row(sb, "  TAC", String.valueOf(id.getTac()));
                        if (valid(id.getEarfcn())) row(sb, "  EARFCN", String.valueOf(id.getEarfcn()));
                    } else if (info instanceof CellInfoNr) {
                        CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) info).getCellIdentity();
                        long nci = id.getNci();
                        if (nci != Long.MAX_VALUE && nci != CellInfo.UNAVAILABLE_LONG) {
                            row(sb, "Solu-ID (NR)", String.valueOf(nci));
                        }
                        if (valid(id.getPci())) row(sb, "  PCI", String.valueOf(id.getPci()));
                        if (valid(id.getTac())) row(sb, "  TAC", String.valueOf(id.getTac()));
                        if (valid(id.getNrarfcn())) row(sb, "  NRARFCN", String.valueOf(id.getNrarfcn()));
                    }
                    break;
                }
            }
        } catch (SecurityException ignored) {
            // FINE-lupa puuttuu → solutiedot jätetään pois
        } catch (Exception ignored) { }

        return trimmed(sb, "Mobiiliverkon tietoja ei saatu.");
    }

    // ---- SIM / liittymät (best-effort; vaatii READ_PHONE_STATE; ei IMEI/IMSI Android 10+) ----
    static CharSequence sim(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "Laitteessa ei ole SIM-tukea.";
        }
        SubscriptionManager sm = (SubscriptionManager)
                ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return "SIM-tietoja ei saatu.";
        int dataSub = defaultSubId(0), voiceSub = defaultSubId(1), smsSub = defaultSubId(2);
        StringBuilder sb = new StringBuilder();
        try {
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                sb.append("Ei aktiivisia SIM-liittymiä.");
            } else {
                int n = 0;
                for (SubscriptionInfo s : subs) {
                    if (n > 0) sb.append('\n');
                    n++;
                    CharSequence carrier = s.getCarrierName();
                    CharSequence disp = s.getDisplayName();
                    sb.append("SIM ").append(n).append(": ")
                            .append(carrier != null ? carrier : "?");
                    if (disp != null && !disp.toString().equals(String.valueOf(carrier))) {
                        sb.append("  (").append(disp).append(")");
                    }
                    sb.append('\n');
                    row(sb, "  Tyyppi", s.isEmbedded() ? "eSIM" : "fyysinen SIM");
                    row(sb, "  Slotti", String.valueOf(s.getSimSlotIndex()));
                    String mcc = s.getMccString();
                    String mnc = s.getMncString();
                    if (mcc != null && mnc != null) row(sb, "  MCC/MNC", mcc + " / " + mnc);
                    if (!TextUtils.isEmpty(s.getCountryIso())) {
                        row(sb, "  Maa", s.getCountryIso().toUpperCase(Locale.ROOT));
                    }
                    row(sb, "  Roaming", s.getDataRoaming() == SubscriptionManager.DATA_ROAMING_ENABLE
                            ? "sallittu" : "estetty");
                    int subId = s.getSubscriptionId();
                    StringBuilder roles = new StringBuilder();
                    if (subId == dataSub) roles.append("data");
                    if (subId == voiceSub) {
                        if (roles.length() > 0) roles.append(", ");
                        roles.append("puhelut");
                    }
                    if (subId == smsSub) {
                        if (roles.length() > 0) roles.append(", ");
                        roles.append("SMS");
                    }
                    if (roles.length() > 0) row(sb, "  Oletus", roles.toString());
                }
            }
        } catch (SecurityException e) {
            return "SIM-tiedot vaativat puhelimen tila -luvan.";
        } catch (Exception e) {
            return "SIM-tietoja ei saatu.";
        }
        sb.append("\n\nIMEI / sarjanumero: ei saatavilla (Android 10+ rajoitus)");
        return trimmed(sb, "SIM-tietoja ei saatu.");
    }

    private static int defaultSubId(int which) {
        try {
            switch (which) {
                case 0: return SubscriptionManager.getActiveDataSubscriptionId();
                case 1: return SubscriptionManager.getDefaultVoiceSubscriptionId();
                default: return SubscriptionManager.getDefaultSmsSubscriptionId();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean valid(int v) {
        return v != Integer.MAX_VALUE && v != Integer.MIN_VALUE;
    }

    private static String networkTypeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G NR";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE (4G)";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "ei yhteyttä";
            default: return "tyyppi " + t;
        }
    }

    // ---- Apurit ----
    private static void row(StringBuilder sb, String label, String value) {
        if (value == null) return;
        sb.append(label).append(": ").append(value).append('\n');
    }

    private static CharSequence trimmed(StringBuilder sb, String emptyMsg) {
        String s = sb.toString().trim();
        return s.isEmpty() ? emptyMsg : s;
    }

    private static String fmtBytes(long bytes) {
        if (bytes <= 0) return "0";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    private static String band(int freqMhz) {
        if (freqMhz >= 2401 && freqMhz <= 2495) return "2,4 GHz";
        if (freqMhz >= 5150 && freqMhz <= 5895) return "5 GHz";
        if (freqMhz >= 5925 && freqMhz <= 7125) return "6 GHz";
        return "?";
    }

    private static int channel(int freqMhz) {
        if (freqMhz >= 2401 && freqMhz <= 2495) return (freqMhz - 2407) / 5;
        if (freqMhz >= 5150 && freqMhz <= 5895) return (freqMhz - 5000) / 5;
        if (freqMhz >= 5925 && freqMhz <= 7125) return (freqMhz - 5950) / 5 + 1;
        return 0;
    }

    private static String wifiStandard(int standard) {
        switch (standard) {
            case ScanStd.LEGACY: return "802.11a/b/g";
            case ScanStd.N: return "Wi-Fi 4 (n)";
            case ScanStd.AC: return "Wi-Fi 5 (ac)";
            case ScanStd.AX: return "Wi-Fi 6 (ax)";
            case ScanStd.AD: return "802.11ad";
            case ScanStd.BE: return "Wi-Fi 7 (be)";
            default: return "tuntematon";
        }
    }

    /** WifiInfo.WIFI_STANDARD_* arvot (vältetään suora viittaus selkeyden vuoksi). */
    private static final class ScanStd {
        static final int LEGACY = 1, N = 4, AC = 5, AX = 6, AD = 7, BE = 8;
    }

    private static String batteryStatus(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "latautuu";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "purkautuu";
            case BatteryManager.BATTERY_STATUS_FULL: return "täynnä";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "ei lataudu";
            default: return "tuntematon";
        }
    }

    private static String pluggedText(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_AC: return "verkkovirta";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "langaton";
            default: return "kytketty";
        }
    }

    private static String healthText(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "hyvä";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "ylikuumentunut";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "loppuun kulunut";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "ylijännite";
            case BatteryManager.BATTERY_HEALTH_COLD: return "kylmä";
            default: return "tuntematon";
        }
    }

    private static String parseCpuModel() {
        try {
            String text = readFile(new File("/proc/cpuinfo"));
            if (text == null) return null;
            for (String line : text.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("hardware") || lower.startsWith("model name")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0 && idx + 1 < line.length()) return line.substring(idx + 1).trim();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String cpuMaxFreqSummary(int cores) {
        try {
            long maxKhz = 0;
            for (int c = 0; c < cores; c++) {
                String s = readFile(new File("/sys/devices/system/cpu/cpu" + c
                        + "/cpufreq/cpuinfo_max_freq"));
                if (s != null) {
                    long khz = Long.parseLong(s.trim());
                    if (khz > maxKhz) maxKhz = khz;
                }
            }
            if (maxKhz > 0) return String.format(Locale.US, "%.2f GHz", maxKhz / 1_000_000.0);
        } catch (Exception ignored) { }
        return null;
    }

    private static Integer tryReadCycleCount() {
        try {
            String s = readFile(new File("/sys/class/power_supply/battery/cycle_count"));
            if (s != null) return Integer.parseInt(s.trim());
        } catch (Exception ignored) { }
        return null;
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 2000) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
