package com.bpi.cairn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class TrackListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: TrackFileAdapter
    private var files = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        listView = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.DKGRAY)
            dividerHeight = 1
        }

        layout.addView(listView)
        setContentView(layout)
        title = "Mes Traces"

        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        refreshList()
    }

    private fun refreshList() {
        val dir = File(filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()

        files = (dir.listFiles { f -> f.extension.lowercase() == "gpx" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf())

        if (files.isEmpty()) {
            Toast.makeText(this, "Aucune trace disponible", Toast.LENGTH_SHORT).show()
            finish()
        }

        adapter = TrackFileAdapter(this, files)
        listView.adapter = adapter
    }

    // --- ADAPTER PERSONNALISÉ POUR AJOUTER LES 3 POINTS ---
    inner class TrackFileAdapter(context: Context, private val trackFiles: MutableList<File>) :
        ArrayAdapter<File>(context, 0, trackFiles) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val file = getItem(position)!!

            // Création de la ligne (Horizontale)
            val row = convertView ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 50, 40, 50)
                gravity = Gravity.CENTER_VERTICAL
            }
            val layout = row as LinearLayout
            layout.removeAllViews()

            // Texte du nom du fichier
            val textView = TextView(context).apply {
                text = file.nameWithoutExtension.replace("_", " ")
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val intent = Intent().apply { putExtra("selected_file", file.absolutePath) }
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                }
            }

            // Bouton 3 points (Menu)
            val menuButton = TextView(context).apply {
                text = "⋮" // Caractère vertical ellipsis
                setTextColor(android.graphics.Color.GRAY)
                textSize = 24f
                setPadding(30, 10, 30, 10)
                setOnClickListener { showOptions(it, file, position) }
            }

            layout.addView(textView)
            layout.addView(menuButton)

            return layout
        }
    }

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
            Toast.makeText(this, "Erreur d'export", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFile(file: File, position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Voulez-vous supprimer définitivement cette trace ?")
            .setPositiveButton("Supprimer") { _, _ ->
                if (file.delete()) {
                    files.removeAt(position)
                    adapter.notifyDataSetChanged()
                    if (files.isEmpty()) finish()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}