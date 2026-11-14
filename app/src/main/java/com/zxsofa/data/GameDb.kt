package com.zxsofa.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Base de datos local (SQLite) con los juegos del usuario. */
class GameDb(context: Context) : SQLiteOpenHelper(context, "games.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE games (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                file_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                md5 TEXT NOT NULL,
                year TEXT,
                publisher TEXT,
                genre TEXT,
                machine TEXT NOT NULL,
                controls TEXT,
                cover_path TEXT,
                screen_path TEXT,
                zxdb_id TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                last_played INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0,
                notes TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_games_md5 ON games(md5)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Futuras migraciones aquí (nunca borrar la tabla: son los datos del usuario)
    }

    fun all(): List<Game> = readableDatabase.query("games", null, null, null, null, null, "title COLLATE NOCASE")
        .use { c -> buildList { while (c.moveToNext()) add(c.toGame()) } }

    fun get(id: Long): Game? = readableDatabase.query("games", null, "id=?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toGame() else null }

    fun findByMd5(md5: String): Game? = readableDatabase.query("games", null, "md5=?", arrayOf(md5), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toGame() else null }

    fun insert(g: Game): Long = writableDatabase.insertOrThrow("games", null, g.toValues())

    fun update(g: Game) {
        writableDatabase.update("games", g.toValues(), "id=?", arrayOf(g.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("games", "id=?", arrayOf(id.toString()))
    }

    private fun Game.toValues() = ContentValues().apply {
        put("title", title)
        put("file_name", fileName)
        put("file_path", filePath)
        put("md5", md5)
        put("year", year)
        put("publisher", publisher)
        put("genre", genre)
        put("machine", machine.coreValue)
        put("controls", controls.serialize())
        put("cover_path", coverPath)
        put("screen_path", screenPath)
        put("zxdb_id", zxdbId)
        put("favorite", if (favorite) 1 else 0)
        put("added_at", addedAt)
        put("last_played", lastPlayed)
        put("play_count", playCount)
        put("notes", notes)
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun Cursor.toGame() = Game(
        id = long("id"),
        title = str("title") ?: "",
        fileName = str("file_name") ?: "",
        filePath = str("file_path") ?: "",
        md5 = str("md5") ?: "",
        year = str("year"),
        publisher = str("publisher"),
        genre = str("genre"),
        machine = Machine.fromCore(str("machine")),
        controls = ControlConfig.parse(str("controls")),
        coverPath = str("cover_path"),
        screenPath = str("screen_path"),
        zxdbId = str("zxdb_id"),
        favorite = long("favorite") != 0L,
        addedAt = long("added_at"),
        lastPlayed = long("last_played"),
        playCount = long("play_count").toInt(),
        notes = str("notes"),
    )

    companion object {
        const val VERSION = 1
    }
}
