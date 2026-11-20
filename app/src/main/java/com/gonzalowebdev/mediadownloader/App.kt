package com.gonzalowebdev.mediadownloader

import android.app.Application
import androidx.room.Room
import com.gonzalowebdev.mediadownloader.data.AppDatabase
import com.gonzalowebdev.mediadownloader.data.Prefs
import com.gonzalowebdev.mediadownloader.domain.PinterestResolver
import com.gonzalowebdev.mediadownloader.domain.Resolver
import com.gonzalowebdev.mediadownloader.download.MediaStoreSaver
import com.gonzalowebdev.mediadownloader.download.Notifs
import com.gonzalowebdev.mediadownloader.download.Repo
import com.gonzalowebdev.mediadownloader.net.PinterestApi
import com.gonzalowebdev.mediadownloader.net.PinterestAuth
import com.gonzalowebdev.mediadownloader.net.YtDlp

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var db: AppDatabase
    lateinit var auth: PinterestAuth
    lateinit var api: PinterestApi
    lateinit var resolver: Resolver
    lateinit var saver: MediaStoreSaver
    lateinit var repo: Repo

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "mdh.db").build()
        auth = PinterestAuth(prefs)
        api = PinterestApi(auth)
        resolver = Resolver(this, PinterestResolver(api, auth))
        saver = MediaStoreSaver(this)
        repo = Repo(this)
        Notifs.createChannels(this)
        // Inicia yt-dlp en segundo plano y lo actualiza como máximo una vez por día.
        kotlin.concurrent.thread(name = "ytdlp-init") {
            try {
                YtDlp.ensure(this)
                val now = System.currentTimeMillis()
                if (now - prefs.ytdlpLastUpdate > 24L * 3600 * 1000) {
                    YtDlp.update(this)
                    prefs.ytdlpLastUpdate = now
                }
            } catch (e: Exception) { /* se informa al usar la descarga */ }
        }
    }
}
