package org.jrs82.fsclock.mobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.SettingsFragment;
import org.jrs82.fsclock.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
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
            updateWidgetOrderSummary();
            widgetOrderPreference.setOnPreferenceClickListener(preference -> {
                showWidgetOrderDialog();
                return true;
            });
        }
        Preference openHistory = findPreference("open_history");
        if (openHistory != null) {
            openHistory.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), MobileHistoryActivity.class));
                return true;
            });
        }
    }

    private boolean hasPreciseLocationPermission() {
        Context context = getContext();
        return context != null
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void showWidgetOrderDialog() {
        Context context = requireContext();
        List<String> order = loadWidgetOrder(context);
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        list.setPadding(pad, dp(context, 4), pad, dp(context, 4));
        scroll.addView(list);
        renderWidgetOrderRows(context, list, order);

        new AlertDialog.Builder(context)
                .setTitle(R.string.mobile_pref_widget_order)
                .setView(scroll)
                .setPositiveButton("Valmis", null)
                .show();
    }

    private void renderWidgetOrderRows(Context context, LinearLayout list, List<String> order) {
        list.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(context, 4), 0, dp(context, 4));

            TextView name = new TextView(context);
            name.setText(widgetTitle(order.get(i)));
            name.setTextColor(ContextCompat.getColor(context, R.color.mobile_text_primary));
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            ImageButton up = widgetMoveButton(context, R.drawable.mobile_ic_arrow_up_24,
                    index > 0, getString(R.string.mobile_widget_move_up));
            up.setOnClickListener(v -> {
                Collections.swap(order, index, index - 1);
                saveWidgetOrder(context, order);
                updateWidgetOrderSummary();
                renderWidgetOrderRows(context, list, order);
            });
            row.addView(up);

            ImageButton down = widgetMoveButton(context, R.drawable.mobile_ic_arrow_down_24,
                    index < order.size() - 1, getString(R.string.mobile_widget_move_down));
            down.setOnClickListener(v -> {
                Collections.swap(order, index, index + 1);
                saveWidgetOrder(context, order);
                updateWidgetOrderSummary();
                renderWidgetOrderRows(context, list, order);
            });
            row.addView(down);

            list.addView(row);
        }
    }

    private ImageButton widgetMoveButton(Context context, int iconRes, boolean enabled,
                                         String contentDescription) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconRes);
        button.setBackgroundResource(R.drawable.mobile_menu_item_bg);
        button.setColorFilter(ContextCompat.getColor(context, R.color.mobile_text_primary));
        button.setContentDescription(contentDescription);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44));
        lp.setMarginStart(dp(context, 8));
        button.setLayoutParams(lp);
        return button;
    }

    private void updateWidgetOrderSummary() {
        if (widgetOrderPreference == null || getContext() == null) return;
        List<String> order = loadWidgetOrder(requireContext());
        StringBuilder summary = new StringBuilder();
        for (String id : order) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(widgetTitle(id));
        }
        widgetOrderPreference.setSummary(summary.toString());
    }

    private List<String> loadWidgetOrder(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(MobileThemeController.KEY_WIDGET_ORDER,
                MobileThemeController.DEFAULT_WIDGET_ORDER);
        List<String> order = new ArrayList<>();
        if (raw != null) {
            for (String token : raw.split(",")) {
                String id = token.trim();
                if (isKnownWidget(id) && !order.contains(id)) order.add(id);
            }
        }
        addMissingWidget(order, MobileThemeController.WIDGET_WEATHER);
        addMissingWidget(order, MobileThemeController.WIDGET_ELECTRICITY);
        addMissingWidget(order, MobileThemeController.WIDGET_WARNINGS);
        addMissingWidget(order, MobileThemeController.WIDGET_SENSORS);
        addMissingWidget(order, MobileThemeController.WIDGET_TRAFFIC);
        addMissingWidget(order, MobileThemeController.WIDGET_GPS_SPEED);
        addMissingWidget(order, MobileThemeController.WIDGET_ROAD_CAMERAS);
        return order;
    }

    private void saveWidgetOrder(Context context, List<String> order) {
        StringBuilder raw = new StringBuilder();
        for (String id : order) {
            if (raw.length() > 0) raw.append(',');
            raw.append(id);
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(MobileThemeController.KEY_WIDGET_ORDER, raw.toString())
                .apply();
    }

    private static void addMissingWidget(List<String> order, String id) {
        if (!order.contains(id)) order.add(id);
    }

    private static boolean isKnownWidget(String id) {
        return MobileThemeController.WIDGET_WEATHER.equals(id)
                || MobileThemeController.WIDGET_ELECTRICITY.equals(id)
                || MobileThemeController.WIDGET_WARNINGS.equals(id)
                || MobileThemeController.WIDGET_SENSORS.equals(id)
                || MobileThemeController.WIDGET_TRAFFIC.equals(id)
                || MobileThemeController.WIDGET_GPS_SPEED.equals(id)
                || MobileThemeController.WIDGET_ROAD_CAMERAS.equals(id);
    }

    private String widgetTitle(String id) {
        if (MobileThemeController.WIDGET_WEATHER.equals(id)) {
            return getString(R.string.mobile_widget_weather);
        }
        if (MobileThemeController.WIDGET_ELECTRICITY.equals(id)) {
            return getString(R.string.mobile_widget_electricity);
        }
        if (MobileThemeController.WIDGET_WARNINGS.equals(id)) {
            return getString(R.string.mobile_widget_warnings);
        }
        if (MobileThemeController.WIDGET_SENSORS.equals(id)) {
            return getString(R.string.mobile_widget_sensors);
        }
        if (MobileThemeController.WIDGET_TRAFFIC.equals(id)) {
            return getString(R.string.mobile_widget_traffic);
        }
        if (MobileThemeController.WIDGET_GPS_SPEED.equals(id)) {
            return getString(R.string.mobile_widget_gps_speed);
        }
        if (MobileThemeController.WIDGET_ROAD_CAMERAS.equals(id)) {
            return getString(R.string.mobile_widget_road_cameras);
        }
        return id;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
