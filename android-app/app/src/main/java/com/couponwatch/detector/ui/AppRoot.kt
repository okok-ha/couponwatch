package com.couponwatch.detector.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couponwatch.detector.ui.screen.FiltersScreen
import com.couponwatch.detector.ui.screen.FormatScreen
import com.couponwatch.detector.ui.screen.HomeScreen
import com.couponwatch.detector.ui.screen.LogsScreen
import com.couponwatch.detector.ui.screen.SelfCheckScreen

private data class TabItem(val index: Int, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(MainActivity.TAB_HOME, "首页", Icons.Filled.Home),
    TabItem(MainActivity.TAB_LOGS, "日志", Icons.Filled.List),
    TabItem(MainActivity.TAB_FORMAT, "格式", Icons.Filled.Edit),
    TabItem(MainActivity.TAB_FILTERS, "过滤", Icons.Filled.Settings),
    TabItem(MainActivity.TAB_SELF_CHECK, "自检", Icons.Filled.Info),
)

@Composable
fun CouponWatchRoot(initialTab: Int) {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }

    val message by vm.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHost.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item.index,
                        onClick = { tab = item.index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                MainActivity.TAB_HOME -> HomeScreen(
                    vm = vm,
                    onOpenMockPage = {
                        context.startActivity(Intent(context, MockTestActivity::class.java))
                    },
                    onOpenLogs = { tab = MainActivity.TAB_LOGS },
                )

                MainActivity.TAB_LOGS -> LogsScreen(vm = vm)
                MainActivity.TAB_FORMAT -> FormatScreen(vm = vm)
                MainActivity.TAB_FILTERS -> FiltersScreen(vm = vm)
                else -> SelfCheckScreen(vm = vm)
            }
        }
    }
}
