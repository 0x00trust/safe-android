package pm.gnosis.heimdall.utils

import android.app.Activity
import android.content.Intent
import android.view.View
import pm.gnosis.heimdall.R
import pm.gnosis.heimdall.common.utils.ZxingIntentIntegrator
import pm.gnosis.heimdall.common.utils.snackbar
import pm.gnosis.heimdall.ui.exceptions.LocalizedException

fun errorSnackbar(view: View, throwable: Throwable) {
    val message = (throwable as? LocalizedException)?.message ?: view.context.getString(R.string.error_try_again)
    snackbar(view, message)
}

fun handleQrCodeActivityResult(requestCode: Int, resultCode: Int, data: Intent?,
                               onQrCodeResult: (String) -> Unit, onCancelledResult: () -> Unit) {
    if (requestCode == ZxingIntentIntegrator.REQUEST_CODE) {
        if (resultCode == Activity.RESULT_OK && data != null && data.hasExtra(ZxingIntentIntegrator.SCAN_RESULT_EXTRA)) {
            onQrCodeResult(data.getStringExtra(ZxingIntentIntegrator.SCAN_RESULT_EXTRA))
        } else if (resultCode == Activity.RESULT_CANCELED) {
            onCancelledResult()
        }
    }
}