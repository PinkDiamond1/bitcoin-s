package org.bitcoins.wallet

import org.bitcoins.core.currency.{Bitcoins, Satoshis}
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.wallet.utxo.StorageLocationTag.HotStorage
import org.bitcoins.core.wallet.utxo.{
  AddressLabelTag,
  AddressLabelTagType,
  StorageLocationTagType
}
import org.bitcoins.testkit.wallet.FundWalletUtil.FundedWallet
import org.bitcoins.testkit.wallet.{BitcoinSWalletTest, WalletTestUtil}
import org.scalatest.FutureOutcome

import scala.concurrent.Future

class AddressHandlingTest extends BitcoinSWalletTest {
  type FixtureParam = FundedWallet

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    withFundedWallet(test)(getFreshWalletAppConfig)
  }

  behavior of "AddressHandling"

  it must "generate a new address for the default account and then find it" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet
      val addressF = wallet.getNewAddress()

      for {
        address <- addressF
        exists <- wallet.contains(address, None)
      } yield {
        assert(exists, s"Wallet must contain address after generating it")
      }
  }

  it must "generate an address for a non default account and then find it" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet
      val account1 = WalletTestUtil.getHdAccount1(wallet.walletConfig)
      val addressF = wallet.getNewAddress(account1)
      for {
        address <- addressF
        listAddressesForAcct <- wallet.listAddresses(account1)
        exists <- wallet.contains(address, Some(account1))
        doesNotExist <- wallet.contains(address, None)
      } yield {
        assert(listAddressesForAcct.nonEmpty)
        assert(listAddressesForAcct.map(_.address).contains(address))
        assert(exists,
               s"Wallet must contain address in specific after generating it")
        assert(
          doesNotExist,
          s"Wallet must NOT contain address in default account when address is specified")
      }
  }

  it must "generate the same unused address until it receives funds" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet

      for {
        address1 <- wallet.getUnusedAddress
        exists <- wallet.contains(address1, None)
        _ = assert(exists, s"Wallet must contain address after generating it")
        address2 <- wallet.getUnusedAddress
        _ = assert(address1 == address2, "Must generate same address")
        _ <- wallet.sendToAddress(address1, Satoshis(10000), None)
        address3 <- wallet.getUnusedAddress
      } yield {
        assert(address1 != address3, "Must generate a new address")
        assert(address2 != address3, "Must generate a new address")
      }
  }

  it must "be safe to call getNewAddress multiple times in a row" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet
      val addressesF = Future.sequence {
        Vector.fill(10)(wallet.getNewAddress())
      }

      for {
        addresses <- addressesF
      } yield {
        assert(addresses.size == 10)
        assert(addresses.distinct.length == addresses.length,
               s"We receive an identical address!")

      }
  }

  it must "get the correct spent addresses" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      emptySpentAddresses <- wallet.listSpentAddresses()
      _ = assert(
        emptySpentAddresses.isEmpty,
        s"Wallet did not start with empty spent addresses, got $emptySpentAddresses")

      tempAddress <- wallet.getNewAddress()
      tx <- wallet.sendToAddress(tempAddress, Bitcoins(1), None)
      spentDbs <- wallet.spendingInfoDAO.findOutputsBeingSpent(tx)
      spentAddresses <- wallet.listSpentAddresses()
    } yield {
      val diff = spentDbs
        .map(_.output.scriptPubKey)
        .diff(spentAddresses.map(_.scriptPubKey))
      assert(diff.isEmpty, s"Extra spent addresses $diff")
    }
  }

  it must "get the correct funded addresses" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      unspentDbs <- wallet.spendingInfoDAO.findAllUnspent()
      fundedAddresses <- wallet.listFundedAddresses()
    } yield {
      val diff = unspentDbs
        .map(_.output)
        .diff(fundedAddresses.map(tuple =>
          TransactionOutput(tuple._2, tuple._1.scriptPubKey)))
      assert(diff.isEmpty, s"Extra funded addresses $diff")
    }
  }

  it must "get the correct unused addresses" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      addrDbs <- wallet.spendingInfoDAO.findAllSpendingInfos()
      fundedAddresses <- wallet.listUnusedAddresses()
    } yield {
      val intersect = addrDbs
        .map(_.output.scriptPubKey)
        .intersect(fundedAddresses.map(_.scriptPubKey))
      assert(intersect.isEmpty, s"Returned used addresses $intersect")
    }
  }

  it must "tag an address" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      addr <- wallet.getNewAddress()
      initTags <- wallet.getAddressTags(addr)
      _ = assert(initTags.isEmpty)

      tag = AddressLabelTag("for test")
      _ <- wallet.tagAddress(addr, tag)
      tags <- wallet.getAddressTags(addr)
    } yield {
      assert(tags.size == 1)
      val tagDb = tags.head
      assert(tagDb.address == addr)
      assert(tagDb.addressTag == tag)
    }
  }

  it must "drop an address tag" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      addr <- wallet.getNewAddress()
      initTags <- wallet.getAddressTags(addr)
      _ = assert(initTags.isEmpty)

      tag = AddressLabelTag("no one knows the supply of eth")
      _ <- wallet.tagAddress(addr, tag)
      tags <- wallet.getAddressTags(addr)
      _ = assert(tags.size == 1)
      tagDb = tags.head
      _ = assert(tagDb.address == addr)
      _ = assert(tagDb.addressTag == tag)

      num <- wallet.dropAddressTag(tagDb)
    } yield assert(num == 1)
  }

  it must "drop an address tag type" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet

    for {
      addr <- wallet.getNewAddress()
      addr1 <- wallet.getNewAddress()
      initTags <- wallet.getAddressTags(addr)
      initTags1 <- wallet.getAddressTags(addr1)
      _ = assert(initTags.isEmpty)
      _ = assert(initTags1.isEmpty)

      tag = AddressLabelTag("no one knows the supply of eth")
      _ <- wallet.tagAddress(addr, tag)
      _ <- wallet.tagAddress(addr, HotStorage)
      _ <- wallet.tagAddress(addr1, tag)
      tags <- wallet.getAddressTags(AddressLabelTagType)
      _ = assert(tags.size == 2)
      tagDb = tags.head
      _ = assert(tagDb.address == addr)
      _ = assert(tagDb.addressTag == tag)
      tagDb1 = tags.last
      _ = assert(tagDb1.address == addr1)
      _ = assert(tagDb1.addressTag == tag)

      numDropped <- wallet.dropAddressTagType(AddressLabelTagType)
      hotStorageTags <- wallet.getAddressTags(StorageLocationTagType)
    } yield {
      assert(numDropped == 2)
      assert(hotStorageTags.size == 1)
      assert(hotStorageTags.head.address == addr)
    }
  }

  it must "add public key scripts to watch" in { fundedWallet: FundedWallet =>
    val wallet = fundedWallet.wallet
    val spk = EmptyScriptPubKey
    for {
      before <- wallet.listScriptPubKeys()
      spkDb <- wallet.watchScriptPubKey(spk)
      after <- wallet.listScriptPubKeys()
    } yield {
      assert(before.size + 1 == after.size)
      assert(spkDb.scriptPubKey == spk)
      assert(spkDb.id.nonEmpty)
      assert(!before.contains(spkDb))
      assert(after.contains(spkDb))
    }
  }

  it must "correctly identify change addresses" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet

      for {
        nonChange <- wallet.getNewAddress()
        output0 = TransactionOutput(Satoshis.zero, nonChange.scriptPubKey)
        res0 <- wallet.isChange(output0)
        _ = assert(!res0)

        change <- wallet.getNewChangeAddress()
        output1 = TransactionOutput(Satoshis.zero, change.scriptPubKey)
        res1 <- wallet.isChange(output1)
      } yield assert(res1)
  }

  it must "create an address and not have an associated utxo until we deposit" in {
    fundedWallet: FundedWallet =>
      val wallet = fundedWallet.wallet
      val addressF = wallet.getNewAddress()
      val spendingInfoDAO = wallet.spendingInfoDAO
      val spkDAO = wallet.scriptPubKeyDAO
      for {
        address <- addressF
        utxos <- spendingInfoDAO.findByScriptPubKey(address.scriptPubKey)
        spkOpt <- spkDAO.findScriptPubKey(address.scriptPubKey)
      } yield {
        assert(utxos.isEmpty)
        assert(spkOpt.isDefined)
      }
  }
}
