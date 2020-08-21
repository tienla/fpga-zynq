// See LICENSE.SiFive for license details.

package rocketchip

import Chisel._
import config._
import coreplex._
import diplomacy._
import tile.XLen
import uncore.tilelink2._
import uncore.axi4._
import uncore.converters._
import uncore.devices._
import uncore.util._
import util._
import scala.math.max

/** Specifies the size of external memory */
case class MasterConfig(base: Long, size: Long, beatBytes: Int, idBits: Int)
case object ExtMem extends Field[MasterConfig]
case object ExtBus extends Field[MasterConfig]
case class SlaveConfig(beatBytes: Int, idBits: Int, sourceBits: Int)
case object ExtIn extends Field[SlaveConfig]
/** Specifies the number of external interrupts */
case object NExtTopInterrupts extends Field[Int]
/** Source of RTC. First bundle is TopIO.extra, Second bundle is periphery.io.extra  **/
case object RTCPeriod extends Field[Int]
/* Specifies the periphery bus configuration */
case object PeripheryBusConfig extends Field[TLBusConfig]
case object PeripheryBusArithmetic extends Field[Boolean]
/* Specifies the SOC-bus configuration */
case object SOCBusConfig extends Field[TLBusConfig]
/* Specifies the location of the Zero device */
case class ZeroConfig(base: Long, size: Long, beatBytes: Int)
case object ZeroConfig extends Field[ZeroConfig]

/** Utility trait for quick access to some relevant parameters */
trait HasPeripheryParameters {
  implicit val p: Parameters
  lazy val peripheryBusConfig = p(PeripheryBusConfig)
  lazy val socBusConfig = p(SOCBusConfig)
  lazy val cacheBlockBytes = p(CacheBlockBytes)
  lazy val peripheryBusArithmetic = p(PeripheryBusArithmetic)
}

/////

trait PeripheryExtInterrupts {
  this: TopNetwork =>

  val extInterrupts = IntBlindInputNode(p(NExtTopInterrupts))
  val extInterruptXing = LazyModule(new IntXing)

  intBus.intnode := extInterruptXing.intnode
  extInterruptXing.intnode := extInterrupts
}

trait PeripheryExtInterruptsBundle {
  this: TopNetworkBundle {
    val outer: PeripheryExtInterrupts
  } =>
  val interrupts = outer.extInterrupts.bundleIn
}

trait PeripheryExtInterruptsModule {
  this: TopNetworkModule {
    val outer: PeripheryExtInterrupts
    val io: PeripheryExtInterruptsBundle
  } =>
}

/////

trait PeripheryMasterAXI4Mem {
  this: TopNetwork =>
  val module: PeripheryMasterAXI4MemModule

  private val config = p(ExtMem)
  private val channels = p(BankedL2Config).nMemoryChannels
  private val lineBytes = p(CacheBlockBytes)

  val mem_axi4 = AXI4BlindOutputNode(Seq.tabulate(channels) { channel =>
    val base = AddressSet(config.base, config.size-1)
    val filter = AddressSet(channel * lineBytes, ~((channels-1) * lineBytes))

    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = base.intersect(filter).toList,
        regionType    = RegionType.UNCACHED,   // cacheable
        executable    = true,
        supportsWrite = TransferSizes(1, 256), // The slave supports 1-256 byte transfers
        supportsRead  = TransferSizes(1, 256),
        interleavedId = Some(0))),             // slave does not interleave read responses
      beatBytes = config.beatBytes)
  })

  private val converter = LazyModule(new TLToAXI4(config.idBits))
  private val buffer = LazyModule(new AXI4Buffer)

  mem foreach { case xbar =>
    converter.node := xbar.node
    buffer.node := converter.node
    mem_axi4 := buffer.node
  }
}

trait PeripheryMasterAXI4MemBundle {
  this: TopNetworkBundle {
    val outer: PeripheryMasterAXI4Mem
  } =>
  val mem_axi4 = outer.mem_axi4.bundleOut
}

trait PeripheryMasterAXI4MemModule {
  this: TopNetworkModule {
    val outer: PeripheryMasterAXI4Mem
    val io: PeripheryMasterAXI4MemBundle
  } =>
}

