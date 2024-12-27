/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package testpkg

import sbt.internal.bsp._
import sbt.internal.protocol.JsonRpcRequestMessage
import sbt.internal.protocol.codec.JsonRPCProtocol._
import sjsonnew.JsonWriter
import sjsonnew.support.scalajson.unsafe.{ CompactPrinter, Converter }

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

// starts svr using server-test/buildserver and perform custom server tests
object BuildServerTest extends AbstractServerTest {

  import sbt.internal.bsp.codec.JsonProtocol._

  override val testDirectory: String = "buildserver"

  private val idGen: AtomicInteger = new AtomicInteger(0)
  private def nextId(): Int = idGen.getAndIncrement()

  test("build/initialize") { _ =>
    val id = initializeRequest()
    assertMessage(
      s""""id":"${id}"""",
      """"resourcesProvider":true""",
      """"outputPathsProvider":true"""
    )()
  }

  /*  test("buildTarget/cleanCache") { _ =>
    def targetDir =
      Paths
        .get(
          svr.baseDirectory.getAbsoluteFile.toString,
          "run-and-test/target/scala-2.13/classes/main"
        )
        .toFile

    val buildTarget = buildTargetUri("runAndTest", "Compile")
    compile(buildTarget)
    svr.waitFor[BspCompileResult](10.seconds)
    assert(targetDir.list().contains("Main.class"))

    svr.sendJsonRpc(
      s"""{ "jsonrpc": "2.0", "id": "${nextId()}", "method": "buildTarget/cleanCache", "params": {
         |  "targets": [{ "uri": "$buildTarget" }]
         |} }""".stripMargin
    )
    assertProcessing("buildTarget/cleanCache")
    val res = svr.waitFor[CleanCacheResult](10.seconds)
    assert(res.cleaned)
    assert(targetDir.list().isEmpty)
  }*/

  test("buildTarget/cleanCache: rebuild project") { _ =>
    svr.sendJsonRpc(
      s"""{ "jsonrpc": "2.0", "id": "${nextId()}", "method": "workspace/buildTargets", "params": {} }"""
    )
    assertProcessing("workspace/buildTargets")
    val result = svr.waitFor[WorkspaceBuildTargetsResult](10.seconds)
    val allTargets = result.targets.map(_.id.uri)

    svr.sendJsonRpc(
      s"""{ "jsonrpc": "2.0", "id": "${nextId()}", "method": "buildTarget/cleanCache", "params": {
         |  "targets": [
         |    ${allTargets.map(uri => s"""{ "uri": "$uri" }""").mkString(",\n")}
         |  ]
         |} }""".stripMargin
    )
    assertProcessing("buildTarget/cleanCache")
    val res = svr.waitFor[CleanCacheResult](600.seconds)
    assert(res.cleaned)
  }

  private def initializeRequest(): Int = {
    val params = InitializeBuildParams(
      "test client",
      "1.0.0",
      "2.1.0-M1",
      new URI("file://root/"),
      BuildClientCapabilities(Vector("scala")),
      None
    )
    sendRequest("build/initialize", params)
  }

  private def assertProcessing(method: String, debug: Boolean = false): Unit =
    assertMessage("build/logMessage", s""""message":"Processing $method"""")(debug = debug)

  def assertMessage(
      parts: String*
  )(duration: FiniteDuration = 10.seconds, debug: Boolean = false, message: String = ""): Unit = {
    def assertion =
      svr.waitForString(duration) { msg =>
        if (debug) println(msg)
        parts.forall(msg.contains)
      }
    if (message.nonEmpty) assert.apply(assertion, message) else assert(assertion)
  }

  private def sendRequest[T: JsonWriter](method: String, params: T): Int = {
    val id = nextId()
    val msg = JsonRpcRequestMessage("2.0", id.toString, method, Converter.toJson(params).get)
    val json = Converter.toJson(msg).get
    svr.sendJsonRpc(CompactPrinter(json))
    if (method != "build/initialize") assertProcessing(method)
    id
  }
}
