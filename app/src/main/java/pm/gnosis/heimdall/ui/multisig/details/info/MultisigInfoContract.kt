package pm.gnosis.heimdall.ui.multisig.details.info

import android.arch.lifecycle.ViewModel
import io.reactivex.Observable
import pm.gnosis.heimdall.common.util.Result
import pm.gnosis.heimdall.data.repositories.model.MultisigWalletInfo


abstract class MultisigInfoContract : ViewModel() {
    abstract fun setup(address: String)
    abstract fun loadMultisigInfo(ignoreCache: Boolean): Observable<Result<MultisigWalletInfo>>
}