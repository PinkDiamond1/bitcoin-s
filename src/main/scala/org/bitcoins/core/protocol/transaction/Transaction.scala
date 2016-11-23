package org.bitcoins.core.protocol.transaction

import org.bitcoins.core.crypto.DoubleSha256Digest
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.NetworkElement
import org.bitcoins.core.serializers.transaction.{RawBaseTransactionParser, RawWitnessTransactionParser}
import org.bitcoins.core.util.{BitcoinSUtil, CryptoUtil, Factory}

import scala.util.{Failure, Success, Try}

/**
 * Created by chris on 7/14/15.
 */
sealed trait Transaction extends NetworkElement {
  /**
    * The sha256(sha256(tx)) of this transaction
    * Note that this is the big endian encoding of the hash NOT the little endian encoding displayed on block explorers
    */
  def txId : DoubleSha256Digest = CryptoUtil.doubleSHA256(bytes)

  /** The version number for this transaction */
  def version : UInt32

  /** The inputs for this transaction */
  def inputs  : Seq[TransactionInput]

  /** The outputs for this transaction */
  def outputs : Seq[TransactionOutput]

  /** The locktime for this transaction */
  def lockTime : UInt32


  /**
    * Determines if this transaction is a coinbase transaction
    * @return
    */
  def isCoinbase : Boolean = inputs.size match {
    case 1 => inputs.head match {
      case coinbase : CoinbaseInput => true
      case _ : TransactionInput => false
    }
    case _ : Int => false
  }
}


sealed trait BaseTransaction extends Transaction {
  override def hex = RawBaseTransactionParser.write(this)
}


case object EmptyTransaction extends BaseTransaction {
  override def txId = DoubleSha256Digest(BitcoinSUtil.decodeHex("0000000000000000000000000000000000000000000000000000000000000000"))
  override def version = TransactionConstants.version
  override def inputs = Nil
  override def outputs = Nil
  override def lockTime = TransactionConstants.lockTime
}

sealed trait WitnessTransaction extends Transaction {
  /** The witness used to evaluate [[org.bitcoins.core.protocol.script.ScriptSignature]]/[[org.bitcoins.core.protocol.script.ScriptPubKey]]s inside of a segwit tx
    * [[https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki]]
    */
  def witness: TransactionWitness

  def marker: Char

  def flag: Char

  override def hex = RawWitnessTransactionParser.write(this)

}

object Transaction extends Factory[Transaction] {

  /** Updates a transaction outputs */
  def factory(oldTx : Transaction, updatedOutputs : UpdateTransactionOutputs) : Transaction = {
    Transaction(oldTx.version,oldTx.inputs,updatedOutputs.outputs,oldTx.lockTime)
  }

  /** Updates a transaction's input */
  def factory(oldTx : Transaction,updatedInputs : UpdateTransactionInputs) : Transaction = {
    Transaction(oldTx.version,updatedInputs.inputs,oldTx.outputs,oldTx.lockTime)
  }

  /** Factory function that modifies a transactions locktime */
  def factory(oldTx : Transaction, lockTime : UInt32) : Transaction = {
    Transaction(oldTx.version,oldTx.inputs,oldTx.outputs,lockTime)
  }


  /** Removes the inputs of the transactions */
  def emptyInputs(oldTx : Transaction) : Transaction = Transaction(oldTx.version,Nil,oldTx.outputs,oldTx.lockTime)

  /** Removes the outputs of the transactions */
  def emptyOutputs(oldTx : Transaction) : Transaction = Transaction(oldTx.version,oldTx.inputs,Nil,oldTx.lockTime)

  def factory(bytes : Array[Byte]) : Transaction = fromBytes(bytes.toSeq)

  def fromBytes(bytes : Seq[Byte]) : Transaction = {
    val wtxTry = Try(RawWitnessTransactionParser.read(bytes))
    wtxTry match {
      case Success(wtx) => wtx
      case Failure(_) => RawBaseTransactionParser.read(bytes)
    }
  }

  def apply(bytes : Array[Byte]) : Transaction = factory(bytes)
  def apply(oldTx : Transaction, lockTime : UInt32)  : Transaction = factory(oldTx,lockTime)
  def apply(oldTx : Transaction, updatedInputs : UpdateTransactionInputs) : Transaction = factory(oldTx, updatedInputs)
  def apply(oldTx : Transaction, updatedOutputs : UpdateTransactionOutputs) : Transaction = factory(oldTx, updatedOutputs)

  def apply(version : UInt32, inputs : Seq[TransactionInput],
            outputs : Seq[TransactionOutput], lockTime : UInt32) : Transaction = {
    BaseTransaction(version,inputs,outputs,lockTime)
  }
}

object BaseTransaction extends Factory[BaseTransaction] {
  private case class BaseTransactionImpl(version : UInt32, inputs : Seq[TransactionInput],
                                         outputs : Seq[TransactionOutput], lockTime : UInt32) extends BaseTransaction

  override def fromBytes(bytes: Seq[Byte]):  BaseTransaction = RawBaseTransactionParser.read(bytes)


  def apply(version : UInt32, inputs : Seq[TransactionInput],
            outputs : Seq[TransactionOutput], lockTime : UInt32) : BaseTransaction = BaseTransactionImpl(version,inputs,outputs,lockTime)
}


object WitnessTransaction extends Factory[WitnessTransaction] {
  private case class WitnessTransactionImpl(version: UInt32, marker: Char, flag: Char, inputs: Seq[TransactionInput],
                                            outputs: Seq[TransactionOutput], lockTime: UInt32,
                                            witness: TransactionWitness) extends WitnessTransaction {
    //https://github.com/bitcoin/bips/blob/master/bip-0144.mediawiki#serialization
    require(marker == '0', "According to BIP144 the marker field must be the character '0', got: " + marker)
    require(flag != '0', "According to BIP144 the flag field must NOT be the character '0', got:" + flag)
  }

  def apply(version: UInt32, marker: Char, flag: Char, inputs: Seq[TransactionInput], outputs: Seq[TransactionOutput],
            lockTime: UInt32, witness: TransactionWitness): WitnessTransaction =
    WitnessTransactionImpl(version,marker, flag, inputs,outputs,lockTime,witness)

  override def fromBytes(bytes: Seq[Byte]): WitnessTransaction = RawWitnessTransactionParser.read(bytes)

}