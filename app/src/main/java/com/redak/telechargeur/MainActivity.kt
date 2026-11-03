package com.redak.telechargeur

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var lien: EditText
    private lateinit var audio: RadioButton
    private lateinit var qualite: Spinner
    private lateinit var bouton: Button
    private lateinit var progression: LinearProgressIndicator
    private lateinit var statut: TextView

    private val qualites = arrayOf("Meilleure", "1080p", "720p", "480p", "360p")

    companion object {
        @Volatile private var pret = false
        private val verrou = Mutex()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lien = findViewById(R.id.lien)
        audio = findViewById(R.id.audio)
        qualite = findViewById(R.id.qualite)
        bouton = findViewById(R.id.telecharger)
        progression = findViewById(R.id.progression)
        statut = findViewById(R.id.statut)

        qualite.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualites)

        findViewById<Button>(R.id.coller).setOnClickListener { coller() }
        bouton.setOnClickListener { telecharger() }

        lireLienPartage(intent)

        // Prépare le moteur dès l'ouverture (le 1er lancement prend quelques secondes)
        lifecycleScope.launch { initialiser() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lireLienPartage(intent)
    }

    // ---------- Lien reçu via « Partager » ----------
    private fun lireLienPartage(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val texte = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extraireLien(texte) ?: return
            lien.setText(url)
            telecharger()
        }
    }

    private fun extraireLien(texte: String): String? =
        Regex("https?://\\S+").find(texte)?.value

    private fun coller() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val texte = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val url = extraireLien(texte)
        if (url != null) lien.setText(url)
        else Toast.makeText(this, "Aucun lien dans le presse-papiers", Toast.LENGTH_SHORT).show()
    }

    // ---------- Moteur ----------
    private suspend fun initialiser(): Boolean = verrou.withLock {
        if (pret) return@withLock true
        withContext(Dispatchers.Main) { statut.text = "Préparation de l'application…" }
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                try {
                    // Met à jour yt-dlp pour suivre les changements des sites
                    YoutubeDL.getInstance().updateYoutubeDL(
                        applicationContext, YoutubeDL.UpdateChannel.STABLE
                    )
                } catch (_: Exception) { }
                pret = true
            } catch (e: Exception) {
                pret = false
            }
        }
        withContext(Dispatchers.Main) {
            statut.text = if (pret) "Prêt." else "Erreur de préparation. Redémarrez l'application."
        }
        pret
    }

    // ---------- Téléchargement ----------
    private fun telecharger() {
        val url = extraireLien(lien.text.toString().trim())
        if (url == null) {
            Toast.makeText(this, "Collez d'abord un lien valide", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
            return
        }

        val enAudio = audio.isChecked
        val q = qualites[qualite.selectedItemPosition]

        bouton.isEnabled = false
        progression.setProgressCompat(0, false)

        lifecycleScope.launch {
            if (!initialiser()) {
                bouton.isEnabled = true
                return@launch
            }
            statut.text = "Analyse du lien…"
            val dossierTemp = File(cacheDir, "telechargements").apply {
                deleteRecursively(); mkdirs()
            }

            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-o", dossierTemp.absolutePath + "/%(title).70s.%(ext)s")
                if (enAudio) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                } else {
                    val h = if (q == "Meilleure") "" else "[height<=${q.dropLast(1)}]"
                    addOption("-f", "bv*$h+ba/b$h/b")
                    addOption("--merge-output-format", "mp4")
                }
            }

            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(req, "dl") { pct, _, _ ->
                        runOnUiThread {
                            if (pct >= 0) {
                                progression.setProgressCompat(pct.toInt(), true)
                                statut.text = "Téléchargement… ${pct.toInt()} %"
                            }
                        }
                    }
                }
                statut.text = "Enregistrement…"
                val fichiers = withContext(Dispatchers.IO) {
                    dossierTemp.listFiles()
                        ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                        ?.mapNotNull { enregistrer(it, enAudio) }
                        ?: emptyList()
                }
                if (fichiers.isEmpty()) throw Exception("Aucun fichier obtenu.")
                progression.setProgressCompat(100, true)
                val ou = if (enAudio) "Musique/Telechargeur" else "Films/Telechargeur"
                statut.text = "✔ Terminé ! Enregistré dans $ou"
                Toast.makeText(this@MainActivity, "Téléchargement terminé ✔", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                progression.setProgressCompat(0, false)
                var msg = (e.message ?: "Erreur inconnue").replace("ERROR: ", "").take(300)
                if (msg.contains("login", true) || msg.contains("private", true)) {
                    msg += "\n\nCette vidéo est peut-être privée ou demande une connexion."
                }
                statut.text = "✖ Échec : $msg"
            } finally {
                bouton.isEnabled = true
                dossierTemp.deleteRecursively()
            }
        }
    }

    // Copie le fichier dans la galerie (Films) ou dans Musique
    private fun enregistrer(f: File, enAudio: Boolean): Uri? {
        val ext = f.extension.lowercase()
        val estAudio = enAudio || ext in listOf("mp3", "m4a", "opus", "aac")
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> if (estAudio) "audio/webm" else "video/webm"
            else -> if (estAudio) "audio/mpeg" else "video/mp4"
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            val valeurs = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (estAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Telechargeur"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (estAudio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, valeurs) ?: return null
            contentResolver.openOutputStream(uri)?.use { sortie ->
                f.inputStream().use { it.copyTo(sortie) }
            }
            valeurs.clear()
            valeurs.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, valeurs, null, null)
            f.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                if (estAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            )
            val dossier = File(base, "Telechargeur").apply { mkdirs() }
            val dest = File(dossier, f.name)
            f.copyTo(dest, overwrite = true)
            f.delete()
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
            Uri.fromFile(dest)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) telecharger()
    }
}
