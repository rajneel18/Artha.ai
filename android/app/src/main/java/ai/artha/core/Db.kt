package ai.artha.core

import kotlinx.serialization.json.*

/** A row from SQLite: values are Long, Double, String or null. */
typealias Row = Map<String, Any?>

/** Minimal database interface so the engine runs on Android SQLite and, in tests, on JDBC SQLite. */
interface Db {
    fun query(sql: String, vararg args: Any?): List<Row>
    fun exec(sql: String, vararg args: Any?)
    /** Runs an INSERT and returns the new row id, or -1 if a constraint (e.g. UNIQUE) rejected it. */
    fun insert(sql: String, vararg args: Any?): Long
    fun <T> transaction(block: () -> T): T
    /** Rewrites the database file so deleted data is gone from disk. Must run outside a transaction. */
    fun vacuum()
}

fun Db.one(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()
fun Db.scalar(sql: String, vararg args: Any?): Any? = one(sql, *args)?.values?.firstOrNull()
fun Db.num(sql: String, vararg args: Any?): Double = (scalar(sql, *args) as? Number)?.toDouble() ?: 0.0
fun Db.count(sql: String, vararg args: Any?): Long = (scalar(sql, *args) as? Number)?.toLong() ?: 0L

fun Row.s(k: String): String? = this[k]?.toString()
fun Row.d(k: String): Double? = (this[k] as? Number)?.toDouble()
fun Row.l(k: String): Long? = (this[k] as? Number)?.toLong()
fun Row.b(k: String): Boolean = ((this[k] as? Number)?.toLong() ?: 0L) != 0L

fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

// ---------- JSON <-> Kotlin values ----------

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) JsonPrimitive(v) else JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}

fun fromJson(e: JsonElement?): Any? = when (e) {
    null, JsonNull -> null
    is JsonObject -> e.mapValues { fromJson(it.value) }
    is JsonArray -> e.map { fromJson(it) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.boolean
        e.longOrNull != null -> e.long
        else -> e.doubleOrNull
    }
}

@Suppress("UNCHECKED_CAST")
fun parseBody(body: String?): Map<String, Any?> =
    if (body.isNullOrBlank()) emptyMap() else (fromJson(Json.parseToJsonElement(body)) as? Map<String, Any?>) ?: emptyMap()

fun Map<String, Any?>.str(k: String): String? = (this[k] as? String)?.takeIf { it.isNotBlank() }
fun Map<String, Any?>.dbl(k: String): Double? = when (val v = this[k]) {
    is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
}
fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.strList(k: String): List<String>? = (this[k] as? List<Any?>)?.mapNotNull { it?.toString() }

class ApiError(val status: Int, message: String) : Exception(message)
