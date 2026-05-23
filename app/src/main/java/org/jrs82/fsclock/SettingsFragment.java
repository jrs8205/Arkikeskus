package org.jrs82.fsclock;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import org.jrs82.fsclock.db.CsvExporter;
import org.jrs82.fsclock.db.HistoryRepository;
import org.jrs82.fsclock.ruuvi.RuuviRepository;
import org.jrs82.fsclock.ruuvi.RuuviSample;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final Locale FI = new Locale("fi", "FI");

    /** True kun home_place asetetaan ohjelmallisesti dialogin kautta — estää
     *  OnPreferenceChangeListeneriä avaamasta dialogia toista kertaa. */
    private boolean settingHomePlaceProgrammatically = false;

    /** Ruuvi-skannauksen dialogin tila (jos dialog on auki). */
    private AlertDialog ruuviScanDialog;
    /** Dialogissa näytettävien MACien järjestys (sama kuin näkyy listalla). */
    private final List<String> ruuviDialogMacs = new ArrayList<>();
    /** Slot johon valittu anturi kytketään, tai null jos kysytään dialogissa erikseen. */
    private String ruuviPendingSlot;
    /** Listener jolla RuuviRepository päivittää avoimen dialogin. */
    private RuuviRepository.Listener ruuviDialogListener;
    /** Ajetaan kun BLUETOOTH_SCAN-lupa on myönnetty (jos dialog yritettiin avata). */
    private Runnable pendingRuuviAction;

    private final ActivityResultLauncher<String> bluetoothScanPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                if (granted) {
                    Runnable r = pendingRuuviAction;
                    pendingRuuviAction = null;
                    if (r != null) r.run();
                } else {
                    Toast.makeText(requireContext(), R.string.ruuvi_perm_needed,
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        SettingsManager sm = SettingsManager.get();

        // Päivän alkamisaika
        Preference morning = findPreference(SettingsManager.KEY_DAY_MORNING_HOUR);
        if (morning != null) {
            morning.setSummary(String.format(FI, "%02d:00", sm.getMorningHour()));
            morning.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference p) {
                    showHourPicker(true, p);
                    return true;
                }
            });
        }

        // Yön alkamisaika
        Preference evening = findPreference(SettingsManager.KEY_NIGHT_EVENING_HOUR);
        if (evening != null) {
            evening.setSummary(String.format(FI, "%02d:00", sm.getEveningHour()));
            evening.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference p) {
                    showHourPicker(false, p);
                    return true;
                }
            });
        }

        // Versio
        Preference version = findPreference("version");
        if (version != null) {
            try {
                String v = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                version.setSummary(v);
            } catch (Exception ignored) {
                version.setSummary("?");
            }
        }

        // Viimeisin sääpäivitys
        Preference lastUpdate = findPreference("last_update");
        if (lastUpdate != null) {
            long ts = sm.getLastSuccessfulFmiUpdate();
            if (ts <= 0) {
                lastUpdate.setSummary("—");
            } else {
                lastUpdate.setSummary(new SimpleDateFormat("d.M.yyyy HH:mm", FI).format(new Date(ts)));
            }
        }

        // EditTextPreference: paikkakunnan summary näyttää oletuksena tekstin
        EditTextPreference place = findPreference(SettingsManager.KEY_HOME_PLACE);
        if (place != null) {
            if (place.getText() == null || place.getText().isEmpty()) {
                place.setText(SettingsManager.DEFAULT_HOME_PLACE);
            }
            setupHomePlaceConfirm(place);
        }

        // Testitila-napit
        setupTestModeButton("test_day_mode", SettingsManager.TEST_DAY, R.string.toast_test_day_label);
        setupTestModeButton("test_night_mode", SettingsManager.TEST_NIGHT, R.string.toast_test_night_label);
        setupTestModeButton("test_warning", SettingsManager.TEST_WARNING, R.string.toast_test_warning_label);
        setupTestModeButton("test_offline", SettingsManager.TEST_OFFLINE, R.string.toast_test_offline_label);

        // Auto-time -varoitus: lisätään näkyväksi vain jos auto-aika on pois
        addAutoTimeWarningIfNeeded();

        // Historia ja tietokanta
        setupHistoryPreferences();

        // Ruuvi-anturit
        setupRuuviPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRuuviSlotSummaries();
    }

    @Override
    public void onPause() {
        dismissRuuviScanDialog();
        super.onPause();
    }

    /** Pyyntö kotipaikkakunnan vaihtoon vaatii vahvistuksen, koska uusi
     *  DB-kanava aloittaa tyhjältä. Listener palauttaa false ja näyttää dialogin;
     *  OK asettaa arvon käsin guard-flagin takana, ettei dialogi aukea uudestaan. */
    private void setupHomePlaceConfirm(final EditTextPreference pref) {
        pref.setOnPreferenceChangeListener((p, newValue) -> {
            if (settingHomePlaceProgrammatically) return true;
            String newStr = newValue == null ? "" : newValue.toString().trim();
            String oldStr = pref.getText() == null ? "" : pref.getText().trim();
            if (newStr.isEmpty() || newStr.equalsIgnoreCase(oldStr)) {
                return false;
            }
            showHomePlaceChangeDialog(pref, newStr);
            return false;
        });
    }

    private void showHomePlaceChangeDialog(final EditTextPreference pref, final String newPlace) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.home_place_change_title)
                .setMessage(getString(R.string.home_place_change_message, newPlace))
                .setPositiveButton(R.string.home_place_change_ok, (d, w) -> {
                    settingHomePlaceProgrammatically = true;
                    try {
                        // setText kirjoittaa SharedPreferencesiin avaimella "home_place"
                        // ja triggeröi FsClockApp:n kanavavaihdon. Ei tarvita erillistä
                        // SettingsManager.setHomePlace-kutsua.
                        pref.setText(newPlace);
                    } finally {
                        settingHomePlaceProgrammatically = false;
                    }
                })
                .setNegativeButton(R.string.home_place_change_cancel, null)
                .show();
    }

    private void setupHistoryPreferences() {
        refreshDbSize();

        Preference exportCsv = findPreference("export_csv");
        if (exportCsv != null) {
            exportCsv.setOnPreferenceClickListener(p -> { exportAllToCsv(); return true; });
        }

        Preference clearDb = findPreference("clear_db");
        if (clearDb != null) {
            clearDb.setOnPreferenceClickListener(p -> { showClearDbDialog(); return true; });
        }
    }

    private void refreshDbSize() {
        final Preference dbSize = findPreference("db_size");
        if (dbSize == null) return;
        final Context ctx = requireContext().getApplicationContext();
        final HistoryRepository repo = HistoryRepository.get(ctx);
        final Handler main = new Handler(Looper.getMainLooper());
        repo.io().execute(() -> {
            long count = repo.sampleCount();
            String sizeStr = formatDbFileSize(ctx);
            main.post(() -> dbSize.setSummary(getString(R.string.pref_db_size_summary, count, sizeStr)));
        });
    }

    private static String formatDbFileSize(Context ctx) {
        try {
            java.io.File dbFile = ctx.getDatabasePath("fsclock.db");
            long bytes = dbFile.exists() ? dbFile.length() : 0L;
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(FI, "%.1f kB", bytes / 1024.0);
            return String.format(FI, "%.2f MB", bytes / (1024.0 * 1024.0));
        } catch (Exception e) {
            return "?";
        }
    }

    private void exportAllToCsv() {
        final Context ctx = requireContext().getApplicationContext();
        final HistoryRepository repo = HistoryRepository.get(ctx);
        final Handler main = new Handler(Looper.getMainLooper());
        final String fileName = CsvExporter.buildFileName(CsvExporter.Kind.RAW_ALL);
        repo.io().execute(() -> {
            CsvExporter.Result result = CsvExporter.export(ctx, CsvExporter.Kind.RAW_ALL, fileName);
            main.post(() -> {
                if (!isAdded()) return;
                if (result.ok) {
                    Toast.makeText(ctx, getString(R.string.toast_csv_exported, result.fileName),
                            Toast.LENGTH_LONG).show();
                } else if (result.rowCount == 0 && result.error == null) {
                    Toast.makeText(ctx, R.string.toast_csv_empty, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, R.string.toast_csv_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showClearDbDialog() {
        final Context ctx = requireContext();
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad * 2, pad, pad * 2, 0);

        TextView msg = new TextView(ctx);
        msg.setText(R.string.clear_db_message);
        msg.setTextSize(15);
        container.addView(msg);

        final CheckBox confirm = new CheckBox(ctx);
        confirm.setText(R.string.clear_db_confirm_check);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        lp.gravity = Gravity.START;
        container.addView(confirm, lp);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle(R.string.clear_db_title)
                .setView(container)
                .setPositiveButton(R.string.clear_db_confirm, (d, w) -> doClearDb())
                .setNegativeButton(R.string.clear_db_cancel, null)
                .create();

        dlg.setOnShowListener(d -> {
            android.widget.Button positive = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setEnabled(false);
            confirm.setOnCheckedChangeListener((b, checked) -> positive.setEnabled(checked));
        });
        dlg.show();
    }

    private void doClearDb() {
        final Context ctx = requireContext().getApplicationContext();
        final HistoryRepository repo = HistoryRepository.get(ctx);
        final Handler main = new Handler(Looper.getMainLooper());
        repo.io().execute(() -> {
            try {
                repo.clearAll();
            } catch (Exception ignored) {}
            main.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(ctx, R.string.toast_db_cleared, Toast.LENGTH_SHORT).show();
                refreshDbSize();
            });
        });
    }

    private void setupTestModeButton(String key, final int testType, final int labelResId) {
        Preference p = findPreference(key);
        if (p == null) return;
        updateTestModeSummary(p, testType, labelResId);
        p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference pp) {
                SettingsManager sm = SettingsManager.get();
                int active = sm.getActiveTestMode();
                Context ctx = requireContext();
                if (active == testType) {
                    sm.clearTestMode();
                    Toast.makeText(ctx, ctx.getString(R.string.toast_test_cleared,
                            ctx.getString(labelResId)), Toast.LENGTH_SHORT).show();
                } else {
                    sm.setTestMode(testType);
                    Toast.makeText(ctx, ctx.getString(R.string.toast_test_activated,
                            ctx.getString(labelResId)), Toast.LENGTH_SHORT).show();
                }
                updateTestModeSummary(pp, testType, labelResId);
                return true;
            }
        });
    }

    private void updateTestModeSummary(Preference p, int testType, int labelResId) {
        SettingsManager sm = SettingsManager.get();
        Context ctx = requireContext();
        if (sm.getActiveTestMode() == testType) {
            long minutesLeft = Math.max(0,
                    (sm.getTestModeUntil() - System.currentTimeMillis()) / 60_000L);
            p.setSummary(String.format(FI, "Aktiivinen — %d min jäljellä", minutesLeft));
        } else {
            p.setSummary(ctx.getString(R.string.pref_test_summary));
        }
    }

    private void showHourPicker(final boolean isMorning, final Preference p) {
        final SettingsManager sm = SettingsManager.get();
        int currentHour = isMorning ? sm.getMorningHour() : sm.getEveningHour();
        TimePickerDialog dlg = new TimePickerDialog(requireContext(),
                new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker view, int h, int m) {
                        if (isMorning) sm.setMorningHour(h);
                        else sm.setEveningHour(h);
                        p.setSummary(String.format(FI, "%02d:00", h));
                    }
                }, currentHour, 0, true);
        dlg.setTitle(R.string.time_picker_title);
        dlg.show();
    }

    // ---------- Ruuvi-asetukset ----------

    private void setupRuuviPreferences() {
        Preference scan = findPreference("ruuvi_scan");
        if (scan != null) {
            scan.setOnPreferenceClickListener(p -> { openScanDialog(null); return true; });
        }
        setupSlotPreference("ruuvi_slot_bedroom", SettingsManager.RUUVI_SLOT_BEDROOM);
        setupSlotPreference("ruuvi_slot_livingroom", SettingsManager.RUUVI_SLOT_LIVINGROOM);
        setupSlotPreference("ruuvi_slot_balcony", SettingsManager.RUUVI_SLOT_BALCONY);
        refreshRuuviSlotSummaries();
    }

    private void setupSlotPreference(String key, final String slot) {
        Preference p = findPreference(key);
        if (p == null) return;
        p.setOnPreferenceClickListener(pp -> { openScanDialog(slot); return true; });
    }

    private void refreshRuuviSlotSummaries() {
        SettingsManager sm = SettingsManager.get();
        RuuviRepository repo = RuuviRepository.get(requireContext());
        updateSlotSummary("ruuvi_slot_bedroom", sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BEDROOM), repo);
        updateSlotSummary("ruuvi_slot_livingroom", sm.getRuuviMac(SettingsManager.RUUVI_SLOT_LIVINGROOM), repo);
        updateSlotSummary("ruuvi_slot_balcony", sm.getRuuviMac(SettingsManager.RUUVI_SLOT_BALCONY), repo);
    }

    private void updateSlotSummary(String key, String mac, RuuviRepository repo) {
        Preference p = findPreference(key);
        if (p == null) return;
        if (mac == null || mac.isEmpty()) {
            p.setSummary(R.string.ruuvi_slot_empty);
            return;
        }
        RuuviSample s = repo.getLatest(mac);
        if (s != null && s.temperatureC() != null) {
            p.setSummary(mac + " · " + String.format(FI, "%.1f °C", s.temperatureC()));
        } else {
            p.setSummary(mac);
        }
    }

    private void openScanDialog(String targetSlot) {
        if (!ensureBluetoothScanPermission(() -> openScanDialog(targetSlot))) return;
        Context ctx = requireContext();
        RuuviRepository repo = RuuviRepository.get(ctx);
        boolean started = repo.start();
        if (!started && !repo.isScanning()) {
            Toast.makeText(ctx, R.string.ruuvi_perm_needed, Toast.LENGTH_LONG).show();
            return;
        }

        ruuviPendingSlot = targetSlot;
        ruuviDialogMacs.clear();
        final CharSequence[] initialItems = buildScanItems(repo.snapshot());

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(R.string.ruuvi_scan_dialog_title)
                .setItems(initialItems, (d, which) -> {
                    if (which < 0 || which >= ruuviDialogMacs.size()) return;
                    String mac = ruuviDialogMacs.get(which);
                    if (ruuviPendingSlot != null) {
                        assignMacToSlot(mac, ruuviPendingSlot);
                    } else {
                        showAssignDialog(mac);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (targetSlot == null) {
            b.setMessage(R.string.ruuvi_scan_dialog_hint);
        }
        ruuviScanDialog = b.create();
        ruuviScanDialog.setOnDismissListener(d -> {
            if (ruuviDialogListener != null) {
                repo.removeListener(ruuviDialogListener);
                ruuviDialogListener = null;
            }
            ruuviScanDialog = null;
            ruuviPendingSlot = null;
        });

        final Handler main = new Handler(Looper.getMainLooper());
        ruuviDialogListener = (mac, sample) -> main.post(() -> {
            if (ruuviScanDialog == null || !ruuviScanDialog.isShowing()) return;
            updateScanDialogList(repo.snapshot());
        });
        repo.addListener(ruuviDialogListener);

        ruuviScanDialog.show();
        updateScanDialogList(repo.snapshot());
    }

    private CharSequence[] buildScanItems(Map<String, RuuviSample> snapshot) {
        ruuviDialogMacs.clear();
        if (snapshot == null || snapshot.isEmpty()) {
            return new CharSequence[]{ getString(R.string.ruuvi_scan_dialog_empty) };
        }
        Map<String, RuuviSample> sorted = sortSnapshotByMac(snapshot);
        SettingsManager sm = SettingsManager.get();
        List<CharSequence> items = new ArrayList<>(sorted.size());
        for (Map.Entry<String, RuuviSample> e : sorted.entrySet()) {
            String mac = e.getKey();
            RuuviSample s = e.getValue();
            ruuviDialogMacs.add(mac);
            String temp = (s != null && s.temperatureC() != null)
                    ? String.format(FI, "%.1f °C", s.temperatureC())
                    : getString(R.string.ruuvi_card_temp_missing);
            String label = getString(R.string.ruuvi_card_format, mac, temp, s == null ? 0 : s.rssi);
            String slot = sm.slotForMac(mac);
            if (slot != null) label += "  [" + slotLabel(slot) + "]";
            items.add(label);
        }
        return items.toArray(new CharSequence[0]);
    }

    private Map<String, RuuviSample> sortSnapshotByMac(Map<String, RuuviSample> snapshot) {
        List<String> keys = new ArrayList<>(snapshot.keySet());
        java.util.Collections.sort(keys);
        Map<String, RuuviSample> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, snapshot.get(k));
        return out;
    }

    private void updateScanDialogList(Map<String, RuuviSample> snapshot) {
        if (ruuviScanDialog == null) return;
        CharSequence[] items = buildScanItems(snapshot);
        android.widget.ListView lv = ruuviScanDialog.getListView();
        if (lv == null) return;
        lv.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, items));
        if (snapshot == null || snapshot.isEmpty()) {
            lv.setOnItemClickListener(null);
        } else {
            lv.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= ruuviDialogMacs.size()) return;
                String mac = ruuviDialogMacs.get(position);
                if (ruuviScanDialog != null) ruuviScanDialog.dismiss();
                if (ruuviPendingSlot != null) {
                    assignMacToSlot(mac, ruuviPendingSlot);
                } else {
                    showAssignDialog(mac);
                }
            });
        }
    }

    private void dismissRuuviScanDialog() {
        if (ruuviScanDialog != null && ruuviScanDialog.isShowing()) {
            ruuviScanDialog.dismiss();
        }
        ruuviScanDialog = null;
    }

    private void showAssignDialog(final String mac) {
        Context ctx = requireContext();
        CharSequence[] options = new CharSequence[]{
                getString(R.string.ruuvi_assign_to_bedroom),
                getString(R.string.ruuvi_assign_to_livingroom),
                getString(R.string.ruuvi_assign_to_balcony),
                getString(R.string.ruuvi_assign_clear)
        };
        new AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.ruuvi_assign_dialog_title, mac))
                .setItems(options, (DialogInterface d, int which) -> {
                    switch (which) {
                        case 0: assignMacToSlot(mac, SettingsManager.RUUVI_SLOT_BEDROOM); break;
                        case 1: assignMacToSlot(mac, SettingsManager.RUUVI_SLOT_LIVINGROOM); break;
                        case 2: assignMacToSlot(mac, SettingsManager.RUUVI_SLOT_BALCONY); break;
                        case 3: clearMacAssignment(mac); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Asettaa MAC slottiin. Jos MAC oli aiemmin toisessa slotissa, sieltä se poistuu. */
    private void assignMacToSlot(String mac, String slot) {
        SettingsManager sm = SettingsManager.get();
        // poista MAC mahdollisesta vanhasta slotista
        String existing = sm.slotForMac(mac);
        if (existing != null && !existing.equals(slot)) {
            sm.setRuuviMac(existing, null);
        }
        // poista mahdollinen aiempi MAC kohdeslotista (yksi anturi per slot)
        sm.setRuuviMac(slot, mac);
        refreshRuuviSlotSummaries();
        Toast.makeText(requireContext(),
                getString(R.string.ruuvi_assigned_toast, mac, slotLabel(slot)),
                Toast.LENGTH_SHORT).show();
    }

    private void clearMacAssignment(String mac) {
        SettingsManager sm = SettingsManager.get();
        String slot = sm.slotForMac(mac);
        if (slot == null) return;
        sm.setRuuviMac(slot, null);
        refreshRuuviSlotSummaries();
    }

    private String slotLabel(String slot) {
        if (SettingsManager.RUUVI_SLOT_BEDROOM.equals(slot)) return getString(R.string.ruuvi_assign_to_bedroom);
        if (SettingsManager.RUUVI_SLOT_LIVINGROOM.equals(slot)) return getString(R.string.ruuvi_assign_to_livingroom);
        if (SettingsManager.RUUVI_SLOT_BALCONY.equals(slot)) return getString(R.string.ruuvi_assign_to_balcony);
        return slot;
    }

    /** Varmistaa BLE-skannauksen vaatiman luvan: API 31+ BLUETOOTH_SCAN, API ≤30
     *  ACCESS_FINE_LOCATION. Palauttaa true jos lupa on jo, false jos käyttäjältä
     *  kysytään (silloin onPermissionResult ajaa pyydetyn actionin). */
    private boolean ensureBluetoothScanPermission(Runnable onGranted) {
        Context ctx = requireContext();
        String perm = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        pendingRuuviAction = onGranted;
        bluetoothScanPermissionLauncher.launch(perm);
        return false;
    }

    private void addAutoTimeWarningIfNeeded() {
        try {
            int autoTime = Settings.Global.getInt(requireContext().getContentResolver(),
                    Settings.Global.AUTO_TIME, 0);
            if (autoTime != 0) return;
            Preference warn = new Preference(requireContext());
            warn.setTitle(R.string.auto_time_warning);
            warn.setOrder(-1);
            warn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference p) {
                    try {
                        startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
                    } catch (Exception ignored) { }
                    return true;
                }
            });
            getPreferenceScreen().addPreference(warn);
        } catch (Exception ignored) { }
    }
}
