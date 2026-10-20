package com.vpnapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vpnapp.data.UsageDay
import com.vpnapp.data.UsageHistory
import com.vpnapp.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageHistoryScreen(onBack: () -> Unit) {
    val context=LocalContext.current
    val store=remember { UsageHistory(context) }
    DisposableEffect(store) { onDispose { store.close() } }
    var period by remember { mutableStateOf("7 days") }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var to by remember { mutableStateOf(LocalDate.now().toString()) }
    var weekly by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(emptyList<UsageDay>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(period,from,to) {
        while(true) {
            runCatching {
                val today=LocalDate.now()
                val start=when(period) { "Today"->today;"Yesterday"->today.minusDays(1);"7 days"->today.minusDays(6)
                    "30 days"->LocalDate.now().minusDays(29);else->LocalDate.parse(from) }
                val end=when(period) { "Custom"->LocalDate.parse(to);"Yesterday"->today.minusDays(1);else->today }
                require(start<=end) { "Start date must be before the end date" }
                withContext(Dispatchers.IO) { store.days(start,end) }
            }.onSuccess { days=it;error=null }.onFailure { days=emptyList();error="Use YYYY-MM-DD with a valid start/end date" }
            kotlinx.coroutines.delay(5000)
        }
    }
    val rows=if(weekly) days.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .map { (date,items)->UsageDay(date,items.sumOf { it.downloaded },items.sumOf { it.uploaded }) }
        .sortedByDescending { it.date } else days
    Scaffold(containerColor=CurrentBackground,topBar={ TopAppBar(title={ Text("History of usage") },
        navigationIcon={ IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,"Back") } },
        colors=TopAppBarDefaults.topAppBarColors(containerColor=CurrentBackground)) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal=16.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Today","Yesterday","7 days","30 days","Custom").forEach { value ->
                    FilterChip(selected=period==value,onClick={ period=value },label={ Text(value,style=MaterialTheme.typography.labelSmall) })
                }
            }
            if(period=="Custom") {
                OutlinedTextField(value=from,onValueChange={ from=it },label={ Text("From · YYYY-MM-DD") },singleLine=true)
                OutlinedTextField(value=to,onValueChange={ to=it },label={ Text("To · YYYY-MM-DD") },singleLine=true)
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Card(colors=CardDefaults.cardColors(containerColor=CurrentCard),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Total: ${formatBytes(days.sumOf { it.downloaded+it.uploaded })}",style=MaterialTheme.typography.titleLarge)
                    Text("↓ ${formatBytes(days.sumOf { it.downloaded })} downloaded")
                    Text("↑ ${formatBytes(days.sumOf { it.uploaded })} uploaded")
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Group by week");Switch(checked=weekly,onCheckedChange={ weekly=it })
            }
            if(rows.isEmpty()) Text("No recorded VPN usage in this period")
            rows.forEach { row ->
                Card(colors=CardDefaults.cardColors(containerColor=CurrentCard),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if(weekly) "Week of ${row.date}" else row.date.toString())
                        Text("↓ ${formatBytes(row.downloaded)}    ↑ ${formatBytes(row.uploaded)}",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("VPN traffic is recorded locally from this version onward, including when the app is closed. Dates follow the phone's timezone. Carrier billing may include different overhead.",
                style=MaterialTheme.typography.bodySmall,color=CurrentTextSecondary)
            Spacer(Modifier.height(12.dp))
        }
    }
}
