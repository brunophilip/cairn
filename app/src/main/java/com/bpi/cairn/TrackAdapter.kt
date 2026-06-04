package com.bpi.cairn

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Petite classe pour transporter les infos calculées
data class TrackSummary(
    val file: File,
    val distanceKm: Double,
    val elevationGain: Double
)

class TrackAdapter(
    private var tracks: MutableList<TrackSummary>, // On utilise TrackSummary au lieu de File
    private val onTrackSelected: (File) -> Unit,
    private val onMenuClicked: (View, File, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtTrackName: TextView = view.findViewById(R.id.txtTrackName)
        val txtTrackInfo: TextView = view.findViewById(R.id.txtTrackInfo)
        val btnMenu: TextView = view.findViewById(R.id.btnMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val file = track.file

        holder.txtTrackName.text = file.nameWithoutExtension.replace("_", " ")

        // Formatage Date, Distance et Dénivelé
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.FRANCE)
        val dateStr = sdf.format(Date(file.lastModified()))
        val distStr = String.format(Locale.FRANCE, "%.1f km", track.distanceKm)
        val eleStr = String.format(Locale.FRANCE, "%.0f m D+", track.elevationGain)

        holder.txtTrackInfo.text = "$dateStr • $distStr • $eleStr"

        holder.itemView.setOnClickListener { onTrackSelected(file) }
        holder.btnMenu.setOnClickListener { onMenuClicked(it, file, position) }
    }

    override fun getItemCount(): Int = tracks.size

    fun removeTrackAt(position: Int) {
        tracks.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, tracks.size)
    }

    fun getFilesCount(): Int = tracks.size
}