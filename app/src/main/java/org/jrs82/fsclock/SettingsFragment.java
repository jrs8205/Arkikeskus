package org.jrs82.fsclock;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final Locale FI = new Locale("fi", "FI");

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
        if (place != null && (place.getText() == null || place.getText().isEmpty())) {
            place.setText(SettingsManager.DEFAULT_HOME_PLACE);
        }

        // Testitila-napit
        setupTestModeButton("test_day_mode", SettingsManager.TEST_DAY, R.string.toast_test_day_label);
        setupTestModeButton("test_night_mode", SettingsManager.TEST_NIGHT, R.string.toast_test_night_label);
        setupTestModeButton("test_warning", SettingsManager.TEST_WARNING, R.string.toast_test_warning_label);
        setupTestModeButton("test_offline", SettingsManager.TEST_OFFLINE, R.string.toast_test_offline_label);

        // Auto-time -varoitus: lisätään näkyväksi vain jos auto-aika on pois
        addAutoTimeWarningIfNeeded();
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
