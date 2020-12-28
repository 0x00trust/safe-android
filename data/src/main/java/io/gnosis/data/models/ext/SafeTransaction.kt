package io.gnosis.data.models.ext

import pm.gnosis.model.Solidity
import pm.gnosis.utils.hexToByteArray
import pm.gnosis.utils.toHex
import java.math.BigInteger

data class SafeTransaction(
    val to: Solidity.Address,
    val value: Solidity.UInt256,
    val data: Solidity.Bytes,
    val operation: Solidity.UInt8,
    val safetxgas: Solidity.UInt256,
    val basegas: Solidity.UInt256,
    val gasprice: Solidity.UInt256,
    val gastoken: Solidity.Address,
    val refundreceiver: Solidity.Address,
    val _nonce: Solidity.UInt256
) {
    @Deprecated("To send only eth use toSEndEthRequest and the gateway")
    private fun toCoreTransaction(senderOwner: Solidity.Address, signature: String, transactionHash: String): CoreTransactionRequest =
        CoreTransactionRequest(
            to = to,
            value = value.value,
            data = data.items.toHex(),
            operation = operation.value,
            gasToken = gastoken,
            safeTxGas = safetxgas.value,
            baseGas = basegas.value,
            gasPrice = gasprice.value,
            refundReceiver = refundreceiver,
            nonce = _nonce.value,
            contractTransactionHash = transactionHash,
            sender = senderOwner,
            signature = signature
        )

    fun toSendEthRequest(senderOwner: Solidity.Address, signature: String, transactionHash: String): SendEthRequest =
        SendEthRequest(
            receiver = to,
            value = value.value.toString(),
            data = data.items.toHex(),
            nonce = _nonce.value,
            transactionHash = transactionHash,
            sender = senderOwner,
            signedTransactionHash = signature
        )


    companion object {
        fun buildEthTransfer(receiver: Solidity.Address, value: BigInteger, nonce: BigInteger): SafeTransaction =
            SafeTransaction(
                receiver,
                Solidity.UInt256(value),
                Solidity.Bytes("0x".hexToByteArray()),
                Solidity.UInt8(BigInteger.ZERO),
                Solidity.UInt256(BigInteger.ZERO),
                Solidity.UInt256(BigInteger.ZERO),
                Solidity.UInt256(BigInteger.ZERO),
                Solidity.Address(BigInteger.ZERO),
                Solidity.Address(BigInteger.ZERO),
                Solidity.UInt256(nonce)
            )
    }
}