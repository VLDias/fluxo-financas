package app.fluxo.financas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "fluxo.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE categories(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                emoji TEXT NOT NULL,
                color INTEGER NOT NULL,
                kind TEXT NOT NULL,
                sort INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE accounts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                bank TEXT,
                color INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE txns(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                is_expense INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                account_id INTEGER,
                merchant TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                date INTEGER NOT NULL,
                source TEXT NOT NULL DEFAULT 'manual',
                source_hash TEXT UNIQUE,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_txns_date ON txns(date)")
        db.execSQL("CREATE INDEX idx_txns_cat ON txns(category_id)")
        db.execSQL(
            """CREATE TABLE budgets(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category_id INTEGER NOT NULL,
                ym TEXT NOT NULL,
                limit_cents INTEGER NOT NULL,
                UNIQUE(category_id, ym)
            )"""
        )
        db.execSQL(
            """CREATE TABLE pending(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app_label TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                parsed_amount INTEGER,
                parsed_expense INTEGER,
                merchant TEXT NOT NULL DEFAULT '',
                category_name TEXT,
                confidence REAL NOT NULL DEFAULT 0,
                source_hash TEXT UNIQUE
            )"""
        )
        createSalaryTables(db)
        createSalaryHistory(db)
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSalaryTables(db)
        if (oldVersion < 3) {
            createSalaryHistory(db)
            db.execSQL(
                """INSERT OR IGNORE INTO salary_history(
                       effective_ym, gross_cents, dependents, first_percent, first_day, second_day,
                       irrf_on_first, thirteenth_enabled, thirteenth_month1, thirteenth_month2,
                       months_worked, discounts_json)
                   SELECT '2000-01', gross_cents, dependents, first_percent, first_day, second_day,
                       irrf_on_first, thirteenth_enabled, thirteenth_month1, thirteenth_month2,
                       months_worked, discounts_json
                   FROM salary_config WHERE id = 1"""
            )
        }
        if (oldVersion == 3) {
            db.execSQL("ALTER TABLE salary_history ADD COLUMN second_next_month INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE salary_history ADD COLUMN second_business_day INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE salary_history ADD COLUMN second_business_day_number INTEGER NOT NULL DEFAULT 5")
        }
        if (oldVersion == 3 || oldVersion == 4) {
            db.execSQL("ALTER TABLE salary_history ADD COLUMN thirteenth_day1 INTEGER NOT NULL DEFAULT 30")
            db.execSQL("ALTER TABLE salary_history ADD COLUMN thirteenth_day2 INTEGER NOT NULL DEFAULT 20")
        }
    }

    private fun createSalaryHistory(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS salary_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                effective_ym TEXT NOT NULL UNIQUE,
                gross_cents INTEGER NOT NULL DEFAULT 0,
                dependents INTEGER NOT NULL DEFAULT 0,
                first_percent INTEGER NOT NULL DEFAULT 30,
                first_day INTEGER NOT NULL DEFAULT 19,
                second_day INTEGER NOT NULL DEFAULT 0,
                second_next_month INTEGER NOT NULL DEFAULT 0,
                second_business_day INTEGER NOT NULL DEFAULT 0,
                second_business_day_number INTEGER NOT NULL DEFAULT 5,
                irrf_on_first INTEGER NOT NULL DEFAULT 1,
                thirteenth_enabled INTEGER NOT NULL DEFAULT 1,
                thirteenth_month1 INTEGER NOT NULL DEFAULT 11,
                thirteenth_month2 INTEGER NOT NULL DEFAULT 12,
                thirteenth_day1 INTEGER NOT NULL DEFAULT 30,
                thirteenth_day2 INTEGER NOT NULL DEFAULT 20,
                months_worked INTEGER NOT NULL DEFAULT 12,
                discounts_json TEXT NOT NULL DEFAULT '[]'
            )"""
        )
    }

    private fun createSalaryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS salary_config(
                id INTEGER PRIMARY KEY CHECK (id = 1),
                gross_cents INTEGER NOT NULL DEFAULT 0,
                dependents INTEGER NOT NULL DEFAULT 0,
                first_percent INTEGER NOT NULL DEFAULT 30,
                first_day INTEGER NOT NULL DEFAULT 19,
                second_day INTEGER NOT NULL DEFAULT 0,
                irrf_on_first INTEGER NOT NULL DEFAULT 1,
                thirteenth_enabled INTEGER NOT NULL DEFAULT 1,
                thirteenth_month1 INTEGER NOT NULL DEFAULT 11,
                thirteenth_month2 INTEGER NOT NULL DEFAULT 12,
                months_worked INTEGER NOT NULL DEFAULT 12,
                discounts_json TEXT NOT NULL DEFAULT '[]'
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS salary_vacations(
                ym TEXT PRIMARY KEY,
                days INTEGER NOT NULL DEFAULT 0,
                sell_ten INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    private fun seed(db: SQLiteDatabase) {
        val cats = listOf(
            arrayOf<Any>(1L, "Alimentação", "\uD83C\uDF7D\uFE0F", 0xFFE8734A.toInt(), "expense", 1),
            arrayOf<Any>(2L, "Mercado", "\uD83D\uDED2", 0xFF2F9E68.toInt(), "expense", 2),
            arrayOf<Any>(3L, "Transporte", "\uD83D\uDE95", 0xFF3B82C4.toInt(), "expense", 3),
            arrayOf<Any>(4L, "Moradia", "\uD83C\uDFE0", 0xFF8A6FD1.toInt(), "expense", 4),
            arrayOf<Any>(5L, "Saúde", "\uD83D\uDC8A", 0xFFD95F8D.toInt(), "expense", 5),
            arrayOf<Any>(6L, "Educação", "\uD83D\uDCDA", 0xFFC9A227.toInt(), "expense", 6),
            arrayOf<Any>(7L, "Lazer", "\uD83C\uDFAC", 0xFFF2A93B.toInt(), "expense", 7),
            arrayOf<Any>(8L, "Assinaturas", "\uD83D\uDCF1", 0xFF5C7CFA.toInt(), "expense", 8),
            arrayOf<Any>(9L, "Compras", "\uD83D\uDECD\uFE0F", 0xFFD96C9E.toInt(), "expense", 9),
            arrayOf<Any>(10L, "Serviços", "\uD83E\uDDFE", 0xFF6B8F8A.toInt(), "expense", 10),
            arrayOf<Any>(11L, "Impostos", "\uD83C\uDFDB\uFE0F", 0xFFA16207.toInt(), "expense", 11),
            arrayOf<Any>(12L, "Outros", "\u2728", 0xFF7A8C86.toInt(), "expense", 12),
            arrayOf<Any>(13L, "Salário", "\uD83D\uDCB0", 0xFF1F9D63.toInt(), "income", 13),
            arrayOf<Any>(14L, "Outras receitas", "\u2795", 0xFF37A2A2.toInt(), "income", 14)
        )
        for (c in cats) {
            val v = ContentValues().apply {
                put("id", c[0] as Long)
                put("name", c[1] as String)
                put("emoji", c[2] as String)
                put("color", c[3] as Int)
                put("kind", c[4] as String)
                put("sort", c[5] as Int)
            }
            db.insert("categories", null, v)
        }
        db.insert("accounts", null, ContentValues().apply {
            put("name", "Carteira"); put("bank", "Dinheiro"); put("color", 0xFF128A6B.toInt())
        })
        db.insert("accounts", null, ContentValues().apply {
            put("name", "Conta principal"); put("bank", "Banco"); put("color", 0xFF3B82C4.toInt())
        })
    }
}
