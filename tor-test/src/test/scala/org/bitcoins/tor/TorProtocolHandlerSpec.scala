package org.bitcoins.tor

import akka.actor.ActorSystem
import akka.io.Tcp.Connected
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.ByteString
import org.bitcoins.crypto.CryptoUtil
import org.scalatest._
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class TorProtocolHandlerSpec
    extends TestKit(ActorSystem("test"))
    with TestSuite
    with BeforeAndAfterAll
    with AnyFunSuiteLike
    with ImplicitSender {

  import TorProtocolHandler._

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val LocalHost = new InetSocketAddress("localhost", 8888)
  val PASSWORD = "foobar"

  val ClientNonce =
    hex"8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA"
  val tempDir = Files.createTempDirectory("tor-test-").toAbsolutePath.toString
  val PkFilePath = Paths.get(tempDir, "testtorpk.dat")
  val CookieFilePath = Paths.get(tempDir, "testtorcookie.dat")

  val AuthCookie =
    hex"AA8593C52DF9713CC5FF6A1D0A045B3FADCAE57745B1348A62A6F5F88D940485"

  override def withFixture(test: NoArgTest) = {
    PkFilePath.toFile.delete()
    super.withFixture(test) // Invoke the test function
  }

  /*  ignore("connect to real tor daemon") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    val protocolHandlerProps =
      TorProtocolHandler.props(version = OnionServiceVersion("v2"),
                               authentication = Password(PASSWORD),
                               privateKeyPath = PkFilePath,
                               virtualPort = 9999,
                               onionAdded = Some(promiseOnionAddress))

    val controller =
      TestActorRef(TorController.props(new InetSocketAddress("localhost", 9051),
                                       protocolHandlerProps),
                   "tor")

    val address = Await.result(promiseOnionAddress.future, 30.seconds)
    println(address)
  }*/

  test("happy path v3") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    val protocolHandler = TestActorRef(
      props(authentication = Password(PASSWORD),
            privateKeyPath = PkFilePath,
            virtualPort = 9999,
            onionAdded = Some(promiseOnionAddress)))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=HASHEDPASSWORD\r\n" +
        "250-VERSION Tor=\"0.3.4.8\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString(s"""AUTHENTICATE "$PASSWORD"\r\n"""))
    protocolHandler ! ByteString(
      "250 OK\r\n"
    )

    expectMsg(ByteString("ADD_ONION NEW:ED25519-V3 Port=9999,9999\r\n"))
    protocolHandler ! ByteString(
      "250-ServiceID=mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd\r\n" +
        "250-PrivateKey=ED25519-V3:private-key\r\n" +
        "250 OK\r\n"
    )

    protocolHandler ! GetOnionAddress
    val addr = expectMsgType[Option[InetSocketAddress]]

    assert(addr.nonEmpty)

    assertAddressesEqual(
      addr.get,
      InetSocketAddress.createUnresolved(
        "mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd.onion",
        9999))

    val address = Await.result(promiseOnionAddress.future, 3.seconds)
    assertAddressesEqual(
      address,
      InetSocketAddress.createUnresolved(
        "mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd.onion",
        9999))

    assert(readString(PkFilePath) === "ED25519-V3:private-key")
  }

  test("compatibility check against tor version") {
    assert(OnionServiceVersion.isCompatible("0.3.3.6"))
    assert(!OnionServiceVersion.isCompatible("0.3.3.5"))
    assert(OnionServiceVersion.isCompatible("0.3.3.6-devel"))
    assert(OnionServiceVersion.isCompatible("0.4"))
    assert(!OnionServiceVersion.isCompatible("0.2"))
    assert(OnionServiceVersion.isCompatible("0.5.1.2.3.4"))
  }

  test("handle unsupported Tor version") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    val protocolHandler = TestActorRef(
      props(authentication = Password(PASSWORD),
            privateKeyPath = PkFilePath,
            virtualPort = 9999,
            onionAdded = Some(promiseOnionAddress)))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=HASHEDPASSWORD\r\n" +
        "250-VERSION Tor=\"0.3.3.5\"\r\n" +
        "250 OK\r\n"
    )

    assert(intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    } === TorException("Tor version 0.3.3.5 is not supported"))
  }

  test("authentication method errors") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    val protocolHandler = TestActorRef(
      props(authentication = Password(PASSWORD),
            privateKeyPath = PkFilePath,
            virtualPort = 9999,
            onionAdded = Some(promiseOnionAddress)))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + CookieFilePath + "\"\r\n" +
        "250-VERSION Tor=\"0.4.7.10\"\r\n" +
        "250 OK\r\n"
    )

    assert(intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    } === TorException(
      "cannot use authentication 'password', supported methods are 'COOKIE,SAFECOOKIE'"))
  }

  test("invalid server hash") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    Files.write(CookieFilePath, CryptoUtil.randomBytes(32).toArray)

    val protocolHandler = TestActorRef(
      props(authentication = SafeCookie(ClientNonce),
            privateKeyPath = PkFilePath,
            virtualPort = 9999,
            onionAdded = Some(promiseOnionAddress)))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + CookieFilePath + "\"\r\n" +
        "250-VERSION Tor=\"0.4.7.10\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString(
      "AUTHCHALLENGE SAFECOOKIE 8969a7f3c03cd21bfd1cc49dbbd8f398345261b5b66319df76bb2fdd8d96bcca\r\n"))
    protocolHandler ! ByteString(
      "250 AUTHCHALLENGE SERVERHASH=6828e74049924f37cbc61f2aad4dd78d8dc09bef1b4c3bf6ff454016ed9d50df SERVERNONCE=b4aa04b6e7e2df60dcb0f62c264903346e05d1675e77795529e22ca90918dee7\r\n"
    )

    assert(intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    } === TorException("unexpected server hash"))
  }

  test("AUTHENTICATE failure") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    Files.write(CookieFilePath, AuthCookie.toArray)

    val protocolHandler = TestActorRef(
      props(
        authentication = SafeCookie(ClientNonce),
        privateKeyPath = PkFilePath,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress)
      ))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + CookieFilePath + "\"\r\n" +
        "250-VERSION Tor=\"0.4.7.10\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString(
      "AUTHCHALLENGE SAFECOOKIE 8969a7f3c03cd21bfd1cc49dbbd8f398345261b5b66319df76bb2fdd8d96bcca\r\n"))
    protocolHandler ! ByteString(
      "250 AUTHCHALLENGE SERVERHASH=6828e74049924f37cbc61f2aad4dd78d8dc09bef1b4c3bf6ff454016ed9d50df SERVERNONCE=b4aa04b6e7e2df60dcb0f62c264903346e05d1675e77795529e22ca90918dee7\r\n"
    )

    expectMsg(ByteString(
      "AUTHENTICATE 0ddcab5deb39876cdef7af7860a1c738953395349f43b99f4e5e0f131b0515df\r\n"))
    protocolHandler ! ByteString(
      "515 Authentication failed: Safe cookie response did not match expected value.\r\n"
    )

    assert(intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    } === TorException(
      "server returned error: 515 Authentication failed: Safe cookie response did not match expected value."))
  }

  test("ADD_ONION failure") {
    val promiseOnionAddress = Promise[InetSocketAddress]()

    Files.write(CookieFilePath, AuthCookie.toArray)

    val protocolHandler = TestActorRef(
      props(
        authentication = SafeCookie(ClientNonce),
        privateKeyPath = PkFilePath,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress)
      ))

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + CookieFilePath + "\"\r\n" +
        "250-VERSION Tor=\"0.4.7.10\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString(
      "AUTHCHALLENGE SAFECOOKIE 8969a7f3c03cd21bfd1cc49dbbd8f398345261b5b66319df76bb2fdd8d96bcca\r\n"))
    protocolHandler ! ByteString(
      "250 AUTHCHALLENGE SERVERHASH=6828e74049924f37cbc61f2aad4dd78d8dc09bef1b4c3bf6ff454016ed9d50df SERVERNONCE=b4aa04b6e7e2df60dcb0f62c264903346e05d1675e77795529e22ca90918dee7\r\n"
    )

    expectMsg(ByteString(
      "AUTHENTICATE 0ddcab5deb39876cdef7af7860a1c738953395349f43b99f4e5e0f131b0515df\r\n"))
    protocolHandler ! ByteString(
      "250 OK\r\n"
    )

    expectMsg(ByteString("ADD_ONION NEW:ED25519-V3 Port=9999,9999\r\n"))
    protocolHandler ! ByteString(
      "513 Invalid argument\r\n"
    )

    intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    }

    assert(intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3.seconds)
    } === TorException("server returned error: 513 Invalid argument"))
  }

  private def assertAddressesEqual(
      actual: InetSocketAddress,
      expected: InetSocketAddress) = {
    assert(
      actual.getHostString == expected.getHostString && actual.getPort == expected.getPort)
  }

}
