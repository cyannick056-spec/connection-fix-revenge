package com.cris.doamodgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cris.doamodgallery.R
import com.cris.doamodgallery.data.ModItem
import com.cris.doamodgallery.databinding.ItemModBinding

class ModAdapter(
    private val onOpen: (ModItem) -> Unit,
    private val onFavorite: (ModItem) -> Boolean
) : RecyclerView.Adapter<ModAdapter.Holder>() {
    private var items: List<ModItem> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var columns: Int = 3
    fun setColumns(value: Int) { columns = value.coerceIn(2, 4); notifyDataSetChanged() }
    fun submit(newItems: List<ModItem>, favoriteIds: Set<String>) { items = newItems; favorites = favoriteIds; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(ItemModBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemModBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ModItem) {
            b.title.text = item.title
            b.character.text = item.character
            b.favorite.text = if (item.id in favorites) "★" else "☆"
            b.favorite.setTextColor(b.root.context.getColor(if (item.id in favorites) R.color.blue else R.color.text_secondary))
            val dm = b.root.resources.displayMetrics
            val cardWidthPx = ((dm.widthPixels - (16 * dm.density).toInt()) / columns.coerceAtLeast(1))
            val targetHeightPx = (cardWidthPx * 1.22f).toInt().coerceAtLeast((108 * dm.density).toInt()).coerceAtMost((205 * dm.density).toInt())
            b.image.layoutParams = b.image.layoutParams.apply { height = targetHeightPx }
            b.image.load(item.hdUrl.ifBlank { item.previewUrl }) { crossfade(true); placeholder(R.color.surface_2); error(R.color.surface_2) }
            b.root.setOnClickListener { onOpen(item) }
            b.favorite.setOnClickListener {
                val yes = onFavorite(item)
                favorites = if (yes) favorites + item.id else favorites - item.id
                notifyItemChanged(bindingAdapterPosition)
            }
        }
    }
}
