package com.multivpn.app

import android.content.ActivityNotFoundException
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.multivpn.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), VpnEngine.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: VpnEngine
    private lateinit var adapter: ServerListAdapter

    private var allServers: List<VpnServer> = emptyList()
    private val expandedCountries = HashSet<String>()
    private var filterQuery = ""

    /** Config waiting for the VPN consent dialog. */
    private var pendingConfig: String? = null
    private var pendingLabel: String? = null
    private var currentLabel: String? = null

    private val cacheFile: File get() = File(cacheDir, "vpngate.csv")

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startPendingConnection()
            } else {
                pendingConfig = null
                showDisconnected()
                toast(getString(R.string.err_vpn_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = VpnEngine(applicationContext)
        engine.listener = this

        adapter = ServerListAdapter(
            onToggleCountry = { country ->
                if (!expandedCountries.add(country.code)) expandedCountries.remove(country.code)
                rebuildRows()
            },
            onConnectBest = { country -> country.servers.firstOrNull()?.let { confirmConnect(it) } },
            onConnectServer = { server -> confirmConnect(server) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { refreshServers() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString() ?: ""
                rebuildRows()
            }
        })

        binding.btnDisconnect.setOnClickListener {
            pendingConfig = null
            engine.disconnect()
        }

        showDisconnected()
        loadCacheThenRefresh()
    }

    override fun onResume() {
        super.onResume()
        engine.attach()
    }

    override fun onPause() {
        super.onPause()
        engine.detach()
    }

    // ---------------------------------------------------------------- servers

    private fun loadCacheThenRefresh() {
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                try {
                    if (cacheFile.exists()) VpnGateApi.parse(cacheFile.readText()) else null
                } catch (e: Exception) {
                    null
                }
            }
            if (!cached.isNullOrEmpty()) {
                allServers = cached
                rebuildRows()
                binding.textUpdated.text = getString(R.string.showing_cached, cached.size)
            }
            refreshServers()
        }
    }

    private fun refreshServers() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val raw = VpnGateApi.fetchRaw()
                    val list = VpnGateApi.parse(raw)
                    if (list.isNotEmpty()) {
                        try {
                            cacheFile.writeText(raw)
                        } catch (ignored: Exception) {
                        }
                    }
                    list
                }
                if (parsed.isNotEmpty()) {
                    allServers = parsed
                    rebuildRows()
                }
                val countryCount = parsed.map { it.countryCode }.toSet().size
                binding.textUpdated.text =
                    getString(R.string.servers_loaded, parsed.size, countryCount)
            } catch (e: Exception) {
                if (allServers.isEmpty()) {
                    binding.textEmpty.text = getString(R.string.err_fetch_empty)
                    binding.textEmpty.visibility = View.VISIBLE
                }
                toast(getString(R.string.err_fetch))
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun rebuildRows() {
        val query = filterQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) allServers else allServers.filter {
            it.countryName.lowercase().contains(query) ||
                it.countryCode.lowercase().contains(query) ||
                it.hostName.lowercase().contains(query)
        }
        val countries = VpnGateApi.groupByCountry(filtered)
        val rows = ArrayList<Row>()
        for (country in countries) {
            val expanded = query.isNotEmpty() || expandedCountries.contains(country.code)
            rows.add(Row.CountryRow(country, expanded))
            if (expanded) country.servers.forEach { rows.add(Row.ServerRow(it)) }
        }
        adapter.submit(rows)
        if (rows.isEmpty()) {
            binding.textEmpty.text = getString(R.string.no_servers)
            binding.textEmpty.visibility = View.VISIBLE
        } else {
            binding.textEmpty.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------- connecting

    private fun confirmConnect(server: VpnServer) {
        val label = "${ServerListAdapter.flagEmoji(server.countryCode)} ${server.countryName} — ${server.hostName}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connect_title)
            .setMessage(getString(R.string.connect_message, label))
            .setPositiveButton(R.string.connect) { _, _ -> connectTo(server) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectTo(server: VpnServer) {
        val config = try {
            server.decodeConfig()
        } catch (e: Exception) {
            toast(getString(R.string.err_bad_config))
            return
        }
        pendingConfig = config
        pendingLabel = "${ServerListAdapter.flagEmoji(server.countryCode)} ${server.countryName} — ${server.hostName}"
        showConnecting(getString(R.string.status_preparing))

        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            try {
                vpnPermissionLauncher.launch(consentIntent)
            } catch (e: ActivityNotFoundException) {
                pendingConfig = null
                showDisconnected()
                toast(getString(R.string.err_no_vpn_support))
            }
        } else {
            startPendingConnection()
        }
    }

    private fun startPendingConnection() {
        val config = pendingConfig ?: return
        try {
            engine.start(config, pendingLabel ?: getString(R.string.app_name))
            currentLabel = pendingLabel
            pendingConfig = null
        } catch (e: Exception) {
            pendingConfig = null
            showDisconnected()
            toast(getString(R.string.err_bad_config))
        }
    }

    // ---------------------------------------------------- VpnEngine.Listener

    override fun onVpnStatus(state: String, message: String) {
        when (state) {
            "CONNECTED" -> showConnected()
            "NOPROCESS", "EXITING" -> showDisconnected()
            "AUTH_FAILED" -> {
                toast(getString(R.string.err_auth_failed))
                showDisconnected()
            }
            "NONETWORK" -> showConnecting(getString(R.string.state_nonetwork))
            "RECONNECTING" -> showConnecting(getString(R.string.state_reconnecting))
            "RESOLVE" -> showConnecting(getString(R.string.state_resolve))
            "TCP_CONNECT", "WAIT", "CONNECTING" -> showConnecting(getString(R.string.state_connecting))
            "AUTH" -> showConnecting(getString(R.string.state_auth))
            "GET_CONFIG" -> showConnecting(getString(R.string.state_get_config))
            "ASSIGN_IP" -> showConnecting(getString(R.string.state_assign_ip))
            "ADD_ROUTES" -> showConnecting(getString(R.string.state_add_routes))
            "VPN_GENERATE_CONFIG" -> showConnecting(getString(R.string.state_generate))
            "USER_VPN_PERMISSION", "USER_VPN_PASSWORD" -> Unit
            else -> if (state.isNotBlank()) showConnecting(state)
        }
    }

    // ------------------------------------------------------------------- UI

    private fun showDisconnected() {
        currentLabel = null
        binding.textStatus.text = getString(R.string.status_disconnected)
        binding.textStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        binding.textStatusDetail.text = getString(R.string.status_pick_server)
        binding.btnDisconnect.visibility = View.GONE
    }

    private fun showConnecting(detail: String) {
        binding.textStatus.text = getString(R.string.status_connecting)
        binding.textStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
        val label = currentLabel ?: pendingLabel
        binding.textStatusDetail.text = if (label != null) "$label\n$detail" else detail
        binding.btnDisconnect.visibility = View.VISIBLE
    }

    private fun showConnected() {
        binding.textStatus.text = getString(R.string.status_connected)
        binding.textStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        binding.textStatusDetail.text =
            currentLabel ?: pendingLabel ?: getString(R.string.status_connected)
        binding.btnDisconnect.visibility = View.VISIBLE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
