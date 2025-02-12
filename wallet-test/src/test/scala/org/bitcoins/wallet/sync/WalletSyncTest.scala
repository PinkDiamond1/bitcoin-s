package org.bitcoins.wallet.sync

import org.bitcoins.testkit.chain.SyncUtil
import org.bitcoins.testkit.wallet.{
  BitcoinSWalletTestCachedBitcoindNewest,
  WalletWithBitcoindRpc
}

class WalletSyncTest extends BitcoinSWalletTestCachedBitcoindNewest {

  behavior of "WalletSync"

  override type FixtureParam = WalletWithBitcoindRpc

  it must "sync a wallet with bitcoind" in { param =>
    val wallet = param.wallet
    val bitcoind = param.bitcoind
    val genesisBlockHashF = bitcoind.getBlockHash(0)
    //first we need to implement the 'getBestBlockHashFunc' and 'getBlockHeaderFunc' functions
    val getBestBlockHashFunc = SyncUtil.getBestBlockHashFunc(bitcoind)

    val getBlockHeaderFunc = SyncUtil.getBlockHeaderFunc(bitcoind)

    val getBlockFunc = SyncUtil.getBlockFunc(bitcoind)
    val syncedWalletF = {
      for {
        genesisBlockHash <- genesisBlockHashF
        synced <- WalletSync.syncFullBlocks(wallet,
                                            getBlockHeaderFunc,
                                            getBestBlockHashFunc,
                                            getBlockFunc,
                                            genesisBlockHash)
      } yield synced
    }

    val bitcoindBestHeaderF = bitcoind.getBestBlockHeader()
    for {
      syncedWallet <- syncedWalletF
      descriptorOpt <- syncedWallet.getSyncDescriptorOpt()
      bitcoindBestHeader <- bitcoindBestHeaderF
    } yield {
      descriptorOpt match {
        case Some(descriptor) =>
          assert(descriptor.bestHash == bitcoindBestHeader.hashBE)
          assert(descriptor.height == bitcoindBestHeader.height)
        case None =>
          fail(s"Could not sync wallet with bitcoind, got no descriptor!")
      }
    }
  }
}