/////

trait PeripheryZero {
  this: TopNetwork =>
  val module: PeripheryZeroModule

  private val config = p(ZeroConfig)
  private val address = AddressSet(config.base, config.size-1)
  private val lineBytes = p(CacheBlockBytes)

  val zeros = mem map { case xbar =>
    val zero = LazyModule(new TLZero(address, beatBytes = config.beatBytes))
    zero.node := TLFragmenter(config.beatBytes, lineBytes)(xbar.node)
    zero
  }
}

trait PeripheryZeroBundle {
  this: TopNetworkBundle {
    val outer: PeripheryZero
  } =>
}

trait PeripheryZeroModule {
  this: TopNetworkModule {
    val outer: PeripheryZero
    val io: PeripheryZeroBundle
  } =>
}

/////

// PeripheryMasterAXI4MMIO is an example, make your own cake pattern like this one.
trait PeripheryMasterAXI4MMIO {
  this: TopNetwork =>

  private val config = p(ExtBus)
  val mmio_axi4 = AXI4BlindOutputNode(Seq(AXI4SlavePortParameters(
    slaves = Seq(AXI4SlaveParameters(
      address       = List(AddressSet(BigInt(config.base), config.size-1)),
      executable    = true,                  // Can we run programs on this memory?
      supportsWrite = TransferSizes(1, 256), // The slave supports 1-256 byte transfers
      supportsRead  = TransferSizes(1, 256),
      interleavedId = Some(0))),             // slave does not interleave read responses
    beatBytes = config.beatBytes)))

  mmio_axi4 :=
    AXI4Buffer()(
    // AXI4Fragmenter(lite=false, maxInFlight = 20)( // beef device up to support awlen = 0xff
    TLToAXI4(idBits = config.idBits)(      // use idBits = 0 for AXI4-Lite
    TLWidthWidget(socBusConfig.beatBytes)( // convert width before attaching to socBus
    socBus.node)))
}

trait PeripheryMasterAXI4MMIOBundle {
  this: TopNetworkBundle {
    val outer: PeripheryMasterAXI4MMIO
  } =>
  val mmio_axi4 = outer.mmio_axi4.bundleOut
}

trait PeripheryMasterAXI4MMIOModule {
  this: TopNetworkModule {
    val outer: PeripheryMasterAXI4MMIO
    val io: PeripheryMasterAXI4MMIOBundle
  } =>
  // nothing to do
}

/////

// PeripherySlaveAXI4 is an example, make your own cake pattern like this one.
trait PeripherySlaveAXI4 extends TopNetwork {
  private val config = p(ExtIn)
  val l2_axi4 = AXI4BlindInputNode(Seq(AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      id = IdRange(0, 1 << config.idBits))))))

  l2.node :=
    TLSourceShrinker(1 << config.sourceBits)(
    TLWidthWidget(config.beatBytes)(
    AXI4ToTL()(
    AXI4Fragmenter()(
    l2_axi4))))
}

trait PeripherySlaveAXI4Bundle extends TopNetworkBundle {
  val outer: PeripherySlaveAXI4
  val l2_axi4 = outer.l2_axi4.bundleIn
}

trait PeripherySlaveAXI4Module extends TopNetworkModule {
  val outer: PeripherySlaveAXI4
  val io: PeripherySlaveAXI4Bundle
  // nothing to do
}

/////

// Add an external TL-UL slave
trait PeripheryMasterTLMMIO {
  this: TopNetwork =>

  private val config = p(ExtBus)
  val mmio_tl = TLBlindOutputNode(Seq(TLManagerPortParameters(
    managers = Seq(TLManagerParameters(
      address            = List(AddressSet(BigInt(config.base), config.size-1)),
      executable         = true,
      supportsGet        = TransferSizes(1, cacheBlockBytes),
      supportsPutFull    = TransferSizes(1, cacheBlockBytes),
      supportsPutPartial = TransferSizes(1, cacheBlockBytes))),
    beatBytes = config.beatBytes)))

  mmio_tl :=
    TLBuffer()(
    TLSourceShrinker(config.idBits)(
    TLWidthWidget(socBusConfig.beatBytes)(
    socBus.node)))
}

