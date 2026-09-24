package dev.pixelchutney.tally.capture.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.ui.theme.TallyTheme

/**
 * The capture sheet lives in its own translucent activity so it can be opened
 * straight from a notification without loading the rest of the app first.
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0 }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val amountPaise = intent.getLongExtra(EXTRA_AMOUNT, -1L).takeIf { it > 0 }
        val merchant = intent.getStringExtra(EXTRA_MERCHANT)
        val editingId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0 }

        setContent {
            TallyTheme {
                val viewModel: CaptureViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    viewModel.start(sessionId, packageName, amountPaise, merchant, editingId)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Tapping the dimmed area above the sheet closes it.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clickable(
                                    interactionSource = MutableInteractionSource(),
                                    indication = null,
                                ) { finish() }
                        )
                        CaptureSheet(
                            state = state,
                            onDigit = viewModel::digit,
                            onDecimal = viewModel::decimal,
                            onBackspace = viewModel::backspace,
                            onCategory = viewModel::selectCategory,
                            onNecessity = viewModel::setNecessity,
                            onMerchant = viewModel::setMerchant,
                            onSuggestion = viewModel::applySuggestion,
                            onNote = viewModel::setNote,
                            onToggleNote = viewModel::toggleNote,
                            onSave = { viewModel.save { finish() } },
                            onNoPayment = { viewModel.dismissAsNoPayment { finish() } },
                            onDelete = { viewModel.deleteEditing { finish() } },
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_AMOUNT = "amount_paise"
        const val EXTRA_MERCHANT = "merchant"
        const val EXTRA_TRANSACTION_ID = "transaction_id"

        fun intent(
            context: Context,
            sessionId: Long? = null,
            packageName: String? = null,
            amountPaise: Long? = null,
            merchant: String? = null,
        ): Intent = Intent(context, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            packageName?.let { putExtra(EXTRA_PACKAGE, it) }
            amountPaise?.let { putExtra(EXTRA_AMOUNT, it) }
            merchant?.let { putExtra(EXTRA_MERCHANT, it) }
        }

        /** Opens the same sheet to correct an entry that already exists. */
        fun editIntent(context: Context, transactionId: Long): Intent =
            Intent(context, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
            }
    }
}
