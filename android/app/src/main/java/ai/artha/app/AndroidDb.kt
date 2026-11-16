package ai.artha.app

import ai.artha.core.Db
import ai.artha.core.Row
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Android's built-in SQLite, stored in the app's private storage (sandboxed and encrypted with the device). */
class AndroidDb(context: Context) : Db {
    private val helper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name("artha.db").callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {}
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            // The engine manages its own tables. Never refuse to open (or drop) a database over the version number:
            // v2.2 to v2.3.1 wrote 3 there by mistake.
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.query("PRAGMA secure_delete = ON").close() // deleted rows are overwritten, not just unlinked
            }
        }).build()
    )
    private val db: SupportSQLiteDatabase get() = helper.writableDatabase
    private val lock = Any()

    private fun norm(args: Array<out Any?>): Array<Any?> = args.map { if (it is Boolean) (if (it) 1L else 0L) else it }.toTypedArray()

    override fun query(sql: String, vararg args: Any?): List<Row> = synchronized(lock) {
        db.query(SimpleSQLiteQuery(sql, norm(args))).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                val r = LinkedHashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) r[c.getColumnName(i)] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> null
                }
                out += r
            }
            out
        }
    }

    override fun exec(sql: String, vararg args: Any?) = synchronized(lock) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, norm(args))
    }

    override fun insert(sql: String, vararg args: Any?): Long = synchronized(lock) {
        try {
            db.compileStatement(sql).use { st ->
                norm(args).forEachIndexed { i, a ->
                    when (a) {
                        null -> st.bindNull(i + 1)
                        is Long -> st.bindLong(i + 1, a)
                        is Int -> st.bindLong(i + 1, a.toLong())
                        is Double -> st.bindDouble(i + 1, a)
                        is Float -> st.bindDouble(i + 1, a.toDouble())
                        else -> st.bindString(i + 1, a.toString())
                    }
                }
                st.executeInsert()
            }
        } catch (e: SQLiteConstraintException) { -1L }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (db.inTransaction()) return block()
        db.beginTransaction()
        try { val r = block(); db.setTransactionSuccessful(); r } finally { db.endTransaction() }
    }

    override fun vacuum() = synchronized(lock) { db.execSQL("VACUUM") }
}
