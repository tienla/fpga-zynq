//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Two-bit Counter Table
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2016 Sept 27
//
// Provide a two-bit counter table for use by branch predictors. Should only be
// updated during Commit.
//
//    P | H
//    1 | 1 strongly taken
//    1 | 0 weakly taken
//    0 | 1 weakly not-taken
//    0 | 0 strongly not-taken
//
// To reduce port access requirements, the two-bit state machine is not actually
// a counter - a misprediction when in the weak state pushes the counter into
// the strong state for the correct prediction.
//
//    Example: (00 -> 01 -> 11) for a string of taken branches.
//
// P-bits
//    Read: every cycle for a prediction.
//    Write: on a branch misprediction (with the h-bit value).
//
// H-bits
//    Read: on a branch misprediction.
//    Write: on a branch resolution (with the direction of the branch).
//
//
// TODO:
//    - Support having a smaller h-table (map 1 h-bit entry to multiple p-bit entries).
//    - Don't read the p-table SRAM if stalled (need extra state to store data
//       while stalled)..


package boom

import Chisel._

class UpdateEntry(fetch_width: Int, index_sz: Int) extends Bundle
{
   val index            = UInt(width = index_sz)
   val executed         = Vec(fetch_width, Bool())
   val takens           = Vec(fetch_width, Bool())
   // Was there a misprediction? If yes, we need to read the h-tables.
   val was_mispredicted = Bool()
   // Are we initializing this entry? If yes, we need to write directly to both P and H-tables.
   // If takens(i), then we initialize entry to Weak-Taken. Otherwise, Weak-NotTaken.
   val do_initialize    = Bool()

   override def cloneType: this.type = new UpdateEntry(fetch_width, index_sz).asInstanceOf[this.type]
}


class BrTableUpdate(fetch_width: Int, index_sz: Int) extends Bundle
{
   val index      = UInt(width = index_sz)
   val executed   = UInt(width = fetch_width) // which words in the fetch packet does the update correspond to?
   val new_value  = UInt(width = fetch_width)

   override def cloneType: this.type = new BrTableUpdate(fetch_width, index_sz).asInstanceOf[this.type]
}


// Read p-table every cycle for a prediction.
// Write p-table only if a misprediction occurs.
// The p-table requires 1read/1write port.
abstract class PTable(
   fetch_width: Int,
   num_entries: Int
   ) extends Module
{
   val index_sz = log2Up(num_entries)
   val io = new Bundle
   {
      val s0_r_idx = UInt(INPUT, width = index_sz)
      val s2_r_out = UInt(OUTPUT, width = fetch_width)
      val stall    = Bool(INPUT)
      val update   = Decoupled(new BrTableUpdate(fetch_width, index_sz)).flip
   }

   val ridx = Wire(UInt())
   val last_idx = RegNext(ridx)
   ridx := Mux(io.stall, last_idx, io.s0_r_idx)
}

// This version uses 1 read and 1 write port.
class PTableDualPorted(
   fetch_width: Int,
   num_entries: Int
   ) extends PTable(fetch_width, num_entries)
{
   val p_table = SeqMem(num_entries, Vec(fetch_width, Bool()))

   io.update.ready := Bool(true)

   when (io.update.valid)
   {
      val waddr = io.update.bits.index
      val wdata = Vec(io.update.bits.new_value.toBools)
      val wmask = io.update.bits.executed.toBools
      p_table.write(waddr, wdata, wmask)
   }

   io.s2_r_out := RegEnable(p_table.read(this.ridx).toBits, !io.stall)
}

// Read p-table every cycle for a prediction.
// Write p-table only if a misprediction occurs.
// The p-table requires 1read/1write port.
// This version banks the table to get by with a single 1rw port.
class PTableBanked(
   fetch_width: Int,
   num_entries: Int
   ) extends PTable(fetch_width, num_entries)
{
//   val p_table_0 = SeqMem(num_entries/2, Vec(fetch_width, Bool()))
//   val p_table_1 = SeqMem(num_entries/2, Vec(fetch_width, Bool()))
   val p_table_0 = Module(new SeqMem1rwTransformable(num_entries/2, fetch_width))
   val p_table_1 = Module(new SeqMem1rwTransformable(num_entries/2, fetch_width))

   private def getBank (idx: UInt): UInt = idx(0)
   private def getRowIdx (idx: UInt): UInt = idx >> UInt(1)

   val widx = io.update.bits.index
   val rbank = getBank(ridx)
   val wbank = getBank(widx)
   io.update.ready := rbank =/= wbank

   val ren_0   = rbank === UInt(0)
   val ren_1   = rbank === UInt(1)
//   val rout_0  = RegEnable(p_table_0.read(getRowIdx(ridx), ren_0).toBits, !io.stall)
//   val rout_1  = RegEnable(p_table_1.read(getRowIdx(ridx), ren_1).toBits, !io.stall)
   val wdata   = Vec(io.update.bits.new_value.toBools)
   val wmask = io.update.bits.executed.toBools

//   when (!ren_0 && wbank === UInt(0) && io.update.valid)
//   {
//      p_table_0.write(getRowIdx(widx), wdata, wmask)
//   }
//   when (!ren_1 && wbank === UInt(1) && io.update.valid)
//   {
//      p_table_1.write(getRowIdx(widx), wdata, wmask)
//   }

   // ** use resizable SeqMems ** //
   p_table_0.io.wen   := !ren_0 && wbank === UInt(0) && io.update.valid
   p_table_0.io.waddr := getRowIdx(widx)
   p_table_0.io.wmask := Vec(wmask).toBits
   p_table_0.io.wdata := wdata.toBits
   p_table_1.io.wen   := !ren_1 && wbank === UInt(1) && io.update.valid
   p_table_1.io.waddr := getRowIdx(widx)
   p_table_1.io.wmask := Vec(wmask).toBits
   p_table_1.io.wdata := wdata.toBits

   p_table_0.io.ren   := ren_0
   p_table_0.io.raddr := getRowIdx(ridx)
   p_table_1.io.ren   := ren_1
   p_table_1.io.raddr := getRowIdx(ridx)
   val rout_0 = RegEnable(p_table_0.io.rout, !io.stall)
   val rout_1 = RegEnable(p_table_1.io.rout, !io.stall)

   val s2_ren = RegEnable(RegEnable(ren_0, !io.stall), !io.stall)
   io.s2_r_out := Mux(s2_ren, rout_0, rout_1)
}


