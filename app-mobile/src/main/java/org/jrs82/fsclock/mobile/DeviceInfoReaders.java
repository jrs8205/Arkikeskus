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

import org.jrs82.fsclock.R;

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

    /** Näkyvien lukujen muotoilu sovelluksen kielellä (desimaalierotin). */
    private static Locale loc(Context ctx) {
        return AppLocale.INSTANCE.current(ctx);
    }

    // ---- Akku (ei lupia) ----
    static CharSequence battery(Context ctx) {
        StringBuilder sb = new StringBuilder();
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        Intent i = null;
        // registerReceiver voi heittää (esim. rajoitettu konteksti) — suojataan kuten sisarmetodit.
        try {
            i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) { }

        if (bm != null) {
            int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (cap >= 0 && cap <= 100) row(sb, ctx.getString(R.string.device_battery_level), cap + " %");
        }
        Integer voltageMv = null;
        if (i != null) {
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            row(sb, ctx.getString(R.string.device_battery_status), batteryStatus(ctx, status));
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (plugged > 0) row(sb, ctx.getString(R.string.device_battery_source), pluggedText(ctx, plugged));
            int volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (volt > 0) { row(sb, ctx.getString(R.string.device_battery_voltage), volt + " mV"); voltageMv = volt; }
            int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE && temp > -1000) {
                row(sb, ctx.getString(R.string.device_battery_temperature),
                        String.format(loc(ctx), "%.1f °C", temp / 10.0));
            }
            int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            if (health > 0) row(sb, ctx.getString(R.string.device_battery_health), healthText(ctx, health));
            String tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (!TextUtils.isEmpty(tech)) row(sb, ctx.getString(R.string.device_battery_technology), tech);
        }
        if (bm != null) {
            int currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentUa != Integer.MIN_VALUE && currentUa != 0) {
                row(sb, ctx.getString(R.string.device_battery_current), (currentUa / 1000) + " mA");
                if (voltageMv != null) {
                    double watts = (voltageMv / 1000.0) * (currentUa / 1_000_000.0);
                    row(sb, ctx.getString(R.string.device_battery_power),
                            String.format(loc(ctx), "%.1f W", Math.abs(watts)));
                }
            }
            long charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (charge != Long.MIN_VALUE && charge > 0) {
                row(sb, ctx.getString(R.string.device_battery_charge_left), (charge / 1000) + " mAh");
            }
            long energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
            if (energy != Long.MIN_VALUE && energy > 0) {
                row(sb, ctx.getString(R.string.device_battery_energy),
                        String.format(loc(ctx), "%.0f mWh", energy / 1_000_000.0));
            }
        }
        Integer cycles = tryReadCycleCount();
        if (cycles != null) row(sb, ctx.getString(R.string.device_battery_cycles), String.valueOf(cycles));
        return trimmed(sb, ctx.getString(R.string.device_battery_none));
    }

    // ---- Laitteisto (ei lupia) ----
    static CharSequence hardware(Context ctx) {
        StringBuilder sb = new StringBuilder();
        row(sb, ctx.getString(R.string.device_manufacturer), Build.MANUFACTURER);
        row(sb, ctx.getString(R.string.device_model), Build.MODEL);
        row(sb, ctx.getString(R.string.device_codename), Build.DEVICE);
        row(sb, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        if (!TextUtils.isEmpty(Build.VERSION.SECURITY_PATCH)) {
            row(sb, ctx.getString(R.string.device_security_patch), Build.VERSION.SECURITY_PATCH);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            String soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
            if (!soc.isEmpty() && !soc.equalsIgnoreCase("unknown unknown")) row(sb, "SoC", soc);
        }
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            row(sb, ctx.getString(R.string.device_abis), TextUtils.join(", ", Build.SUPPORTED_ABIS));
        }
        int cores = Runtime.getRuntime().availableProcessors();
        row(sb, ctx.getString(R.string.device_cpu_cores), String.valueOf(cores));
        String cpuModel = parseCpuModel();
        if (cpuModel != null) row(sb, "CPU", cpuModel);
        String freqs = cpuMaxFreqSummary(cores, loc(ctx));
        if (freqs != null) row(sb, ctx.getString(R.string.device_max_clock), freqs);
        return trimmed(sb, ctx.getString(R.string.device_hardware_none));
    }

    // ---- Muisti ja tallennustila (ei lupia) ----
    static CharSequence memory(Context ctx) {
        StringBuilder sb = new StringBuilder();
        Locale locale = loc(ctx);
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            row(sb, ctx.getString(R.string.device_ram_total), fmtBytes(mi.totalMem, locale));
            row(sb, ctx.getString(R.string.device_ram_free), fmtBytes(mi.availMem, locale));
            row(sb, ctx.getString(R.string.device_low_memory),
                    ctx.getString(mi.lowMemory ? R.string.common_yes : R.string.common_no));
        }
        try {
            Debug.MemoryInfo dmi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(dmi);
            row(sb, ctx.getString(R.string.device_app_memory), (dmi.getTotalPss() / 1024) + " MB");
        } catch (Exception ignored) { }
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            row(sb, ctx.getString(R.string.device_storage_total), fmtBytes(stat.getTotalBytes(), locale));
            row(sb, ctx.getString(R.string.device_storage_free), fmtBytes(stat.getAvailableBytes(), locale));
        } catch (Exception ignored) { }
        return trimmed(sb, ctx.getString(R.string.device_memory_none));
    }

    // ---- Näyttö (ei lupia; vaatii visuaalisen kontekstin = Activity) ----
    static CharSequence display(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            row(sb, ctx.getString(R.string.device_resolution), dm.widthPixels + " × " + dm.heightPixels + " px");
            row(sb, ctx.getString(R.string.device_density),
                    dm.densityDpi + " dpi (" + String.format(loc(ctx), "%.1f×", dm.density) + ")");
        } catch (Exception ignored) { }
        try {
            Display d = ctx.getDisplay();
            if (d != null) {
                row(sb, ctx.getString(R.string.device_refresh_rate), Math.round(d.getRefreshRate()) + " Hz");
                Display.Mode[] modes = d.getSupportedModes();
                if (modes != null && modes.length > 1) {
                    StringBuilder hz = new StringBuilder();
                    for (Display.Mode m : modes) {
                        if (hz.length() > 0) hz.append(", ");
                        hz.append(Math.round(m.getRefreshRate())).append(" Hz");
                    }
                    row(sb, ctx.getString(R.string.device_supported_modes), hz.toString());
                }
                if (d.getHdrCapabilities() != null
                        && d.getHdrCapabilities().getSupportedHdrTypes().length > 0) {
                    row(sb, "HDR", ctx.getString(R.string.device_supported));
                }
            }
        } catch (Exception ignored) { }
        return trimmed(sb, ctx.getString(R.string.device_display_none));
    }

    // ---- Sensorit (ei lupia, vain listaus) ----
    static CharSequence sensors(Context ctx) {
        StringBuilder sb = new StringBuilder();
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
            if (list != null && !list.isEmpty()) {
                row(sb, ctx.getString(R.string.device_sensor_count), String.valueOf(list.size()));
                sb.append('\n');
                for (Sensor s : list) {
                    sb.append("• ").append(s.getName());
                    if (!TextUtils.isEmpty(s.getVendor())) sb.append("  —  ").append(s.getVendor());
                    sb.append('\n');
                }
            }
        }
        return trimmed(sb, ctx.getString(R.string.device_sensors_none));
    }

    // ---- WiFi (perustiedot ilman lupaa; SSID ja skannaus vaativat sijaintiluvan) ----
    static CharSequence wifi(Context ctx) {
        StringBuilder sb = new StringBuilder();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            return ctx.getString(R.string.device_wifi_off);
        }
        WifiInfo info = wm.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            if (ssid != null && !ssid.contains("unknown") && !ssid.equals("0x")) {
                row(sb, ctx.getString(R.string.device_network), ssid.replace("\"", ""));
            }
            int rssi = info.getRssi();
            if (rssi > -127 && rssi <= 0) row(sb, ctx.getString(R.string.device_signal), rssi + " dBm");
            int link = info.getLinkSpeed();
            if (link > 0) row(sb, ctx.getString(R.string.device_link_speed), link + " Mbps");
            int rx = info.getRxLinkSpeedMbps();
            int tx = info.getTxLinkSpeedMbps();
            if (rx > 0) row(sb, ctx.getString(R.string.device_rx), rx + " Mbps");
            if (tx > 0) row(sb, ctx.getString(R.string.device_tx), tx + " Mbps");
            int freq = info.getFrequency();
            if (freq > 0) {
                row(sb, ctx.getString(R.string.device_frequency), ctx.getString(
                        R.string.device_frequency_value, freq, band(freq, loc(ctx)), channel(freq)));
            }
            String std = wifiStandard(info.getWifiStandard());
            row(sb, ctx.getString(R.string.device_wifi_standard),
                    std != null ? std : ctx.getString(R.string.common_unknown));
        }
        appendIpAndDns(ctx, sb);
        return trimmed(sb, ctx.getString(R.string.device_wifi_none));
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
                        row(sb, ctx.getString(R.string.device_ip_address), addr.getHostAddress());
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
                if (down > 0) row(sb, ctx.getString(R.string.device_est_download), (down / 1000) + " Mbps");
                if (up > 0) row(sb, ctx.getString(R.string.device_est_upload), (up / 1000) + " Mbps");
            }
        } catch (Exception ignored) { }
    }

    // ---- Mobiiliverkko (best-effort; verkkotyyppi vaatii READ_PHONE_STATE, solutiedot FINE) ----
    static CharSequence cellular(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return ctx.getString(R.string.device_no_cellular);
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return ctx.getString(R.string.device_cellular_none);
        StringBuilder sb = new StringBuilder();
        try {
            String op = tm.getNetworkOperatorName();
            if (!TextUtils.isEmpty(op)) row(sb, ctx.getString(R.string.device_operator), op);
        } catch (Exception ignored) { }
        try {
            String mm = tm.getNetworkOperator();
            if (mm != null && mm.length() >= 5) {
                row(sb, "MCC / MNC", mm.substring(0, 3) + " / " + mm.substring(3));
            }
        } catch (Exception ignored) { }
        try {
            row(sb, ctx.getString(R.string.device_roaming),
                    ctx.getString(tm.isNetworkRoaming() ? R.string.common_yes : R.string.common_no));
        } catch (Exception ignored) { }

        boolean nrSignal = false;
        try {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) {
                for (CellSignalStrength c : ss.getCellSignalStrengths()) {
                    if (c instanceof CellSignalStrengthLte) {
                        CellSignalStrengthLte lte = (CellSignalStrengthLte) c;
                        if (valid(lte.getDbm())) row(sb, ctx.getString(R.string.device_lte_signal), lte.getDbm() + " dBm");
                        if (valid(lte.getRsrp())) row(sb, "  RSRP", lte.getRsrp() + " dBm");
                        if (valid(lte.getRsrq())) row(sb, "  RSRQ", lte.getRsrq() + " dB");
                        if (valid(lte.getRssnr())) row(sb, "  RSSNR", lte.getRssnr() + " dB");
                    } else if (c instanceof CellSignalStrengthNr) {
                        nrSignal = true;
                        CellSignalStrengthNr nr = (CellSignalStrengthNr) c;
                        if (valid(nr.getDbm())) row(sb, ctx.getString(R.string.device_5g_signal), nr.getDbm() + " dBm");
                        if (valid(nr.getSsRsrp())) row(sb, "  SS-RSRP", nr.getSsRsrp() + " dBm");
                        if (valid(nr.getSsRsrq())) row(sb, "  SS-RSRQ", nr.getSsRsrq() + " dB");
                        if (valid(nr.getSsSinr())) row(sb, "  SS-SINR", nr.getSsSinr() + " dB");
                    } else if (valid(c.getDbm())) {
                        row(sb, ctx.getString(R.string.device_signal), c.getDbm() + " dBm");
                    }
                }
            }
        } catch (Exception ignored) { }

        try {
            int nt = tm.getDataNetworkType();
            String typeLabel = ctx.getString(R.string.device_network_type);
            if (nt == TelephonyManager.NETWORK_TYPE_NR) {
                row(sb, typeLabel, ctx.getString(R.string.device_5g_sa));
            } else if (nrSignal && nt == TelephonyManager.NETWORK_TYPE_LTE) {
                row(sb, typeLabel, ctx.getString(R.string.device_5g_nsa));
            } else {
                row(sb, typeLabel, networkTypeName(ctx, nt));
            }
        } catch (SecurityException e) {
            row(sb, ctx.getString(R.string.device_network_type), ctx.getString(R.string.device_needs_phone_permission));
        } catch (Exception ignored) { }

        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) {
                for (CellInfo info : cells) {
                    if (!info.isRegistered()) continue;
                    if (info instanceof CellInfoLte) {
                        CellIdentityLte id = ((CellInfoLte) info).getCellIdentity();
                        if (valid(id.getCi())) row(sb, ctx.getString(R.string.device_cell_id_lte), String.valueOf(id.getCi()));
                        if (valid(id.getPci())) row(sb, "  PCI", String.valueOf(id.getPci()));
                        if (valid(id.getTac())) row(sb, "  TAC", String.valueOf(id.getTac()));
                        if (valid(id.getEarfcn())) row(sb, "  EARFCN", String.valueOf(id.getEarfcn()));
                    } else if (info instanceof CellInfoNr) {
                        CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) info).getCellIdentity();
                        long nci = id.getNci();
                        if (nci != Long.MAX_VALUE && nci != CellInfo.UNAVAILABLE_LONG) {
                            row(sb, ctx.getString(R.string.device_cell_id_nr), String.valueOf(nci));
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

        return trimmed(sb, ctx.getString(R.string.device_cellular_none));
    }

    // ---- SIM / liittymät (best-effort; vaatii READ_PHONE_STATE; ei IMEI/IMSI Android 10+) ----
    static CharSequence sim(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return ctx.getString(R.string.device_no_sim_support);
        }
        SubscriptionManager sm = (SubscriptionManager)
                ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return ctx.getString(R.string.device_sim_none);
        int dataSub = defaultSubId(0), voiceSub = defaultSubId(1), smsSub = defaultSubId(2);
        StringBuilder sb = new StringBuilder();
        try {
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                sb.append(ctx.getString(R.string.device_sim_no_active));
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
                    row(sb, "  " + ctx.getString(R.string.device_sim_type),
                            s.isEmbedded() ? "eSIM" : ctx.getString(R.string.device_sim_physical));
                    row(sb, "  " + ctx.getString(R.string.device_sim_slot), String.valueOf(s.getSimSlotIndex()));
                    String mcc = s.getMccString();
                    String mnc = s.getMncString();
                    if (mcc != null && mnc != null) row(sb, "  MCC/MNC", mcc + " / " + mnc);
                    if (!TextUtils.isEmpty(s.getCountryIso())) {
                        row(sb, "  " + ctx.getString(R.string.device_sim_country), s.getCountryIso().toUpperCase(Locale.ROOT));
                    }
                    row(sb, "  " + ctx.getString(R.string.device_roaming), ctx.getString(
                            s.getDataRoaming() == SubscriptionManager.DATA_ROAMING_ENABLE
                                    ? R.string.device_sim_roaming_allowed : R.string.device_sim_roaming_blocked));
                    int subId = s.getSubscriptionId();
                    StringBuilder roles = new StringBuilder();
                    if (subId == dataSub) roles.append("data");
                    if (subId == voiceSub) {
                        if (roles.length() > 0) roles.append(", ");
                        roles.append(ctx.getString(R.string.device_sim_calls));
                    }
                    if (subId == smsSub) {
                        if (roles.length() > 0) roles.append(", ");
                        roles.append("SMS");
                    }
                    if (roles.length() > 0) row(sb, "  " + ctx.getString(R.string.device_sim_default), roles.toString());
                }
            }
        } catch (SecurityException e) {
            return ctx.getString(R.string.device_sim_needs_permission);
        } catch (Exception e) {
            return ctx.getString(R.string.device_sim_none);
        }
        sb.append("\n\n").append(ctx.getString(R.string.device_imei_note));
        return trimmed(sb, ctx.getString(R.string.device_sim_none));
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

    private static String networkTypeName(Context ctx, int t) {
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
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return ctx.getString(R.string.device_no_network);
            default: return ctx.getString(R.string.device_network_type_other, t);
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

    static String fmtBytes(long bytes, Locale locale) {
        if (bytes <= 0) return "0";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) return String.format(locale, "%.1f GB", gb);
        return String.format(locale, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    static String band(int freqMhz, Locale locale) {
        if (freqMhz >= 2401 && freqMhz <= 2495) return String.format(locale, "%.1f GHz", 2.4);
        if (freqMhz >= 5150 && freqMhz <= 5895) return "5 GHz";
        if (freqMhz >= 5925 && freqMhz <= 7125) return "6 GHz";
        return "?";
    }

    static int channel(int freqMhz) {
        if (freqMhz >= 2401 && freqMhz <= 2495) return (freqMhz - 2407) / 5;
        if (freqMhz >= 5150 && freqMhz <= 5895) return (freqMhz - 5000) / 5;
        if (freqMhz >= 5925 && freqMhz <= 7125) return (freqMhz - 5950) / 5 + 1;
        return 0;
    }

    /** Wi-Fi-standardin nimi; null = tuntematon (kutsuja näyttää "Ei tiedossa" sovelluksen kielellä). */
    static String wifiStandard(int standard) {
        switch (standard) {
            case ScanStd.LEGACY: return "802.11a/b/g";
            case ScanStd.N: return "Wi-Fi 4 (n)";
            case ScanStd.AC: return "Wi-Fi 5 (ac)";
            case ScanStd.AX: return "Wi-Fi 6 (ax)";
            case ScanStd.AD: return "802.11ad";
            case ScanStd.BE: return "Wi-Fi 7 (be)";
            default: return null;
        }
    }

    /** WifiInfo.WIFI_STANDARD_* arvot (vältetään suora viittaus selkeyden vuoksi). */
    private static final class ScanStd {
        static final int LEGACY = 1, N = 4, AC = 5, AX = 6, AD = 7, BE = 8;
    }

    private static String batteryStatus(Context ctx, int status) {
        return ctx.getString(batteryStatusRes(status));
    }

    static int batteryStatusRes(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return R.string.device_battery_charging;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return R.string.device_battery_discharging;
            case BatteryManager.BATTERY_STATUS_FULL: return R.string.device_battery_full;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return R.string.device_battery_not_charging;
            default: return R.string.common_unknown;
        }
    }

    private static String pluggedText(Context ctx, int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_AC: return ctx.getString(R.string.device_plugged_ac);
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return ctx.getString(R.string.device_plugged_wireless);
            default: return ctx.getString(R.string.device_plugged_other);
        }
    }

    private static String healthText(Context ctx, int health) {
        return ctx.getString(healthRes(health));
    }

    static int healthRes(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return R.string.device_health_good;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return R.string.device_health_overheat;
            case BatteryManager.BATTERY_HEALTH_DEAD: return R.string.device_health_dead;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return R.string.device_health_over_voltage;
            case BatteryManager.BATTERY_HEALTH_COLD: return R.string.device_health_cold;
            default: return R.string.common_unknown;
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

    private static String cpuMaxFreqSummary(int cores, Locale locale) {
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
            if (maxKhz > 0) return String.format(locale, "%.2f GHz", maxKhz / 1_000_000.0);
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
