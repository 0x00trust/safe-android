package pm.gnosis.heimdall.ui.tokens.balances

import android.content.Intent
import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jakewharton.rxbinding2.support.v4.widget.refreshes
import com.jakewharton.rxbinding2.view.clicks
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.layout_token_balances.*
import pm.gnosis.heimdall.R
import pm.gnosis.heimdall.common.di.components.ApplicationComponent
import pm.gnosis.heimdall.common.di.components.DaggerViewComponent
import pm.gnosis.heimdall.common.di.modules.ViewModule
import pm.gnosis.heimdall.common.utils.*
import pm.gnosis.heimdall.ui.base.Adapter
import pm.gnosis.heimdall.ui.base.BaseFragment
import pm.gnosis.heimdall.ui.tokens.add.AddTokenActivity
import pm.gnosis.heimdall.utils.errorSnackbar
import pm.gnosis.heimdall.utils.handleQrCodeActivityResult
import pm.gnosis.utils.hexAsEthereumAddressOrNull
import pm.gnosis.utils.isValidEthereumAddress
import timber.log.Timber
import javax.inject.Inject

class TokenBalancesFragment : BaseFragment() {
    @Inject lateinit var viewModel: TokenBalancesContract
    @Inject lateinit var adapter: TokenBalancesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.layout_token_balances, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val addressArgument = arguments?.getString(ARGUMENT_ADDRESS)?.hexAsEthereumAddressOrNull()
        layout_tokens_fab.visibility = if (addressArgument == null) View.VISIBLE else View.GONE

        if (addressArgument != null) {
            viewModel.setup(addressArgument)
        } else {
            snackbar(layout_tokens_coordinator_layout, R.string.invalid_ethereum_address)
            activity?.finish()
        }

        layout_tokens_list.layoutManager = LinearLayoutManager(context)
        layout_tokens_list.adapter = adapter
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleQrCodeActivityResult(requestCode, resultCode, data, {
            if (it.isValidEthereumAddress()) {
                startActivity(AddTokenActivity.createIntent(activity!!, it))
            } else {
                snackbar(layout_tokens_coordinator_layout, R.string.invalid_ethereum_address)
            }
        }, { snackbar(layout_tokens_coordinator_layout, R.string.qr_code_scan_cancel) })
    }

    override fun onStart() {
        super.onStart()
        disposables += layout_tokens_input_address.clicks()
                .subscribeBy(onNext = {
                    layout_tokens_fab.close(true)
                    startActivity(AddTokenActivity.createIntent(activity!!))
                }, onError = Timber::e)

        disposables += layout_tokens_scan_qr_code.clicks()
                .subscribeBy(onNext = {
                    layout_tokens_fab.close(true)
                    scanQrCode()
                }, onError = Timber::e)

        disposables += viewModel.observeLoadingStatus()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(onNext = {
                    layout_tokens_swipe_refresh.isRefreshing = it
                }, onError = Timber::e)

        disposables += viewModel.observeTokens(layout_tokens_swipe_refresh.refreshes())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeForResult(onNext = ::onTokensList, onError = ::onTokensListError)
    }

    private fun onTokensList(tokens: Adapter.Data<TokenBalancesContract.ERC20TokenWithBalance>) {
        adapter.updateData(tokens)
        layout_tokens_empty_view.visibility = if (tokens.entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onTokensListError(throwable: Throwable) {
        errorSnackbar(layout_tokens_coordinator_layout, throwable)
    }

    override fun inject(component: ApplicationComponent) {
        DaggerViewComponent.builder()
                .applicationComponent(component)
                .viewModule(ViewModule(context!!))
                .build().inject(this)
    }

    companion object {
        private const val ARGUMENT_ADDRESS = "argument.string.address"

        fun createInstance(address: String) =
                TokenBalancesFragment().withArgs(Bundle().build { putString(ARGUMENT_ADDRESS, address) })
    }
}