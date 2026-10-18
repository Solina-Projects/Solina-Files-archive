package ir.solina.fm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileListAdapter(
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (File) -> Unit,
    private val isSelected: (File) -> Boolean,
    private val formatSize: (Long) -> String,
    private val formatDate: (Long) -> String
) : ListAdapter<File, FileListAdapter.FileViewHolder>(FileDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = getItem(position)
        holder.bind(file)
    }

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val name: TextView = view.findViewById(R.id.name)
        private val info: TextView = view.findViewById(R.id.info)

        fun bind(file: File) {
            name.text = file.name
            
            val iconRes = if (file.isDirectory) {
                R.drawable.ic_folder
            } else {
                FileTypeVisuals.getIconForExtension(file.extension)
            }
            icon.setImageResource(iconRes)

            val dateStr = formatDate(file.lastModified())
            info.text = if (file.isDirectory) {
                dateStr
            } else {
                "${formatSize(file.length())} • $dateStr"
            }

            itemView.isActivated = isSelected(file)

            itemView.setOnClickListener { onFileClick(file) }
            itemView.setOnLongClickListener {
                onFileLongClick(file)
                true
            }
        }
    }

    object FileDiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.absolutePath == newItem.absolutePath
        }

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.lastModified() == newItem.lastModified() &&
                    oldItem.length() == newItem.length() &&
                    oldItem.name == newItem.name
        }
    }
}
