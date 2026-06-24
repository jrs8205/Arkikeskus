package org.jrs82.fsclock.mobile

import android.content.SharedPreferences

/** Muistinvarainen SharedPreferences-fake JVM-yksikkötesteihin (jaettu NewsProfile-testien kesken). */
internal class FakeSharedPreferences : SharedPreferences {
    val map = HashMap<String, Any?>()

    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun getAll(): MutableMap<String, *> = map
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) pending[key] = values; return this }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { if (key != null) removals.add(key); return this }
    override fun clear(): SharedPreferences.Editor { prefs.map.clear(); return this }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() {
        for (k in removals) prefs.map.remove(k)
        prefs.map.putAll(pending)
        pending.clear(); removals.clear()
    }
}
