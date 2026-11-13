package com.manhwalens.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {

    private fun stored(map: Map<String, Any?>) = SettingsMigration.storedVersion(map)
    private fun migrate(map: Map<String, Any?>, from: Int? = null) =
        SettingsMigration.migrate(map, from ?: stored(map))

    @Test
    fun freshStoreIsStampedCurrentVersionWithNothingWiped() {
        val r = migrate(emptyMap())
        assertEquals(SettingsMigration.CURRENT_VERSION, r.newVersion)
        assertTrue("nothing to wipe in a fresh store", r.wipe.isEmpty())
        assertEquals(1, SettingsMigration.storedVersion(emptyMap())) // legacy default
    }

    @Test
    fun validStoreIsFullyKept() {
        val all = mapOf(
            "settings_version" to 2, // current version — migration is a no-op
            "font_scale" to 1.2f,
            "ignore_top_pct" to 16.0f,
            "live_mode" to true,
            "live_stable_ms" to 500L,   // long-stored int is tolerated
            "ignore_phrases" to "one\ntwo",
            "live_sensitivity" to "high"
        )
        val r = migrate(all)
        assertEquals(2, r.newVersion)
        assertTrue("valid keys must survive", r.wipe.isEmpty())
    }

    @Test
    fun corruptBooleanStoredAsStringIsWiped() {
        val r = migrate(mapOf("live_mode" to "yes", "debug_mode" to false))
        assertEquals(listOf("live_mode"), r.wipe)
    }

    @Test
    fun corruptFloatAndIntAreWiped() {
        val r = migrate(
            mapOf(
                "font_scale" to "1.5",       // String where a float is expected
                "live_stable_ms" to "400",   // String where an int is expected
                "ignore_bottom_pct" to 10    // int in a float slot → tolerated (Number)
            )
        )
        assertEquals(setOf("font_scale", "live_stable_ms"), r.wipe.toSet())
    }

    @Test
    fun unknownKeysAreReset() {
        // e.g. keys left behind by a different build — spec: reset unknown values
        val r = migrate(mapOf("some_foreign_key" to 123, "ignore_phrases" to "abc"))
        assertEquals(listOf("some_foreign_key"), r.wipe)
    }

    @Test
    fun wrongTypeInStringSlotIsWiped() {
        val r = migrate(mapOf("live_sensitivity" to 42, "live_allowed_apps" to true))
        assertEquals(setOf("live_sensitivity", "live_allowed_apps"), r.wipe.toSet())
    }

    @Test
    fun newerVersionIsNeverDowngradedOrWiped() {
        // Downgrade from a future build: leave everything untouched.
        val all = mapOf("settings_version" to 99, "future_key" to "x")
        val r = migrate(all, from = 99)
        assertTrue(r.wipe.isEmpty())
        assertEquals(99, r.newVersion)
    }

    @Test
    fun nullValuesAreWiped() {
        val r = migrate(mapOf("live_mode" to null, "ignore_phrases" to "ok"))
        assertEquals(listOf("live_mode"), r.wipe)
    }

    @Test
    fun mixedStoreOnlyCorruptEntriesAreWiped() {
        val all = mapOf(
            "settings_version" to 1,
            "live_mode" to true,
            "live_stable_ms" to 250,
            "font_scale" to "bigger",   // corrupt
            "obsolete_flag" to true     // unknown
        )
        val r = migrate(all)
        assertEquals(SettingsMigration.CURRENT_VERSION, r.newVersion)
        assertEquals(setOf("font_scale", "obsolete_flag"), r.wipe.toSet())
    }

    // ---- v0.9.4 Part C: the new "Tap on the lens" key ----
    @Test
    fun lensTapModeIsAKnownStringKey() {
        assertTrue(SettingsMigration.isKnown("lens_tap_mode"))
        assertTrue(SettingsMigration.typeOk("lens_tap_mode", "menu"))
        assertTrue(SettingsMigration.typeOk("lens_tap_mode", "translate"))
        assertFalse("a wrong type must be wiped back to the default (Menu)", SettingsMigration.typeOk("lens_tap_mode", 3))
        assertFalse(SettingsMigration.typeOk("lens_tap_mode", null))
    }

    @Test
    fun corruptLensTapModeIsWipedAndAValidOneIsKept() {
        // A wrong-typed value is only repaired by the one-shot migration (legacy store).
        val wiped = migrate(mapOf("settings_version" to 1, "lens_tap_mode" to 7))
        assertEquals(setOf("lens_tap_mode"), wiped.wipe.toSet())
        val kept = migrate(mapOf("settings_version" to 1, "lens_tap_mode" to "translate"))
        assertEquals(emptyList<String>(), kept.wipe)
        // Same-or-newer layout: never touched (no user data is destroyed on a downgrade).
        val untouched = migrate(mapOf("settings_version" to 2, "lens_tap_mode" to 7))
        assertEquals(emptyList<String>(), untouched.wipe)
    }

    // ---- storedVersion ----
    @Test
    fun storedVersionDefaultsToLegacyWhenMissing() {
        assertEquals(1, stored(emptyMap()))
    }

    @Test
    fun storedVersionReadsLongAndInt() {
        assertEquals(2, stored(mapOf("settings_version" to 2)))
        assertEquals(2, stored(mapOf("settings_version" to 2L)))
        assertEquals(1, stored(mapOf("settings_version" to "two")))
    }
}
