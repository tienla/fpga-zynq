//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
package boom
{

import Chisel._
import rocket._
import tile._
import config.{Parameters, Field}

case object BoomKey extends Field[BoomCoreParams]

case class BoomCoreParams(
   issueWidth: Int = 1,
   numRobEntries: Int = 16,
   numIssueSlotEntries: Int = 12,
   numLsuEntries: Int = 8,
   numPhysRegisters: Int = 110,
   maxBrCount: Int = 4,
   fetchBufferSz: Int = 4,
   enableAgePriorityIssue: Boolean = true,
   enablePrefetching: Boolean = false,
   enableFetchBufferFlowThrough: Boolean = false,
   enableBrResolutionRegister: Boolean = true,
   enableCommitMapTable: Boolean = false,
   enableBTBContainsBranches: Boolean = true,
   enableBranchPredictor: Boolean = true,
   enableBpdUModeOnly: Boolean = false,
   enableBpdUSModeHistory: Boolean = false,
   tage: Option[TageParameters] = None,
   gshare: Option[GShareParameters] = None,
   gskew: Option[GSkewParameters] = None
)

trait HasBoomCoreParameters extends tile.HasCoreParameters
{
   // HACK this is a bit hacky since BoomParams can't extend RocketParams.
   val rocketParams: RocketCoreParams = tileParams.core.asInstanceOf[RocketCoreParams]
   val boomParams: BoomCoreParams = p(BoomKey)
   require(xLen == 64)

   val nPerfCounters    = rocketParams.nPerfCounters
   val nPerfEvents      = rocketParams.nPerfEvents
   //************************************
   // Superscalar Widths
   val FETCH_WIDTH      = rocketParams.fetchWidth       // number of insts we can fetch
   val DECODE_WIDTH     = rocketParams.decodeWidth
   val DISPATCH_WIDTH   = DECODE_WIDTH                // number of insts put into the IssueWindow
   val ISSUE_WIDTH      = boomParams.issueWidth
   val COMMIT_WIDTH     = rocketParams.retireWidth

   require (DECODE_WIDTH == COMMIT_WIDTH)
   require (DISPATCH_WIDTH == COMMIT_WIDTH)
   require (isPow2(FETCH_WIDTH))
   require (DECODE_WIDTH <= FETCH_WIDTH)

   //************************************
   // Data Structure Sizes
   val NUM_ROB_ENTRIES  = boomParams.numRobEntries     // number of ROB entries (e.g., 32 entries for R10k)
   val NUM_LSU_ENTRIES  = boomParams.numLsuEntries     // number of LD/ST entries
   val MAX_BR_COUNT     = boomParams.maxBrCount        // number of branches we can speculate simultaneously
   val PHYS_REG_COUNT   = boomParams.numPhysRegisters  // size of the unified, physical register file
   val FETCH_BUFFER_SZ  = boomParams.fetchBufferSz     // number of instructions that stored between fetch&decode


   val enableFetchBufferFlowThrough = boomParams.enableFetchBufferFlowThrough 

   //************************************
   // Functional Units
   val usingFDivSqrt = rocketParams.fpu.get.divSqrt

   val mulDivParams = rocketParams.mulDiv.getOrElse(MulDivParams())

   //************************************
   // Pipelining

   val IMUL_STAGES = rocketParams.fpu.get.dfmaLatency
   val dfmaLatency = rocketParams.fpu.get.dfmaLatency
   val sfmaLatency = rocketParams.fpu.get.sfmaLatency
   // All FPU ops padded out to same delay for writeport scheduling.
   require (sfmaLatency == dfmaLatency) 
   
   val enableBrResolutionRegister = boomParams.enableBrResolutionRegister
    
   //************************************
   // Issue Window
   
   val numIssueSlotEntries = boomParams.numIssueSlotEntries
   val enableAgePriorityIssue = boomParams.enableAgePriorityIssue 
    
   //************************************
   // Load/Store Unit
   val dcacheParams: DCacheParams = tileParams.dcache.get
   val nTLBEntries = dcacheParams.nTLBEntries

   //************************************
   // Branch Prediction

   val enableBTB = tileParams.btb.isDefined
   val btbParams: rocket.BTBParams = tileParams.btb.get

   val enableBTBContainsBranches = boomParams.enableBTBContainsBranches 

   val ENABLE_BRANCH_PREDICTOR = boomParams.enableBranchPredictor
   val ENABLE_BPD_UMODE_ONLY = boomParams.enableBpdUModeOnly
   val ENABLE_BPD_USHISTORY = boomParams.enableBpdUSModeHistory
   // What is the maximum length of global history tracked?
   var GLOBAL_HISTORY_LENGTH = 0
   // What is the physical length of the VeryLongHistoryRegister? This must be
   // able to handle the GHIST_LENGTH as well as being able hold all speculative
   // updates well beyond the GHIST_LENGTH (i.e., +ROB_SZ and other buffering).
   var VLHR_LENGTH = 0
   var BPD_INFO_SIZE = 0
   var ENABLE_VLHR = false

   val tageParams = boomParams.tage
   val gshareParams = boomParams.gshare
   val gskewParams = boomParams.gskew

   if (!ENABLE_BRANCH_PREDICTOR)
   {
      BPD_INFO_SIZE = 1
      GLOBAL_HISTORY_LENGTH = 1
   }
   else if (tageParams.isDefined && tageParams.get.enabled)
   {
      GLOBAL_HISTORY_LENGTH = tageParams.get.history_lengths.max
      BPD_INFO_SIZE = TageBrPredictor.GetRespInfoSize(p, fetchWidth)
      ENABLE_VLHR = true
   }
   else if (gskewParams.isDefined && gskewParams.get.enabled)
   {
      GLOBAL_HISTORY_LENGTH = gskewParams.get.history_length
      BPD_INFO_SIZE = GSkewBrPredictor.GetRespInfoSize(p, fetchWidth)
   }
   else if (gshareParams.isDefined && gshareParams.get.enabled)
   {
      GLOBAL_HISTORY_LENGTH = gshareParams.get.history_length
      BPD_INFO_SIZE = GShareBrPredictor.GetRespInfoSize(p, GLOBAL_HISTORY_LENGTH)
   }
   else if (p(SimpleGShareKey).enabled)
   {
      GLOBAL_HISTORY_LENGTH = p(SimpleGShareKey).history_length
      BPD_INFO_SIZE = SimpleGShareBrPredictor.GetRespInfoSize(p)
   }
   else if (p(RandomBpdKey).enabled)
   {
      GLOBAL_HISTORY_LENGTH = 1
      BPD_INFO_SIZE = RandomBrPredictor.GetRespInfoSize(p)
   }
   else
   {
      require(!ENABLE_BRANCH_PREDICTOR) // set branch predictor in configs.scala
      BPD_INFO_SIZE = 1
      GLOBAL_HISTORY_LENGTH = 1
   }
   VLHR_LENGTH = GLOBAL_HISTORY_LENGTH+2*NUM_ROB_ENTRIES


   //************************************
   // Extra Knobs and Features
   val ENABLE_REGFILE_BYPASSING  = true  // bypass regfile write ports to read ports
   val MAX_WAKEUP_DELAY = 3              // unused
   val ENABLE_COMMIT_MAP_TABLE = boomParams.enableCommitMapTable

   //************************************
   // Implicitly calculated constants
   val NUM_ROB_ROWS      = NUM_ROB_ENTRIES/DECODE_WIDTH
   val ROB_ADDR_SZ       = log2Up(NUM_ROB_ENTRIES)
   // the f-registers are mapped into the space above the x-registers
   val LOGICAL_REG_COUNT = if (usingFPU) 64 else 32
   val LREG_SZ           = log2Up(LOGICAL_REG_COUNT)
   val PREG_SZ           = log2Up(PHYS_REG_COUNT)
   val MEM_ADDR_SZ       = log2Up(NUM_LSU_ENTRIES)
   val MAX_ST_COUNT      = (1 << MEM_ADDR_SZ)
   val MAX_LD_COUNT      = (1 << MEM_ADDR_SZ)
   val BR_TAG_SZ         = log2Up(MAX_BR_COUNT)
   val NUM_BROB_ENTRIES  = NUM_ROB_ROWS //TODO explore smaller BROBs
   val BROB_ADDR_SZ      = log2Up(NUM_BROB_ENTRIES)

   require (PHYS_REG_COUNT >= (LOGICAL_REG_COUNT + DECODE_WIDTH))
   require (MAX_BR_COUNT >=2)
   require (NUM_ROB_ROWS % 2 == 0)
   require (NUM_ROB_ENTRIES % DECODE_WIDTH == 0)
   require (isPow2(NUM_LSU_ENTRIES))
   require ((NUM_LSU_ENTRIES-1) > DECODE_WIDTH)
 
 
   //************************************
   // Non-BOOM parameters

   val corePAddrBits = paddrBits
   val corePgIdxBits = pgIdxBits
}


}
