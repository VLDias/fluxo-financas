package app.fluxo.financas.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("fluxo_prefs", Context.MODE_PRIVATE)

    var autoImport: Boolean
        get() = sp.getBoolean("auto_import", true)
        set(value) = sp.edit().putBoolean("auto_import", value).apply()

    var onlyBanks: Boolean
        get() = sp.getBoolean("only_banks", true)
        set(value) = sp.edit().putBoolean("only_banks", value).apply()

    var forecastStartOverride: Long
        get() = sp.getLong("forecast_start", Long.MIN_VALUE)
        set(value) = sp.edit().putLong("forecast_start", value).apply()

    var forecastIncomeGrowthBp: Int
        get() = sp.getInt("forecast_growth_income", 0)
        set(value) = sp.edit().putInt("forecast_growth_income", value).apply()

    var forecastExpenseGrowthBp: Int
        get() = sp.getInt("forecast_growth_expense", 0)
        set(value) = sp.edit().putInt("forecast_growth_expense", value).apply()

    var forecastMonths: Int
        get() = sp.getInt("forecast_months", 12)
        set(value) = sp.edit().putInt("forecast_months", value).apply()

    var onboardingSeen: Boolean
        get() = sp.getBoolean("onboarding_seen", false)
        set(value) = sp.edit().putBoolean("onboarding_seen", value).apply()

    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { it.contains(context.packageName) }
    }
}
