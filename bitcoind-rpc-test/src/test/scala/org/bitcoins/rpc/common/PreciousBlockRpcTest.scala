package org.bitcoins.rpc.common

import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddNodeArgument
import org.bitcoins.testkit.rpc.{
  BitcoindFixturesCachedPairNewest,
  BitcoindRpcTestUtil
}

class PreciousBlockRpcTest extends BitcoindFixturesCachedPairNewest {

  it should "be able to mark a block as precious" in { nodePair =>
    val freshClient = nodePair.node1
    val otherFreshClient = nodePair.node2

    for {
      _ <- freshClient.disconnectNode(otherFreshClient.getDaemon.uri)
      _ <- BitcoindRpcTestUtil.awaitDisconnected(freshClient, otherFreshClient)

      blocks1 <-
        freshClient.generate(1)
      blocks2 <- otherFreshClient.generate(1)

      bestHash1 <- freshClient.getBestBlockHash
      _ = assert(bestHash1 == blocks1.head)
      bestHash2 <- otherFreshClient.getBestBlockHash
      _ = assert(bestHash2 == blocks2.head)

      _ <-
        freshClient
          .addNode(otherFreshClient.getDaemon.uri, AddNodeArgument.OneTry)
      _ <- AsyncUtil.retryUntilSatisfiedF(() =>
        BitcoindRpcTestUtil.hasSeenBlock(otherFreshClient, bestHash1))

      _ <- otherFreshClient.preciousBlock(bestHash1)
      newBestHash <- otherFreshClient.getBestBlockHash

    } yield assert(newBestHash == bestHash1)
  }
}
