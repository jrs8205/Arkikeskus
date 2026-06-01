package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.SettingsFragment;
import org.jrs82.fsclock.SettingsManager;

import java.util.ArrayList;
import java.util.List;

public class MobileSettingsFragment extends SettingsFragment {

    private SwitchPreferenceCompat autoLocationPreference;
    private Preference widgetOrderPreference;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (autoLocationPreference == null
                        || autoLocationPreference.getSharedPreferences() == null) return;
                Context context = getContext();
                if (context == null) return;

                boolean precise = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (!precise) SettingsManager.get().clearHomeCoordinates();

                autoLocationPreference.getSharedPreferences().edit()
                        .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                        .putBoolean(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION, precise)
                        .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
                        .apply();
                autoLocationPreference.setChecked(precise);

                Toast.makeText(context, precise
                        ? "Automaattinen sijainti on käytössä."
                        : coarse
                        ? "Tarkka sijainti ei ole käytössä. Valitse luvasta Tarkka sijainti."
                        : "Sijaintilupaa ei annettu. Kaupunkihaku toimii normaalisti.",
                        Toast.LENGTH_SHORT).show();
            });

    @Override
    protected int preferencesResource() {
        return R.xml.mobile_preferences;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        ListPreference theme = findPreference(MobileThemeController.KEY_THEME_MODE);
        if (theme != null) {
            theme.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String value = newValue == null
                            ? MobileThemeController.VALUE_SYSTEM
                            : newValue.toString();
                    if (preference.getSharedPreferences() != null) {
                        preference.getSharedPreferences().edit()
                                .putString(MobileThemeController.KEY_THEME_MODE, value)
                                .apply();
                    }
                    MobileThemeController.applyValue(value);
                    return false;
                }
            });
        }
        autoLocationPreference = findPreference(MobileThemeController.KEY_USE_AUTOMATIC_LOCATION);
        if (autoLocationPreference != null) {
            autoLocationPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enable = Boolean.TRUE.equals(newValue);
                if (!enable) {
                    SettingsManager.get().clearHomeCoordinates();
                    if (preference.getSharedPreferences() != null) {
                        preference.getSharedPreferences().edit()
                                .remove(MobileThemeController.KEY_AUTO_LOCATION_DISPLAY_NAME)
                                .apply();
                    }
                    return true;
                }
                if (hasPreciseLocationPermission()) {
                    return true;
                }
                if (preference.getSharedPreferences() != null) {
                    preference.getSharedPreferences().edit()
                            .putBoolean(MobileThemeController.KEY_INITIAL_LOCATION_PERMISSION_ASKED, true)
                            .apply();
                }
                locationPermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                });
                return false;
            });
        }
        widgetOrderPreference = findPreference(MobileThemeController.KEY_WIDGET_ORDER);
        if (widgetOrderPreference != null) {
            widgetOrderPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), MobileWidgetOrderActivity.class));
                return true;
            });
        }
        Preference addCustomFeed = findPreference("mobile_add_custom_feed");
        if (addCustomFeed != null) {
            addCustomFeed.setOnPreferenceClickListener(preference -> {
                showCustomFeedDialog(null);
                return true;
            });
        }
        rebuildCustomFeedPreferences();
    }

    private boolean hasPreciseLocationPermission() {
        Context context = getContext();
        return context != null
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Rakentaa "Omat uutissyötteet" -kategorian rivit uudelleen nykyisistä
     *  syötteistä. Jokainen rivi: nimi + URL, klikkaus avaa muokkaus/poisto. */
    private void rebuildCustomFeedPreferences() {
        Context context = getContext();
        if (context == null) return;
        PreferenceCategory category = findPreference("mobile_custom_feeds_category");
        if (category == null) return;

        // Poista aiemmin lisätyt syöte-rivit (kaikki paitsi "Lisää oma syöte").
        List<Preference> toRemove = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference p = category.getPreference(i);
            if (p.getKey() != null && p.getKey().startsWith("custom_feed_row_")) {
                toRemove.add(p);
            }
        }
        for (Preference p : toRemove) category.removePreference(p);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        for (NewsFeed feed : NewsFeedStore.customFeeds(prefs)) {
            Preference row = new Preference(context);
            row.setKey("custom_feed_row_" + feed.id);
            row.setTitle(feed.name);
            row.setSummary(feed.url);
            row.setOnPreferenceClickListener(preference -> {
                showCustomFeedDialog(feed.id);
                return true;
            });
            category.addPreference(row);
        }
    }

    private void showCustomFeedDialog(final String existingId) {
        Context context = requireContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        NewsFeed existing = existingId == null
                ? null : NewsFeedStore.feedById(prefs, existingId);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        box.setPadding(pad, dp(context, 8), pad, 0);

        final EditText nameInput = new EditText(context);
        nameInput.setHint("Nimi (esim. Oma blogi)");
        nameInput.setSingleLine(true);
        if (existing != null) nameInput.setText(existing.name);
        box.addView(nameInput);

        final EditText urlInput = new EditText(context);
        urlInput.setHint("https://esimerkki.fi/feed/");
        urlInput.setSingleLine(true);
        urlInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) urlInput.setText(existing.url);
        box.addView(urlInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(existing == null ? "Lisää oma syöte" : "Muokkaa syötettä")
                .setView(box)
                .setPositiveButton("Tallenna", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (!isValidFeedUrl(url)) {
                        Toast.makeText(context,
                                "Virheellinen osoite. Käytä http(s)://-alkuista osoitetta.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (existingId == null) {
                        NewsFeedStore.addCustom(prefs, name, url);
                    } else {
                        NewsFeedStore.updateCustom(prefs, existingId, name, url);
                    }
                    rebuildCustomFeedPreferences();
                })
                .setNegativeButton("Peruuta", null);
        if (existingId != null) {
            builder.setNeutralButton("Poista", (dialog, which) -> {
                NewsFeedStore.removeCustom(prefs, existingId);
                rebuildCustomFeedPreferences();
            });
        }
        builder.show();
    }

    private static boolean isValidFeedUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
