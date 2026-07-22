package com.multivpn.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.multivpn.app.databinding.ItemCountryBinding
import com.multivpn.app.databinding.ItemServerBinding
import java.util.Locale

/** Flat row model: countries with their servers inlined when expanded. */
sealed class Row {
    data class CountryRow(val country: Country, val expanded: Boolean) : Row()
    data class ServerRow(val server: VpnServer) : Row()
}

class ServerListAdapter(
    private val onToggleCountry: (Country) -> Unit,
    private val onConnectBest: (Country) -> Unit,
    private val onConnectServer: (VpnServer) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_COUNTRY = 0
        private const val TYPE_SERVER = 1

        fun flagEmoji(countryCode: String): String {
            if (countryCode.length != 2) return "🌐" // globe
            val cc = countryCode.uppercase(Locale.US)
            if (!cc[0].isLetter() || !cc[1].isLetter()) return "🌐"
            val first = 0x1F1E6 + (cc[0].code - 'A'.code)
            val second = 0x1F1E6 + (cc[1].code - 'A'.code)
            return String(Character.toChars(first)) + String(Character.toChars(second))
        }
    }

    private val rows = ArrayList<Row>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.CountryRow -> TYPE_COUNTRY
        is Row.ServerRow -> TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_COUNTRY) {
            CountryViewHolder(ItemCountryBinding.inflate(inflater, parent, false))
        } else {
            ServerViewHolder(ItemServerBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.CountryRow -> (holder as CountryViewHolder).bind(row)
            is Row.ServerRow -> (holder as ServerViewHolder).bind(row.server)
        }
    }

    inner class CountryViewHolder(private val binding: ItemCountryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.CountryRow) {
            val context = binding.root.context
            binding.textFlag.text = flagEmoji(row.country.code)
            binding.textCountry.text = row.country.name
            binding.textCount.text = context.resources.getQuantityString(
                R.plurals.server_count, row.country.servers.size, row.country.servers.size
            )
            binding.textChevron.text = if (row.expanded) "▲" else "▼"
            binding.root.setOnClickListener { onToggleCountry(row.country) }
            binding.btnBest.setOnClickListener { onConnectBest(row.country) }
        }
    }

    inner class ServerViewHolder(private val binding: ItemServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(server: VpnServer) {
            val context = binding.root.context
            binding.textHost.text = server.hostName
            val ping = if (server.pingMs >= 0) "${server.pingMs} ms" else "–"
            binding.textDetails.text = context.getString(
                R.string.server_details,
                ping,
                String.format(Locale.US, "%.1f", server.speedMbps),
                server.sessions
            )
            binding.root.setOnClickListener { onConnectServer(server) }
        }
    }
}
