package com.example.truenasapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.truenasapp.Graph
import com.example.truenasapp.data.NasAlert
import com.example.truenasapp.data.Pool
import com.example.truenasapp.data.Realtime
import com.example.truenasapp.data.SystemInfo
import com.example.truenasapp.data.friendlyError
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val api = Graph.api
    var info by remember { mutableStateOf<SystemInfo?>(null) }
    var pools by remember { mutableStateOf<List<Pool>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<NasAlert>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // Live chart data (rolling history of the last ~2 minutes).
    var latest by remember { mutableStateOf(Realtime()) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val rxHistory = remember { mutableStateListOf<Float>() }
    val txHistory = remember { mutableStateListOf<Float>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val perf by Graph.perf.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Only stream while the screen is visible.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var count = 0
            api.realtimeUpdates().collect { r ->
                latest = r
                // In battery saver only every 2nd update is drawn.
                if (count++ % perf.chartSampleEvery != 0) return@collect
                val cap = perf.chartPoints
                cpuHistory.pushPoint(r.cpuPercent, cap)
                rxHistory.pushPoint(r.netRxBytesPerSec?.let { it * 8 / 1e6 }, cap) // Mb/s
                txHistory.pushPoint(r.netTxBytesPerSec?.let { it * 8 / 1e6 }, cap)
            }
        }
    }

    LaunchedEffect(reloadKey) {
        refreshing = true
        try {
            info = api.systemInfo()
            pools = api.pools()
            alerts = api.alerts()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        refreshing = false
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reloadKey++ }, modifier = Modifier.fillMaxSize()) {
        // One column on phones, several on tablets / desktop, two either side of a foldable's hinge.
        LazyVerticalGrid(
            columns = adaptiveGridCells(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(gridGap()),
            modifier = Modifier.fillMaxSize(),
        ) {
            val full: (LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
            item(span = full) { ScreenTitle(info?.hostname?.ifBlank { null } ?: "My NAS") }
            val err = error
            if (err != null) item(span = full) { ErrorCard(err) { reloadKey++ } }
            info?.let { i -> item { DeviceCard(i) } }
            item { CpuCard(latest, cpuHistory, perf.chartPoints, perf.chartSampleEvery, perf.chartFill) }
            item { MemoryCard(latest) }
            item { NetworkCard(latest, rxHistory, txHistory, perf.chartPoints, perf.chartSampleEvery, perf.chartFill) }
            item(span = full) { SectionTitle("Storage") }
            items(pools, key = { it.name }) { PoolCard(it) }
            if (alerts.isNotEmpty()) {
                item(span = full) { SectionTitle("Alerts") }
                items(alerts) { AlertCard(it) }
            }
            item(span = full) { ExpertButton(Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun DeviceCard(info: SystemInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.model.ifBlank { "TrueNAS" }, fontWeight = FontWeight.SemiBold)
            Text(info.version, style = MaterialTheme.typography.bodySmall)
            Text(
                "Up ${formatUptime(info.uptimeSeconds)} · ${info.cores} cores · ${formatBytes(info.memoryBytes)} RAM",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, value: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable
private fun CpuCard(rt: Realtime, history: List<Float>, capacity: Int, sampleEvery: Int, fill: Boolean) {
    ChartCard(
        title = "CPU",
        value = rt.cpuPercent?.let { "%.0f%%".format(it) } ?: "—",
        subtitle = rt.cpuTempC?.let { "Temperature %.0f°C".format(it) },
    ) {
        LineChart(
            listOf(ChartSeries(history, MaterialTheme.colorScheme.primary)),
            maxValue = 100f,
            capacity = capacity,
            fill = fill,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Text(historyLabel(capacity, sampleEvery), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MemoryCard(rt: Realtime) {
    val total = rt.memTotal
    val avail = rt.memAvailable
    val arc = rt.arcSize ?: 0.0
    // TrueNAS shows memory as: services, ZFS cache (ARC) and free.
    var services = 0.0
    var free = 0.0
    if (total != null && avail != null) {
        val usedNotCache = total - avail - arc
        if (usedNotCache >= 0) {
            services = usedNotCache; free = avail
        } else { // this TrueNAS version counts the cache as available
            services = total - avail; free = (avail - arc).coerceAtLeast(0.0)
        }
    }
    val servicesColor = MaterialTheme.colorScheme.primary
    val cacheColor = TrueNasGreen
    val freeColor = MaterialTheme.colorScheme.outline

    ChartCard(
        title = "Memory",
        value = total?.let { formatBytes(it.toLong()) } ?: "—",
        subtitle = null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                DonutChart(
                    listOf(services.toFloat() to servicesColor, arc.toFloat() to cacheColor, free.toFloat() to freeColor),
                    Modifier.fillMaxSize(),
                )
                Text(rt.memPercent?.let { "%.0f%%".format(it) } ?: "—", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendDot(servicesColor, "Services  ${formatBytes(services.toLong())}")
                LegendDot(cacheColor, "ZFS cache  ${formatBytes(arc.toLong())}")
                LegendDot(freeColor, "Free  ${formatBytes(free.toLong())}")
            }
        }
    }
}

@Composable
private fun NetworkCard(rt: Realtime, rx: List<Float>, tx: List<Float>, capacity: Int, sampleEvery: Int, fill: Boolean) {
    val inColor = MaterialTheme.colorScheme.primary
    val outColor = TrueNasGreen
    ChartCard(title = "Network", value = "", subtitle = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(inColor, "In  ${formatRate(rt.netRxBytesPerSec)}")
            LegendDot(outColor, "Out  ${formatRate(rt.netTxBytesPerSec)}")
        }
        LineChart(
            listOf(ChartSeries(rx, inColor), ChartSeries(tx, outColor)),
            capacity = capacity,
            fill = fill,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Text("${historyLabel(capacity, sampleEvery)} · all network ports", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PoolCard(pool: Pool) {
    val fraction = if (pool.size > 0) pool.allocated.toFloat() / pool.size else 0f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(pool.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val healthy = pool.healthy && pool.status == "ONLINE"
                AssistChip(
                    onClick = {},
                    label = { Text(if (healthy) "Healthy" else pool.status.ifBlank { "Problem" }) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (healthy) TrueNasGreen else MaterialTheme.colorScheme.error,
                    ),
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                color = if (fraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (pool.size > 0) {
                Text(
                    "${formatBytes(pool.allocated)} used of ${formatBytes(pool.size)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AlertCard(alert: NasAlert) {
    val color = when (alert.level) {
        "CRITICAL", "ALERT", "EMERGENCY", "ERROR" -> MaterialTheme.colorScheme.error
        "WARNING" -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color)
            Spacer(Modifier.size(12.dp))
            Text(alert.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
