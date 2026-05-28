package org.jrs82.fsclock.mobile;

import org.jrs82.fsclock.R;
import org.jrs82.fsclock.system.SystemActivity;

public class MobileSystemActivity extends SystemActivity {

    @Override
    protected int activityTheme() {
        return R.style.MobileSettingsTheme;
    }

    @Override
    protected CharSequence activityTitle() {
        return getString(R.string.mobile_menu_system);
    }
}
