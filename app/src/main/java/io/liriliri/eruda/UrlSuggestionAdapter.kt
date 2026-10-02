package io.liriliri.eruda

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import io.liriliri.eruda.data.HistoryItem

class UrlSuggestionAdapter(context: Context) :
    ArrayAdapter<HistoryItem>(context, android.R.layout.simple_list_item_1) {

    private val suggestions = mutableListOf<HistoryItem>()
    private var allItems = listOf<HistoryItem>()

    fun setData(items: List<HistoryItem>) {
        allItems = items
    }

    override fun getCount(): Int = suggestions.size

    override fun getItem(position: Int): HistoryItem = suggestions[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        val item = getItem(position)
        view.findViewById<TextView>(android.R.id.text1).text = "${item.title}\n${item.url}"
        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val query = constraint?.toString()?.lowercase() ?: ""
                suggestions.clear()
                if (query.isEmpty()) {
                    suggestions.addAll(allItems.take(5))
                } else {
                    suggestions.addAll(allItems.filter {
                        it.url.lowercase().contains(query) ||
                            it.title.lowercase().contains(query)
                    }.take(10))
                }
                results.values = suggestions
                results.count = suggestions.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (results != null) {
                    suggestions.clear()
                    suggestions.addAll(results.values as List<HistoryItem>)
                    notifyDataSetChanged()
                }
            }
        }
    }
}