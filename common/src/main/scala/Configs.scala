package zynq

import config.{Parameters, Config}
import diplomacy.LazyModule
import junctions.{NastiKey, NastiParameters}
import tile._
import coreplex._
import uncore.tilelink._
import uncore.coherence._
import uncore.agents._
import uncore.devices.NTiles
import rocketchip._
import rocket._
import testchipip._
import Chisel._

class WithZynqAdapter extends Config((site, here, up) => {
    //L2 Memory System Params
    case AmoAluOperandBits => site(XLen)
    case NAcquireTransactors => 7
    case L2StoreDataQueueDepth => 1
    case BuildRoCC => Nil
    case CacheBlockOffsetBits => chisel3.util.log2Up(site(CacheBlockBytes))
    case TLKey("L1toL2") =>
      val nTiles = site(NTiles)
      val useMEI = nTiles <= 1
      val dir = new NullRepresentation(nTiles)
      TileLinkParameters(
        coherencePolicy = if (useMEI) new MEICoherence(dir) else new MESICoherence(dir),
        nManagers = site(BankedL2Config).nBanks + 1 /* MMIO */,
        nCachingClients = 1,
        nCachelessClients = 1,
        maxClientXacts = List(
          // L1 cache
          site(RocketTilesKey).head.dcache.get.nMSHRs + 1 /* IOMSHR */,
          // RoCC
          if (site(BuildRoCC).isEmpty) 1 else site(RoccMaxTaggedMemXacts)).max,
        maxClientsPerPort = if (site(BuildRoCC).isEmpty) 1 else 2,
        maxManagerXacts = site(NAcquireTransactors) + 2,
        dataBeats = (8 * site(CacheBlockBytes)) / site(XLen),
        dataBits = (8 * site(CacheBlockBytes))
      )

    case SerialInterfaceWidth => 32
    case SerialFIFODepth => 16
    case ResetCycles => 10
    case NExtTopInterrupts => 0
    // This should be removed once adapter is TL2
    case TLId => "L1toL2"
    // PS slave interface
    case NastiKey => NastiParameters(32, 32, 12)
    // To PS Memory System / MIG
    case ExtMem => MasterConfig(base=0x80000000L, size=0x10000000L, beatBytes=8, idBits=6)
  })

class NoBrPred extends Config((here, site, up) => {
    case boom.BoomKey => up(boom.BoomKey).copy(
      enableBranchPredictor = false)
  })

class ZynqConfig extends Config(new WithZynqAdapter ++ new WithNBigCores(1) ++ new DefaultFPGAConfig)
class ZC706MIGConfig extends Config(new WithExtMemSize(0x40000000L) ++ new ZynqConfig)
class BOOMZynqConfig extends Config(new WithExtMemSize(0x40000000L) ++ new WithZynqAdapter ++ new boom.MediumBoomConfig)
class SmallBOOMZynqConfig extends Config(new WithZynqAdapter ++ new boom.SmallBoomConfig)

/*
class WithIntegrationTest extends Config((here, site, up) => {
    case BuildSerialDriver =>
      (p: Parameters) => Module(new IntegrationTestSerial()(p))
    case BuildTiles => Seq.fill(site(NTiles)) {
      (_reset: Bool, p: Parameters) => Module(new DummyTile()(p.alterPartial({
        case TileId => 0
        case TLId => "L1toL2"
        case NUncachedTileLinkPorts => 1
      })))
    }
  })

class IntegrationTestConfig extends Config(new WithIntegrationTest ++ new ZynqConfig)
*/
