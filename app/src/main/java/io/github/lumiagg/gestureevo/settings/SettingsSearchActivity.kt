package io.github.lumiagg.gestureevo.settings

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.transition.Fade
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySettingsSearchBinding

class SettingsSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSearchBinding
    private val resultAdapter = SearchResultAdapter(::openSearchResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSharedElementTransition()
        setupRecyclerView()
        setupSearchInput()
        renderResults("")
        startEntranceAnimations()
    }

    private fun setupSharedElementTransition() {
        window.sharedElementEnterTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.sharedElementReturnTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.enterTransition = Fade().apply { duration = 150L }
        window.returnTransition = Fade().apply { duration = 150L }
    }

    private fun setupRecyclerView() {
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = resultAdapter
    }

    private fun setupSearchInput() {
        binding.searchBackButton.setOnClickListener {
            finishAfterTransition()
        }
        binding.searchClearButton.setOnClickListener {
            binding.searchInput.text?.clear()
        }
        binding.searchInput.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            binding.searchClearButton.isVisible = query.isNotBlank()
            renderResults(query)
        }
    }

    private fun renderResults(query: String) {
        val entries = SettingsSearchRegistry.search(this, query)
        resultAdapter.submitList(entries)
        binding.resultsRecyclerView.isVisible = entries.isNotEmpty()
        binding.emptyStateLayout.isVisible = entries.isEmpty()
    }

    private fun startEntranceAnimations() {
        binding.searchSurface.alpha = 0f
        binding.searchSurface.scaleX = 0.98f
        binding.searchSurface.scaleY = 0.98f
        binding.resultsRecyclerView.alpha = 0f
        binding.resultsRecyclerView.scaleX = 0.97f
        binding.resultsRecyclerView.scaleY = 0.97f
        binding.emptyStateLayout.alpha = 0f
        binding.emptyStateLayout.scaleX = 0.97f
        binding.emptyStateLayout.scaleY = 0.97f
        postponeEnterTransition()
        binding.root.doOnPreDraw { startPostponedEnterTransition() }
        binding.root.postDelayed({
            binding.searchSurface.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .start()
            binding.resultsRecyclerView.animate().alpha(1f).setDuration(180L).start()
            binding.resultsRecyclerView.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
            binding.emptyStateLayout.animate().alpha(1f).setDuration(180L).start()
            binding.emptyStateLayout.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
            focusSearchInput()
        }, 140L)
    }

    private fun focusSearchInput() {
        binding.searchInput.requestFocus()
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun openSearchResult(entry: SettingsSearchEntry) {
        startActivity(SettingsSearchNavigator.buildIntent(this, entry))
        finishAfterTransition()
    }

    private class SearchResultAdapter(
        private val onClick: (SettingsSearchEntry) -> Unit
    ) : RecyclerView.Adapter<SearchResultAdapter.SearchResultViewHolder>() {
        private val items = mutableListOf<SettingsSearchEntry>()

        fun submitList(entries: List<SettingsSearchEntry>) {
            val previous = items.toList()
            val next = entries.toList()
            val changes = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size
                    override fun getNewListSize() = next.size
                    override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                        previous[oldPosition].id == next[newPosition].id
                    override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                        previous[oldPosition] == next[newPosition]
                }
            )
            items.clear()
            items.addAll(next)
            changes.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_search_result, parent, false)
            return SearchResultViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class SearchResultViewHolder(
            itemView: View,
            private val onClick: (SettingsSearchEntry) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val pageTextView: TextView = itemView.findViewById(R.id.resultPageText)
            private val titleTextView: TextView = itemView.findViewById(R.id.resultTitleText)
            private val summaryTextView: TextView = itemView.findViewById(R.id.resultSummaryText)

            fun bind(entry: SettingsSearchEntry) {
                val context = itemView.context
                pageTextView.text = context.getString(entry.pageTitleResId)
                titleTextView.text = context.getString(entry.titleResId)
                val summary = entry.summaryResId?.let(context::getString).orEmpty()
                summaryTextView.isVisible = summary.isNotBlank()
                summaryTextView.text = summary
                itemView.setOnClickListener {
                    onClick(entry)
                }
            }
        }
    }
}