// Write h-table for every branch resolution (we can buffer these up).
// Read h-table immediately to update the p-table (only if a mispredict occurred).
class HTable(
   fetch_width: Int,
   num_entries: Int
   ) extends Module
{
   private val index_sz = log2Up(num_entries)
   val io = new Bundle
   {
      // Update the h-table.
      val update   = Valid(new UpdateEntry(fetch_width, index_sz)).flip
      // Enqueue an update to the p-table.
      val pwq_enq  = Decoupled(new BrTableUpdate(fetch_width, index_sz))
   }

//   val h_table = SeqMem(num_entries, Vec(fetch_width, Bool()))
   val h_table = Module(new SeqMem1rwTransformable(num_entries, fetch_width))
   val hwq = Module(new Queue(new UpdateEntry(fetch_width, index_sz), entries=4))

   hwq.io.enq <> io.update

   val h_ren = io.update.valid && io.update.bits.was_mispredicted && !io.update.bits.do_initialize
   hwq.io.deq.ready := !h_ren
   //when (!h_ren && hwq.io.deq.valid)
   //{
   //   val waddr = hwq.io.deq.bits.index
   //   val wmask = hwq.io.deq.bits.executed
   //   // if initializing, set to weak state.
   //   val wdata = Vec(hwq.io.deq.bits.takens.map(t =>
   //                  Mux(hwq.io.deq.bits.do_initialize, !t, t)))
   //   h_table.write(waddr, wdata, wmask)
   //}
   h_table.io.wen   := !h_ren && hwq.io.deq.valid
   h_table.io.waddr := hwq.io.deq.bits.index
   h_table.io.wmask := hwq.io.deq.bits.executed.toBits
   h_table.io.wdata := Vec(hwq.io.deq.bits.takens.map(t =>
                        Mux(hwq.io.deq.bits.do_initialize, !t, t))).toBits

   val h_raddr = io.update.bits.index
   h_table.io.ren   := h_ren
   h_table.io.raddr := h_raddr
   val h_rout = h_table.io.rout
//   val h_rout = h_table.read(h_raddr, h_ren).toBits
   io.pwq_enq.valid          := RegNext(h_ren || io.update.bits.do_initialize)
   io.pwq_enq.bits.index     := RegNext(h_raddr)
   io.pwq_enq.bits.executed  := RegNext(io.update.bits.executed.toBits)
   io.pwq_enq.bits.new_value := Mux(RegNext(io.update.bits.do_initialize),
                                    RegNext(io.update.bits.takens.toBits),
                                    h_rout)
}


class TwobcCounterTable(
   fetch_width: Int,
   num_entries: Int,
   dualported: Boolean = false
   ) extends Module
{
   private val index_sz = log2Up(num_entries)
   val io = new Bundle
   {
      // send read addr on cycle 0, get data out on cycle 2.
      val s0_r_idx = UInt(INPUT, width = index_sz)
      val s2_r_out = UInt(OUTPUT, width = fetch_width)
      val stall    = Bool(INPUT)

      val update   = Valid(new UpdateEntry(fetch_width, index_sz)).flip
   }

   println ("\t\tBuilding (" +
      (num_entries * fetch_width * 2/8/1024) + " kB) 2-bit counter table for (" +
      fetch_width + "-wide fetch) and " +
      num_entries + " entries " +
      (if (dualported) "[1read/1write]." else "[1rw]."))

   //------------------------------------------------------------
   // prediction bits
   // hysteresis bits

   val p_table = if (dualported) Module(new PTableDualPorted(fetch_width, num_entries))
                 else            Module(new PTableBanked(fetch_width, num_entries))
   val h_table = Module(new HTable(fetch_width, num_entries))


   //------------------------------------------------------------
   // write queue from h-table to p-table.

   val pwq_entries = if (dualported) 2 else 6
   val pwq = Module(new Queue(new BrTableUpdate(fetch_width, index_sz), entries=pwq_entries))

   //------------------------------------------------------------
   // p-table
   // Read table every cycle for a prediction.
   // Write table only if a misprediction occurs.

   p_table.io.s0_r_idx <> io.s0_r_idx
   io.s2_r_out <> p_table.io.s2_r_out
   p_table.io.update <> pwq.io.deq
   p_table.io.stall := io.stall


   //------------------------------------------------------------
   // h-table
   // Write table for every branch resolution (we can buffer these up).
   // Read table immediately to update the p-table (only if a mispredict occurred).

   h_table.io.update <> io.update
   pwq.io.enq <> h_table.io.pwq_enq


   //------------------------------------------------------------
}