trait PeripheryMasterTLMMIOBundle {
  this: TopNetworkBundle {
    val outer: PeripheryMasterTLMMIO
  } =>
  val mmio_tl = outer.mmio_tl.bundleOut
}

trait PeripheryMasterTLMMIOModule {
  this: TopNetworkModule {
    val outer: PeripheryMasterTLMMIO
    val io: PeripheryMasterTLMMIOBundle
  } =>
  // nothing to do
}

/////

// NOTE: this port is NOT allowed to issue Acquires
trait PeripherySlaveTL extends TopNetwork {
  private val config = p(ExtIn)
  val l2_tl = TLBlindInputNode(Seq(TLClientPortParameters(
    clients = Seq(TLClientParameters(
      sourceId = IdRange(0, 1 << config.idBits))))))

  l2.node :=
    TLSourceShrinker(1 << config.sourceBits)(
    TLWidthWidget(config.beatBytes)(
    l2_tl))
}

trait PeripherySlaveTLBundle extends TopNetworkBundle {
  val outer: PeripherySlaveTL
  val l2_tl = outer.l2_tl.bundleIn
}

trait PeripherySlaveTLModule extends TopNetworkModule {
  val outer: PeripherySlaveTL
  val io: PeripherySlaveTLBundle
  // nothing to do
}

/////

trait PeripheryBootROM {
  this: TopNetwork =>
  val coreplex: CoreplexRISCVPlatform

 private val bootrom_address = 0x1000
  private val bootrom_size = 0x1000

  // Currently RC emits a config string that uses a name for offchip memory
  // that does not comform to PK's expectation. So we prepend what we want.
  private lazy val configString = {
    val sb = new StringBuilder
    sb append "ram {\n"
    sb append "  0 {\n"
    sb append "  addr 0x%x;\n".format(p(ExtMem).base)
    sb append "  size 0x%x;\n".format(p(ExtMem).size)
    sb append "  };\n"
    sb append "};\n"
    sb append coreplex.configString
    val configString = sb.toString

    println(s"\nBIANCOLIN'S HACK: Generated Configuration String\n${configString}")
    _root_.util.ElaborationArtefacts.add("cfg", configString)
    configString
  }

  private lazy val bootrom_contents = GenerateBootROM(p, bootrom_address, configString)
  val bootrom = LazyModule(new TLROM(bootrom_address, bootrom_size, bootrom_contents, true, peripheryBusConfig.beatBytes))
  bootrom.node := TLFragmenter(peripheryBusConfig.beatBytes, cacheBlockBytes)(peripheryBus.node)
}

trait PeripheryBootROMBundle {
  this: TopNetworkBundle {
    val outer: PeripheryBootROM
  } =>
}

trait PeripheryBootROMModule {
  this: TopNetworkModule {
    val outer: PeripheryBootROM
    val io: PeripheryBootROMBundle
  } =>
}

/////

trait PeripheryTestRAM {
  this: TopNetwork =>

  val testram = LazyModule(new TLRAM(AddressSet(0x52000000, 0xfff), true, peripheryBusConfig.beatBytes))
  testram.node := TLFragmenter(peripheryBusConfig.beatBytes, cacheBlockBytes)(peripheryBus.node)
}

trait PeripheryTestRAMBundle {
  this: TopNetworkBundle {
    val outer: PeripheryTestRAM
  } =>
}

trait PeripheryTestRAMModule {
  this: TopNetworkModule {
    val outer: PeripheryTestRAM
    val io: PeripheryTestRAMBundle
  } =>
}

/////

trait PeripheryTestBusMaster {
  this: TopNetwork =>
  val fuzzer = LazyModule(new TLFuzzer(5000))
  peripheryBus.node := fuzzer.node
}

trait PeripheryTestBusMasterBundle {
  this: TopNetworkBundle {
    val outer: PeripheryTestBusMaster
  } =>
}

trait PeripheryTestBusMasterModule {
  this: TopNetworkModule {
    val outer: PeripheryTestBusMaster
    val io: PeripheryTestBusMasterBundle
  } =>
}
