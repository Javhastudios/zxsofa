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
                extra_files TEXT,
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
        createMagazines(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nunca borrar la tabla: son los datos del usuario
        if (oldVersion < 2) db.execSQL("ALTER TABLE games ADD COLUMN extra_files TEXT")
        if (oldVersion < 3) createMagazines(db)
    }

    private fun createMagazines(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS magazines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                path TEXT NOT NULL,
                thumb_path TEXT,
                pages INTEGER NOT NULL DEFAULT 0,
                last_page INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // ------------------------------------------------------------ revistas

    fun magazines(): List<Magazine> = readableDatabase.query("magazines", null, null, null, null, null, null)
        .use { c -> buildList { while (c.moveToNext()) add(c.toMagazine()) } }
        .sortedWith { a, b -> naturalCompare(a.title, b.title) }

    fun magazine(id: Long): Magazine? = readableDatabase.query("magazines", null, "id=?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) c.toMagazine() else null }

    fun insertMagazine(m: Magazine): Long = writableDatabase.insertOrThrow("magazines", null, m.toValues())

    fun updateMagazine(m: Magazine) {
        writableDatabase.update("magazines", m.toValues(), "id=?", arrayOf(m.id.toString()))
    }

    fun deleteMagazine(id: Long) {
        writableDatabase.delete("magazines", "id=?", arrayOf(id.toString()))
    }

    private fun Magazine.toValues() = ContentValues().apply {
        put("title", title)
        put("path", path)
        put("thumb_path", thumbPath)
        put("pages", pages)
        put("last_page", lastPage)
        put("added_at", addedAt)
    }

    private fun Cursor.toMagazine() = Magazine(
        id = long("id"),
        title = str("title") ?: "",
        path = str("path") ?: "",
        thumbPath = str("thumb_path"),
        pages = long("pages").toInt(),
        lastPage = long("last_page").toInt(),
        addedAt = long("added_at"),
    )

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
        put("extra_files", extraFiles.joinToString("\n").ifBlank { null })
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
        extraFiles = str("extra_files")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
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
        const val VERSION = 3

        /** Orden "natural": MicroHobby 2 antes que MicroHobby 10. */
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                        ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size.compareTo(rb.size)
        }
    }
}
