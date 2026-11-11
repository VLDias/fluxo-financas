package app.fluxo.financas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import app.fluxo.financas.data.Db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FluxoApp::class)
class DbMigrationTest {

    /** Banco no formato 1.1.0 (versão 2), com uma configuração de salário já salva. */
    private class LegacyDb(context: Context) : SQLiteOpenHelper(context, "fluxo.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                   emoji TEXT NOT NULL, color INTEGER NOT NULL, kind TEXT NOT NULL, sort INTEGER NOT NULL DEFAULT 0)"""
            )
            db.execSQL(
                """CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
                   bank TEXT, color INTEGER NOT NULL DEFAULT 0)"""
            )
            db.execSQL(
                """CREATE TABLE txns(id INTEGER PRIMARY KEY AUTOINCREMENT, amount_cents INTEGER NOT NULL,
                   is_expense INTEGER NOT NULL, category_id INTEGER NOT NULL, account_id INTEGER,
                   merchant TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', date INTEGER NOT NULL,
                   source TEXT NOT NULL DEFAULT 'manual', source_hash TEXT UNIQUE, created_at INTEGER NOT NULL)"""
            )
            db.execSQL(
                """CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER NOT NULL,
                   ym TEXT NOT NULL, limit_cents INTEGER NOT NULL, UNIQUE(category_id, ym))"""
            )
            db.execSQL(
                """CREATE TABLE pending(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL,
                   app_label TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', text TEXT NOT NULL,
                   posted_at INTEGER NOT NULL, parsed_amount INTEGER, parsed_expense INTEGER,
                   merchant TEXT NOT NULL DEFAULT '', category_name TEXT,
                   confidence REAL NOT NULL DEFAULT 0, source_hash TEXT UNIQUE)"""
            )
            db.execSQL(
                """CREATE TABLE salary_config(id INTEGER PRIMARY KEY CHECK (id = 1),
                   gross_cents INTEGER NOT NULL DEFAULT 0, dependents INTEGER NOT NULL DEFAULT 0,
                   first_percent INTEGER NOT NULL DEFAULT 30, first_day INTEGER NOT NULL DEFAULT 19,
                   second_day INTEGER NOT NULL DEFAULT 0, irrf_on_first INTEGER NOT NULL DEFAULT 1,
                   thirteenth_enabled INTEGER NOT NULL DEFAULT 1, thirteenth_month1 INTEGER NOT NULL DEFAULT 11,
                   thirteenth_month2 INTEGER NOT NULL DEFAULT 12, months_worked INTEGER NOT NULL DEFAULT 12,
                   discounts_json TEXT NOT NULL DEFAULT '[]')"""
            )
            db.execSQL(
                """CREATE TABLE salary_vacations(ym TEXT PRIMARY KEY, days INTEGER NOT NULL DEFAULT 0,
                   sell_ten INTEGER NOT NULL DEFAULT 0)"""
            )
            db.insert("categories", null, ContentValues().apply {
                put("id", 1L); put("name", "Alimentação"); put("emoji", "🍽"); put("color", 0)
                put("kind", "expense"); put("sort", 1)
            })
            db.insert("accounts", null, ContentValues().apply { put("name", "Carteira"); put("color", 0) })
            db.insert("txns", null, ContentValues().apply {
                put("amount_cents", 4200L); put("is_expense", 1); put("category_id", 1L)
                put("merchant", "Café antigo"); put("date", System.currentTimeMillis())
                put("source", "manual"); put("created_at", System.currentTimeMillis())
            })
            db.insert("salary_config", null, ContentValues().apply {
                put("id", 1); put("gross_cents", 450000L); put("dependents", 1)
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Banco da 1.2.0 (versão 3): histórico ainda sem as colunas da 2ª parcela. */
    private class LegacyV3Db(context: Context) : SQLiteOpenHelper(context, "fluxo.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE salary_history(id INTEGER PRIMARY KEY AUTOINCREMENT,
                   effective_ym TEXT NOT NULL UNIQUE, gross_cents INTEGER NOT NULL DEFAULT 0,
                   dependents INTEGER NOT NULL DEFAULT 0, first_percent INTEGER NOT NULL DEFAULT 30,
                   first_day INTEGER NOT NULL DEFAULT 19, second_day INTEGER NOT NULL DEFAULT 0,
                   irrf_on_first INTEGER NOT NULL DEFAULT 1, thirteenth_enabled INTEGER NOT NULL DEFAULT 1,
                   thirteenth_month1 INTEGER NOT NULL DEFAULT 11, thirteenth_month2 INTEGER NOT NULL DEFAULT 12,
                   months_worked INTEGER NOT NULL DEFAULT 12, discounts_json TEXT NOT NULL DEFAULT '[]')"""
            )
            db.insert("salary_history", null, ContentValues().apply {
                put("effective_ym", "2026-01"); put("gross_cents", 111000L)
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Banco da 1.4.0 (versão 4): já tem as colunas da 2ª parcela, falta o dia do 13º. */
    private class LegacyV4Db(context: Context) : SQLiteOpenHelper(context, "fluxo.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE salary_history(id INTEGER PRIMARY KEY AUTOINCREMENT,
                   effective_ym TEXT NOT NULL UNIQUE, gross_cents INTEGER NOT NULL DEFAULT 0,
                   dependents INTEGER NOT NULL DEFAULT 0, first_percent INTEGER NOT NULL DEFAULT 30,
                   first_day INTEGER NOT NULL DEFAULT 19, second_day INTEGER NOT NULL DEFAULT 0,
                   second_next_month INTEGER NOT NULL DEFAULT 0,
                   second_business_day INTEGER NOT NULL DEFAULT 0,
                   second_business_day_number INTEGER NOT NULL DEFAULT 5,
                   irrf_on_first INTEGER NOT NULL DEFAULT 1, thirteenth_enabled INTEGER NOT NULL DEFAULT 1,
                   thirteenth_month1 INTEGER NOT NULL DEFAULT 11, thirteenth_month2 INTEGER NOT NULL DEFAULT 12,
                   months_worked INTEGER NOT NULL DEFAULT 12, discounts_json TEXT NOT NULL DEFAULT '[]')"""
            )
            db.insert("salary_history", null, ContentValues().apply {
                put("effective_ym", "2026-02"); put("gross_cents", 222000L)
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun `v4 gains thirteenth day columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("fluxo.db")
        val legacy = LegacyV4Db(context)
        legacy.writableDatabase.close()
        legacy.close()

        val db = Db(context).writableDatabase
        try {
            db.rawQuery(
                "SELECT gross_cents, thirteenth_day1, thirteenth_day2 FROM salary_history LIMIT 1", null
            ).use { c ->
                c.moveToFirst()
                assertEquals(222000L, c.getLong(0))
                assertEquals(30, c.getInt(1))
                assertEquals(20, c.getInt(2))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `v3 gains second payment columns without losing history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("fluxo.db")
        val legacy = LegacyV3Db(context)
        legacy.writableDatabase.close()
        legacy.close()

        val db = Db(context).writableDatabase
        try {
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(salary_history)", null).use { c ->
                while (c.moveToNext()) columns += c.getString(1)
            }
            assertTrue(columns.contains("second_next_month"))
            assertTrue(columns.contains("second_business_day"))
            assertTrue(columns.contains("second_business_day_number"))
            assertTrue(columns.contains("thirteenth_day1"))
            assertTrue(columns.contains("thirteenth_day2"))
            db.rawQuery(
                "SELECT gross_cents, second_business_day_number FROM salary_history LIMIT 1", null
            ).use { c ->
                c.moveToFirst()
                assertEquals(111000L, c.getLong(0))
                assertEquals(5, c.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrade from v2 keeps data and copies salary config to history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("fluxo.db")
        val legacy = LegacyDb(context)
        assertEquals(1, count(legacy.writableDatabase, "txns"))
        legacy.close()

        val db = Db(context).writableDatabase
        try {
            assertEquals(1, count(db, "txns"))
            assertEquals("Café antigo", db.rawQuery("SELECT merchant FROM txns LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            })
            assertEquals(1, count(db, "salary_history"))
            db.rawQuery("SELECT effective_ym, gross_cents FROM salary_history", null).use { c ->
                c.moveToFirst()
                assertEquals("2000-01", c.getString(0))
                assertEquals(450000L, c.getLong(1))
            }
            assertEquals(0, count(db, "salary_vacations"))

            db.execSQL("INSERT INTO salary_history(effective_ym, gross_cents) VALUES('2026-12', 700000)")
            assertEquals(2, count(db, "salary_history"))
        } finally {
            db.close()
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
