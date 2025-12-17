package com.dom.samplenavigation.view.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dom.samplenavigation.R
import com.dom.samplenavigation.databinding.ItemRouteOptionBinding
import com.dom.samplenavigation.navigation.model.NavigationOptionRoute
import com.dom.samplenavigation.navigation.model.RouteOptionType
import java.util.Locale
import kotlin.math.roundToInt

class RouteOptionAdapter(
    private val onRouteSelected: (NavigationOptionRoute) -> Unit
) : ListAdapter<NavigationOptionRoute, RouteOptionAdapter.RouteOptionViewHolder>(DIFF) {

    private var selectedType: RouteOptionType? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteOptionViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemRouteOptionBinding.inflate(inflater, parent, false)
        return RouteOptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RouteOptionViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item = item,
            isSelected = item.optionType == selectedType,
            onClick = {
                // 항상 선택 상태 업데이트 (같은 경로인 경우에도 선택 가능하도록)
                updateSelectionInternal(item.optionType)
                onRouteSelected(item)
            }
        )
    }

    fun updateSelection(optionType: RouteOptionType?) {
        if (optionType == selectedType) return
        updateSelectionInternal(optionType)
    }

    private fun updateSelectionInternal(optionType: RouteOptionType?) {
        val previous = selectedType
        selectedType = optionType
        previous?.let { notifyChangedForType(it) }
        optionType?.let { notifyChangedForType(it) }
    }

    private fun notifyChangedForType(type: RouteOptionType) {
        val index = currentList.indexOfFirst { it.optionType == type }
        if (index != -1) notifyItemChanged(index)
    }

    class RouteOptionViewHolder(
        private val binding: ItemRouteOptionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: NavigationOptionRoute,
            isSelected: Boolean,
            onClick: () -> Unit
        ) = with(binding) {
            val context = root.context
            tvTitle.text = item.optionType.displayName
            tvSubtitle.text = item.optionType.description

            val durationMillis = item.route.summary.totalDuration
            tvTime.text = context.getString(
                R.string.route_option_eta_format,
                formatDuration(durationMillis)
            )

            val distanceKm = item.route.summary.totalDistance / 1000.0
            tvDistance.text = context.getString(
                R.string.route_option_distance_format,
                String.format(Locale.getDefault(), "%.1f", distanceKm)
            )

            val tollFare = item.route.summary.tollFare
            tvToll.text = if (tollFare > 0) {
                context.getString(
                    R.string.route_option_toll_format,
                    String.format(Locale.getDefault(), "%,d", tollFare)
                )
            } else {
                context.getString(R.string.route_option_toll_free)
            }

            val backgroundRes = if (isSelected) {
                R.drawable.bg_route_option_selected
            } else {
                R.drawable.bg_route_option_default
            }
            root.background = ContextCompat.getDrawable(context, backgroundRes)
            tvTitle.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) android.R.color.white else android.R.color.black
                )
            )
            tvSubtitle.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) android.R.color.white else android.R.color.darker_gray
                )
            )
            tvTime.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) android.R.color.white else android.R.color.black
                )
            )
            val secondaryColor = if (isSelected) android.R.color.white else android.R.color.darker_gray
            tvDistance.setTextColor(ContextCompat.getColor(context, secondaryColor))
            tvToll.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) android.R.color.white else android.R.color.holo_red_dark
                )
            )

            root.setOnClickListener { onClick() }
        }

        private fun formatDuration(durationMs: Int): String {
            val totalMinutes = (durationMs / 1000.0 / 60.0).roundToInt()
            if (totalMinutes <= 0) return "1분"
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
                hours > 0 -> "${hours}시간"
                else -> "${minutes}분"
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NavigationOptionRoute>() {
            override fun areItemsTheSame(
                oldItem: NavigationOptionRoute,
                newItem: NavigationOptionRoute
            ): Boolean = oldItem.optionType == newItem.optionType

            override fun areContentsTheSame(
                oldItem: NavigationOptionRoute,
                newItem: NavigationOptionRoute
            ): Boolean = oldItem == newItem
        }
    }
}

