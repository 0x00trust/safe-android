package pm.gnosis.heimdall.data.repositories.impls

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import pm.gnosis.ethereum.*
import pm.gnosis.ethereum.models.TransactionReceipt
import pm.gnosis.heimdall.GnosisSafePersonalEdition.*
import pm.gnosis.heimdall.ProxyFactory
import pm.gnosis.heimdall.data.db.ApplicationDb
import pm.gnosis.heimdall.data.db.models.GnosisSafeDb
import pm.gnosis.heimdall.data.db.models.PendingGnosisSafeDb
import pm.gnosis.heimdall.data.db.models.fromDb
import pm.gnosis.heimdall.data.remote.PushServiceRepository
import pm.gnosis.heimdall.data.repositories.GnosisSafeRepository
import pm.gnosis.heimdall.data.repositories.SettingsRepository
import pm.gnosis.heimdall.data.repositories.TxExecutorRepository
import pm.gnosis.heimdall.data.repositories.models.PendingSafe
import pm.gnosis.heimdall.data.repositories.models.Safe
import pm.gnosis.heimdall.data.repositories.models.SafeInfo
import pm.gnosis.model.Solidity
import pm.gnosis.model.SolidityBase
import pm.gnosis.models.Transaction
import pm.gnosis.models.Wei
import pm.gnosis.svalinn.accounts.base.models.Account
import pm.gnosis.svalinn.accounts.base.repositories.AccountsRepository
import pm.gnosis.utils.asEthereumAddressString
import pm.gnosis.utils.hexAsBigInteger
import pm.gnosis.utils.hexStringToByteArray
import pm.gnosis.utils.nullOnThrow
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultGnosisSafeRepository @Inject constructor(
    gnosisAuthenticatorDb: ApplicationDb,
    private val accountsRepository: AccountsRepository,
    private val ethereumRepository: EthereumRepository,
    private val settingsRepository: SettingsRepository,
    private val txExecutorRepository: TxExecutorRepository,
    private val pushServiceRepository: PushServiceRepository
) : GnosisSafeRepository {

    private val safeDao = gnosisAuthenticatorDb.gnosisSafeDao()
    private val descriptionsDao = gnosisAuthenticatorDb.descriptionsDao()

    override fun observeSafes() =
        Flowable.combineLatest(safeDao.observePendingSafes(), safeDao.observeSafes(),
            BiFunction { pendingSafes: List<PendingGnosisSafeDb>, safes: List<GnosisSafeDb> ->
                pendingSafes.map { it.fromDb() } + safes.map { it.fromDb() }
            })
            .subscribeOn(Schedulers.io())!!

    override fun observeDeployedSafes() =
        safeDao.observeSafes()
            .map { it.map { it.fromDb() } }
            .subscribeOn(Schedulers.io())!!

    override fun observeSafe(address: Solidity.Address): Flowable<Safe> =
        safeDao.observeSafe(address)
            .subscribeOn(Schedulers.io())
            .map { it.fromDb() }

    override fun loadSafe(address: Solidity.Address): Single<Safe> =
        safeDao.loadSafe(address)
            .subscribeOn(Schedulers.io())
            .map { it.fromDb() }

    override fun loadPendingSafe(transactionHash: BigInteger): Single<PendingSafe> =
        safeDao.loadPendingSafe(transactionHash)
            .subscribeOn(Schedulers.io())
            .map { it.fromDb() }

    override fun addSafe(address: Solidity.Address, name: String) =
        Completable.fromCallable {
            safeDao.insertSafe(GnosisSafeDb(address, name))
        }.subscribeOn(Schedulers.io())!!

    private fun loadSafeDeployTransactionWithSender(devices: Set<Solidity.Address>, requiredConfirmations: Int): Single<Pair<Account, Transaction>> =
        accountsRepository.loadActiveAccount()
            .map { account ->
                val factoryAddress = settingsRepository.getProxyFactoryAddress()
                val masterCopyAddress = settingsRepository.getSafeMasterCopyAddress()
                val owners = SolidityBase.Vector(devices.toList() + account.address)
                val confirmations = Math.max(1, Math.min(requiredConfirmations, devices.size))
                val setupData = Setup.encode(
                    // Safe owner info
                    owners, Solidity.UInt8(BigInteger.valueOf(confirmations.toLong())),
                    // Module info -> not set for now
                    Solidity.Address(BigInteger.ZERO), Solidity.Bytes(ByteArray(0))
                )
                val data = ProxyFactory.CreateProxy.encode(masterCopyAddress, Solidity.Bytes(setupData.hexStringToByteArray()))
                account to Transaction(factoryAddress, data = data)
            }

    override fun loadSafeDeployTransaction(devices: Set<Solidity.Address>, requiredConfirmations: Int): Single<Transaction> =
        loadSafeDeployTransactionWithSender(devices, requiredConfirmations).map { it.second }

    override fun deploy(name: String, devices: Set<Solidity.Address>, requiredConfirmations: Int): Single<String> {
        return loadSafeDeployTransactionWithSender(devices, requiredConfirmations)
            .map { (_, tx) -> tx }
            .flatMapObservable(txExecutorRepository::execute)
            .flatMapSingle {
                Single.fromCallable {
                    safeDao.insertPendingSafe(PendingGnosisSafeDb(it.hexAsBigInteger(), name))
                    it
                }
            }
            .firstOrError()
    }

    override fun savePendingSafe(transactionHash: BigInteger, name: String): Completable = Completable.fromAction {
        safeDao.insertPendingSafe(PendingGnosisSafeDb(transactionHash, name))
    }.subscribeOn(Schedulers.io())

    override fun observeDeployStatus(hash: String): Observable<String> {
        return ethereumRepository.getTransactionReceipt(hash)
            .flatMap {
                if (it.status != null) {
                    it.logs.forEach {
                        decodeCreationEventOrNull(it)?.let {
                            return@flatMap Observable.just(it.proxy)
                        }
                    }
                    Observable.error<Solidity.Address>(SafeDeploymentFailedException())
                } else {
                    Observable.error<Solidity.Address>(IllegalStateException())
                }
            }
            .retryWhen {
                it.flatMap {
                    if (it is SafeDeploymentFailedException) {
                        Observable.error(it)
                    } else {
                        Observable.just(it).delay(20, TimeUnit.SECONDS)
                    }
                }
            }
            .flatMapSingle { safeAddress ->
                safeDao.loadPendingSafe(hash.hexAsBigInteger()).map { pendingSafe ->
                    safeAddress to pendingSafe
                }
            }
            .map {
                safeDao.removePendingSafe(hash.hexAsBigInteger())
                safeDao.insertSafe(GnosisSafeDb(it.first, it.second.name))
                it
            }
            .flatMap { sendSafeCreationPush(it.first).andThen(Observable.just(it.first.asEthereumAddressString())) }
            .doOnError {
                safeDao.removePendingSafe(hash.hexAsBigInteger())
            }
    }

    private fun sendSafeCreationPush(safeAddress: Solidity.Address) =
        Single.zip(
            accountsRepository.loadActiveAccount().map { it.address },
            loadInfo(safeAddress).firstOrError().map { it.owners },
            BiFunction<Solidity.Address, List<Solidity.Address>, Set<Solidity.Address>> { deviceAddress, owners -> (owners - deviceAddress).toSet() }
        )
            .flatMapCompletable { pushServiceRepository.sendSafeCreationNotification(safeAddress, it) }
            .onErrorComplete()

    private fun decodeCreationEventOrNull(event: TransactionReceipt.Event) =
        nullOnThrow { ProxyFactory.Events.ProxyCreation.decode(event.topics, event.data) }

    override fun removeSafe(address: Solidity.Address) =
        Completable.fromCallable {
            safeDao.removeSafe(address)
        }.subscribeOn(Schedulers.io())!!

    override fun updateName(address: Solidity.Address, newName: String) =
        Completable.fromCallable {
            safeDao.updateSafe(GnosisSafeDb(address, newName))
        }.subscribeOn(Schedulers.io())!!

    override fun loadInfo(address: Solidity.Address): Observable<SafeInfo> =
        accountsRepository.loadActiveAccount()
            .map {
                SafeInfoRequest(
                    EthBalance(address, 0),
                    EthCall(
                        transaction = Transaction(address = address, data = GetThreshold.encode()),
                        id = 1
                    ),
                    EthCall(
                        transaction = Transaction(address = address, data = GetOwners.encode()),
                        id = 2
                    ),
                    EthCall(
                        transaction = Transaction(
                            address = address,
                            data = IsOwner.encode(it.address)
                        ), id = 3
                    ),
                    EthCall(
                        transaction = Transaction(address = address, data = GetModules.encode()),
                        id = 4
                    )
                )
            }
            .flatMapObservable { bulk -> ethereumRepository.request(bulk) }
            .map {
                val balance = it.balance.result() ?: throw IllegalArgumentException()
                val threshold = GetThreshold.decode(it.threshold.result() ?: throw IllegalArgumentException()).param0.value.toLong()
                val owners = GetOwners.decode(it.owners.result() ?: throw IllegalArgumentException()).param0.items
                val isOwner = IsOwner.decode(it.isOwner.result() ?: throw IllegalArgumentException()).param0.value
                val modules = GetModules.decode(it.modules.result() ?: throw IllegalArgumentException()).param0.items.map { it }
                SafeInfo(address.asEthereumAddressString(), balance, threshold, owners, isOwner, modules)
            }

    override fun observeTransactionDescriptions(address: Solidity.Address): Flowable<List<String>> = descriptionsDao.observeDescriptions(address)

    private class SafeInfoRequest(
        val balance: EthRequest<Wei>,
        val threshold: EthRequest<String>,
        val owners: EthRequest<String>,
        val isOwner: EthRequest<String>,
        val modules: EthRequest<String>
    ) : BulkRequest(balance, threshold, owners, isOwner, modules)

    private class SafeDeploymentFailedException : IllegalArgumentException()
}
