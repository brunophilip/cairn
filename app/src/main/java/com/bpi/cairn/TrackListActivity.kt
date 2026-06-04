package com.bpi.cairn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bpi.cairn.gpx.GpxParser // Ajout de l'import pour votre parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TrackListActivity : AppCompatActivity() {

    private lateinit var adapter: TrackAdapter
    private lateinit var txtEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_list)
        title = "Mes Traces"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        txtEmpty = findViewById<TextView>(R.id.txtEmpty)

        val dir = File(filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()

        val gpxFiles = dir.listFiles { _, name -> name.endsWith(".gpx") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (gpxFiles.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            txtEmpty.visibility = View.GONE
            recyclerView.layoutManager = LinearLayoutManager(this)

            // On calcule la distance et l'altitude en tâche de fond (Dispatchers.IO)
            lifecycleScope.launch(Dispatchers.IO) {
                val trackSummaries = gpxFiles.map { file ->
                    try {
                        val track = GpxParser.parse(file)
                        TrackSummary(
                            file = file,
                            distanceKm = track.totalDistanceMeters / 1000.0,
                            elevationGain = track.elevationGain
                        )
                    } catch (e: Exception) {
                        TrackSummary(file, 0.0, 0.0) // Si un fichier est corrompu
                    }
                }.toMutableList()

                // Une fois le calcul terminé, on affiche la liste (Dispatchers.Main)
                withContext(Dispatchers.Main) {
                    adapter = TrackAdapter(
                        tracks = trackSummaries,
                        onTrackSelected = { selectedFile ->
                            val resultIntent = Intent().apply {
                                putExtra("selected_file", selectedFile.absolutePath)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                        onMenuClicked = { anchorView, file, position ->
                            showOptions(anchorView, file, position)
                        }
                    )
                    recyclerView.adapter = adapter
                    recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- VOS FONCTIONS DE MENU INCHANGÉES ---

    private fun showOptions(anchor: View, file: File, position: Int) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Exporter")
        popup.menu.add("Supprimer")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Exporter" -> exportFile(file)
                "Supprimer" -> deleteFile(file, position)
            }
            true
        }
        popup.show()
    }

    private fun exportFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Partager la trace"))
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur d'export : ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFile(file: File, position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Voulez-vous supprimer définitivement cette trace ?")
            .setPositiveButton("Supprimer") { _, _ ->
                if (file.delete()) {
                    adapter.removeTrackAt(position)
                    if (adapter.getFilesCount() == 0) {
                        txtEmpty.visibility = View.VISIBLE
                        findViewById<RecyclerView>(R.id.recyclerView).visibility = View.GONE
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}