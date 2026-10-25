package pl.mczark.invoicescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.mczark.invoicescanner.background.InvoiceNotifier
import pl.mczark.invoicescanner.ui.InvoiceDetailScreen
import pl.mczark.invoicescanner.ui.InvoiceListScreen
import pl.mczark.invoicescanner.ui.theme.InvoiceScannerTheme

class MainActivity : ComponentActivity() {

    /** Faktura do otwarcia po kliknięciu powiadomienia. */
    private val invoiceToOpen = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) invoiceToOpen.value = intent.getStringExtra(InvoiceNotifier.EXTRA_MESSAGE_ID)
        setContent {
            InvoiceScannerTheme {
                InvoiceApp(invoiceToOpen, onInvoiceOpened = { invoiceToOpen.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(InvoiceNotifier.EXTRA_MESSAGE_ID)?.let { invoiceToOpen.value = it }
    }
}

@Composable
private fun InvoiceApp(
    invoiceToOpen: StateFlow<String?>,
    onInvoiceOpened: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val context = LocalContext.current

    // Android 13+: zgoda na powiadomienia o nowych fakturach, gdy konto jest już połączone.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val connected = state.accountEmail != null
    LaunchedEffect(connected, state.dailyCheckEnabled) {
        if (connected && state.dailyCheckEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val pendingInvoice by invoiceToOpen.collectAsStateWithLifecycle()
    LaunchedEffect(pendingInvoice) {
        pendingInvoice?.let { id ->
            navController.navigate("invoice/$id") { launchSingleTop = true }
            onInvoiceOpened()
        }
    }

    // Ekran wyboru konta Google / zgody na odczyt Gmaila.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onConsentResult(result.resultCode, result.data) }

    LaunchedEffect(Unit) {
        viewModel.consentIntents.collect { pendingIntent ->
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    NavHost(navController, startDestination = "list") {
        composable("list") {
            InvoiceListScreen(
                state = state,
                onScan = viewModel::scan,
                onOpen = { navController.navigate("invoice/${it.messageId}") },
                onClearAll = { viewModel.clearAll() },
                onMessageShown = viewModel::messageShown,
                onDailyCheckChange = viewModel::setDailyCheckEnabled,
            )
        }
        composable("invoice/{id}") { entry ->
            val id = entry.arguments?.getString("id")
            val invoice = state.invoices.firstOrNull { it.messageId == id }
            if (invoice == null) {
                // Faktura usunięta albo dane jeszcze się ładują.
                LaunchedEffect(state.invoices) {
                    if (state.invoices.isNotEmpty() && navController.currentBackStackEntry == entry) {
                        navController.popBackStack()
                    }
                }
                return@composable
            }
            InvoiceDetailScreen(
                invoice = invoice,
                onBack = { navController.popBackStack() },
                onSetStatus = { viewModel.setStatus(invoice, it) },
                onSave = { viewModel.save(it) },
                onDelete = {
                    viewModel.delete(invoice)
                    navController.popBackStack()
                },
            )
        }
    }
}
