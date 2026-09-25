package com.cris.doamodgallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import coil.size.Precision
import com.cris.doamodgallery.R
import com.cris.doamodgallery.data.ModItem
import com.cris.doamodgallery.databinding.ItemModBinding

class ModAdapter(
    private val onOpen: (ModItem) -> Unit,
    private val onFavorite: (ModItem) -> Boolean
) : ListAdapter<ModItem, ModAdapter.Holder>(Diff) {
    private var favorites: Set<String> = emptySet()
    private var columns: Int = 3

    init {
        setHasStableIds(true)
    }

    fun setColumns(value: Int) {
        val newValue = value.coerceIn(2, 4)
        if (columns == newValue) return
        columns = newValue
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SIZE)
    }

    fun submit(newItems: List<ModItem>, favoriteIds: Set<String>) {
        val favoritesChanged = favoriteIds != favorites
        favorites = favoriteIds
        submitList(newItems)
        if (favoritesChanged && itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_FAVORITE)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemModBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val item = getItem(position)
        if (PAYLOAD_SIZE in payloads) holder.updateSize()
        if (PAYLOAD_FAVORITE in payloads) holder.updateFavorite(item)
    }

    inner class Holder(private val b: ItemModBinding) : RecyclerView.ViewHolder(b.root) {
        private var bound: ModItem? = null

        fun bind(item: ModItem) {
            bound = item
            b.title.text = item.title
            b.character.text = item.character
            updateFavorite(item)
            updateSize()

            val width = targetCardWidth()
            val height = targetImageHeight(width)

            // Grid uses the lightweight thumbnail. Full HD is reserved for DetailActivity.
            // This avoids downloading/decoding dozens of multi-megabyte originals while scrolling.
            b.image.load(item.previewUrl.ifBlank { item.hdUrl }) {
                crossfade(false)
                precision(Precision.INEXACT)
                size(width.coerceAtLeast(1), height.coerceAtLeast(1))
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                networkCachePolicy(CachePolicy.ENABLED)
                placeholder(R.color.surface_2)
                error(R.color.surface_2)
            }

            b.root.setOnClickListener { onOpen(item) }
            b.favorite.setOnClickListener {
                val yes = onFavorite(item)
                favorites = if (yes) favorites + item.id else favorites - item.id
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos, PAYLOAD_FAVORITE)
            }
        }

        fun updateFavorite(item: ModItem? = bound) {
            val value = item ?: return
            val yes = value.id in favorites
            b.favorite.text = if (yes) "★" else "☆"
            b.favorite.setTextColor(b.root.context.getColor(if (yes) R.color.blue else R.color.text_secondary))
        }

        fun updateSize() {
            val width = targetCardWidth()
            val height = targetImageHeight(width)
            if (b.image.layoutParams.height != height) {
                b.image.layoutParams = b.image.layoutParams.apply { this.height = height }
            }
        }

        private fun targetCardWidth(): Int {
            val dm = b.root.resources.displayMetrics
            return ((dm.widthPixels - (16 * dm.density).toInt()) / columns.coerceAtLeast(1))
        }

        private fun targetImageHeight(width: Int): Int {
            val dm = b.root.resources.displayMetrics
            return (width * 1.22f).toInt()
                .coerceAtLeast((108 * dm.density).toInt())
                .coerceAtMost((205 * dm.density).toInt())
        }
    }

    private object Diff : DiffUtil.ItemCallback<ModItem>() {
        override fun areItemsTheSame(oldItem: ModItem, newItem: ModItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ModItem, newItem: ModItem): Boolean = oldItem == newItem
    }

    companion object {
        private const val PAYLOAD_FAVORITE = "favorite"
        private const val PAYLOAD_SIZE = "size"
    }
}
