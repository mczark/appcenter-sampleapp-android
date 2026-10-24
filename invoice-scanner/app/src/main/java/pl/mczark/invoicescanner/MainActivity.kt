package pl.mczark.invoicescanner

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pl.mczark.invoicescanner.ui.InvoiceDetailScreen
import pl.mczark.invoicescanner.ui.InvoiceListScreen
import pl.mczark.invoicescanner.ui.theme.InvoiceScannerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InvoiceScannerTheme {
                InvoiceApp()
            }
        }
    }
}

@Composable
private fun InvoiceApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

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
