package org.bitcoins.commons.jsonmodels.bitcoind

import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.{ScriptPubKey, WitnessScriptPubKey}
import org.bitcoins.core.protocol.transaction.{
  TransactionInput,
  TransactionOutPoint
}
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.crypto.{
  DoubleSha256DigestBE,
  ECPrivateKeyBytes,
  StringFactory
}
import play.api.libs.json.{Json, Writes}
import ujson.{Num, Str, Value}

import scala.collection.mutable

object RpcOpts {

  case class WalletCreateFundedPsbtOptions(
      include_unsafe: Boolean = false,
      changeAddress: Option[BitcoinAddress] = None,
      changePosition: Option[Int] = None,
      changeType: Option[AddressType] = None,
      includeWatching: Boolean = false,
      lockUnspents: Boolean = false,
      feeRate: Option[Bitcoins] = None,
      subtractFeeFromOutputs: Option[Vector[Int]] = None,
      replaceable: Boolean = false,
      confTarget: Option[Int] = None,
      estimateMode: FeeEstimationMode = FeeEstimationMode.Unset
  )

  case class FundRawTransactionOptions(
      include_unsafe: Boolean = false,
      changeAddress: Option[BitcoinAddress] = None,
      changePosition: Option[Int] = None,
      includeWatching: Boolean = false,
      lockUnspents: Boolean = false,
      reverseChangeKey: Boolean = true,
      feeRate: Option[Bitcoins] = None,
      subtractFeeFromOutputs: Option[Vector[Int]])

  sealed abstract class FeeEstimationMode

  object FeeEstimationMode {

    case object Unset extends FeeEstimationMode {
      override def toString: String = "UNSET"
    }

    case object Ecnomical extends FeeEstimationMode {
      override def toString: String = "ECONOMICAL"
    }

    case object Conservative extends FeeEstimationMode {
      override def toString: String = "CONSERVATIVE"
    }
  }

  sealed abstract class SetBanCommand

  object SetBanCommand {

    case object Add extends SetBanCommand {
      override def toString: String = "add"
    }

    case object Remove extends SetBanCommand {
      override def toString: String = "remove"
    }
  }

  implicit val fundRawTransactionOptionsWrites: Writes[
    FundRawTransactionOptions] = Json.writes[FundRawTransactionOptions]

  case class SignRawTransactionOutputParameter(
      txid: DoubleSha256DigestBE,
      vout: Int,
      scriptPubKey: ScriptPubKey,
      redeemScript: Option[ScriptPubKey] = None,
      witnessScript: Option[WitnessScriptPubKey] = None,
      amount: Option[Bitcoins] = None)

  implicit val signRawTransactionOutputParameterWrites: Writes[
    SignRawTransactionOutputParameter] =
    Json.writes[SignRawTransactionOutputParameter]

  object SignRawTransactionOutputParameter {

    def fromTransactionInput(
        transactionInput: TransactionInput,
        scriptPubKey: ScriptPubKey,
        redeemScript: Option[ScriptPubKey] = None,
        witnessScript: Option[WitnessScriptPubKey] = None,
        amount: Option[Bitcoins] = None): SignRawTransactionOutputParameter = {
      SignRawTransactionOutputParameter(
        txid = transactionInput.previousOutput.txIdBE,
        vout = transactionInput.previousOutput.vout.toInt,
        scriptPubKey = scriptPubKey,
        redeemScript = redeemScript,
        witnessScript = witnessScript,
        amount = amount
      )
    }
  }

  case class ImportMultiRequest(
      scriptPubKey: ImportMultiAddress,
      timestamp: UInt32,
      redeemscript: Option[ScriptPubKey] = None,
      pubkeys: Option[Vector[ScriptPubKey]] = None,
      keys: Option[Vector[ECPrivateKeyBytes]] = None,
      internal: Option[Boolean] = None,
      watchonly: Option[Boolean] = None,
      label: Option[String] = None)

  case class ImportMultiAddress(address: BitcoinAddress)

  case class LockUnspentOutputParameter(txid: DoubleSha256DigestBE, vout: Int) {

    lazy val outPoint: TransactionOutPoint =
      TransactionOutPoint(txid, UInt32(vout))

    lazy val toJson: Value = {
      mutable.LinkedHashMap(
        "txid" -> Str(txid.hex),
        "vout" -> Num(vout)
      )
    }
  }

  implicit val lockUnspentParameterWrites: Writes[LockUnspentOutputParameter] =
    Json.writes[LockUnspentOutputParameter]

  object LockUnspentOutputParameter {

    def fromOutPoint(
        outPoint: TransactionOutPoint): LockUnspentOutputParameter = {
      LockUnspentOutputParameter(outPoint.txIdBE, outPoint.vout.toInt)
    }

    def fromJsonString(str: String): LockUnspentOutputParameter = {
      val json = ujson.read(str)
      fromJson(json)
    }

    def fromJson(json: Value): LockUnspentOutputParameter = {
      val obj = json.obj
      val txId = DoubleSha256DigestBE(obj("txid").str)
      val vout = obj("vout").num.toInt

      LockUnspentOutputParameter(txId, vout)
    }
  }

  sealed trait AddNodeArgument

  object AddNodeArgument {

    case object Add extends AddNodeArgument {
      override def toString: String = "add"
    }

    case object Remove extends AddNodeArgument {
      override def toString: String = "remove"
    }

    case object OneTry extends AddNodeArgument {
      override def toString: String = "onetry"
    }

  }

  sealed trait WalletFlag

  object WalletFlag {

    case object AvoidReuse extends WalletFlag {
      override def toString: String = "avoid_reuse"
    }
  }

  sealed trait AddressType

  object AddressType extends StringFactory[AddressType] {

    case object Legacy extends AddressType {
      override def toString: String = "legacy"
    }

    case object P2SHSegwit extends AddressType {
      override def toString: String = "p2sh-segwit"
    }

    case object Bech32 extends AddressType {
      override def toString: String = "bech32"
    }

    case object Bech32m extends AddressType {
      override def toString: String = "bech32m"
    }

    lazy val all: Vector[AddressType] =
      Vector(Legacy, P2SHSegwit, Bech32, Bech32m)

    override def fromStringOpt(string: String): Option[AddressType] = {
      val searchString = string.toLowerCase.trim
      all.find(_.toString.toLowerCase == searchString)
    }

    override def fromString(string: String): AddressType = {
      fromStringOpt(string).getOrElse(
        throw new IllegalArgumentException(
          s"Could not find AddressType for string: $string"))
    }
  }

  sealed trait LabelPurpose

  object LabelPurpose {

    case object Send extends LabelPurpose {
      override def toString: String = "send"
    }

    case object Receive extends LabelPurpose {
      override def toString: String = "receive"
    }
  }

  case class BlockTemplateRequest(
      mode: String,
      capabilities: Vector[String],
      rules: Vector[String])

  implicit val blockTemplateRequest: Writes[BlockTemplateRequest] =
    Json.writes[BlockTemplateRequest]
}
