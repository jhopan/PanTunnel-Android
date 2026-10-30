package id.jhopanstore.pantunnel

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import id.jhopanstore.pantunnel.databinding.ItemProfileCardBinding
import id.jhopanstore.pantunnel.core.Profile

/** Card list: one row per profile plus a trailing "+ Tambah profil" row. */
class ProfileAdapter(
    private val onSelect: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit,
    private val onAdd: () -> Unit,
) : ListAdapter<ProfileAdapter.Row, ProfileAdapter.Holder>(DIFF) {

    /** A row is either a real profile or the "add" affordance. */
    sealed interface Row {
        data class Item(val profile: Profile, val isActive: Boolean, val canDelete: Boolean) : Row
        data object Add : Row
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemProfileCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemProfileCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            when (row) {
                is Row.Add -> bindAdd()
                is Row.Item -> bindItem(row)
            }
        }

        private fun bindAdd() {
            val ctx = binding.root.context
            binding.cardMarker.setBackgroundColor(
                ContextCompat.getColor(ctx, R.color.divider),
            )
            binding.cardName.text = ctx.getString(R.string.add_profile)
            binding.cardName.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            binding.cardAddress.text = ""
            binding.cardEdit.visibility = android.view.View.GONE
            binding.cardDelete.visibility = android.view.View.GONE
            binding.root.setOnClickListener { onAdd() }
        }

        private fun bindItem(row: Row.Item) {
            val ctx = binding.root.context
            val profile = row.profile

            binding.cardMarker.setBackgroundColor(
                ContextCompat.getColor(ctx, if (row.isActive) R.color.accent else R.color.divider),
            )
            binding.cardName.text = profile.displayName
            binding.cardName.setTextColor(
                ContextCompat.getColor(ctx, if (row.isActive) R.color.accent_cyan else R.color.text_primary),
            )
            binding.cardAddress.text = if (profile.hasConfig) {
                "${profile.address} • ${profile.path.ifBlank { "/" }}"
            } else {
                "Belum ada config"
            }

            binding.cardEdit.visibility = android.view.View.VISIBLE
            binding.cardDelete.visibility = if (row.canDelete) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.setOnClickListener { onSelect(profile) }
            binding.cardEdit.setOnClickListener { onEdit(profile) }
            binding.cardDelete.setOnClickListener { onDelete(profile) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Add && newItem is Row.Add -> true
                oldItem is Row.Item && newItem is Row.Item -> oldItem.profile.id == newItem.profile.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
        }
    }
}
