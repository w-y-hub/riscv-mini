package mini

import chisel3._
import chisel3.util._
import junctions._

class CacheReq(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val mask = UInt((dataWidth / 8).W)
}

class CacheResp(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}

class CacheIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val abort = Input(Bool())
  val req = Flipped(Valid(new CacheReq(addrWidth, dataWidth)))
  val resp = Valid(new CacheResp(dataWidth))
}

class CacheModuleIO(nastiParams: NastiBundleParameters, addrWidth: Int, dataWidth: Int) extends Bundle {
  val cpu = new CacheIO(addrWidth, dataWidth)
  val nasti = new NastiBundle(nastiParams)
}

case class CacheConfig(nWays: Int, nSets: Int, blockBytes: Int)

class MetaData(tagLength: Int) extends Bundle {
  val tag = UInt(tagLength.W)
}

object CacheState extends ChiselEnum {
  val sIdle, sReadCache, sWriteCache, sEvict, sWriteBack, sWriteAck, sRefillReady, sRefill = Value
}

class Cache(val p: CacheConfig, val nasti: NastiBundleParameters, val xlen: Int) extends Module {
  // local parameters
  val nSets = p.nSets
  val nWays = p.nWays
  val bBytes = p.blockBytes
  val bBits = bBytes << 3
  val blen = log2Ceil(bBytes)
  val slen = log2Ceil(nSets)
  val tlen = xlen - (slen + blen)
  val nWords = bBits / xlen
  val wBytes = xlen / 8
  val byteOffsetBits = log2Ceil(wBytes)
  val dataBeats = bBits / nasti.dataBits

  val io = IO(new CacheModuleIO(nasti, addrWidth = xlen, dataWidth = xlen))

  // cache states
  import CacheState._
  val state = RegInit(sIdle)

  // memory
  val v = RegInit(VecInit(Seq.fill(nWays)(0.U(nSets.W))))  // 每组每路各 1 个 valid
  val d = RegInit(VecInit(Seq.fill(nWays)(0.U(nSets.W))))  // 每组每路各 1 个 dirty
  val metaMem = Seq.fill(nWays)(SyncReadMem(nSets, new MetaData(tlen)))   // 每路各 1 个 tag SRAM
  val dataMem = Seq.fill(nWays)(Seq.fill(nWords)(SyncReadMem(nSets, Vec(wBytes, UInt(8.W))))) // [FIX] 补全括号

  val addr_reg = Reg(chiselTypeOf(io.cpu.req.bits.addr))
  val cpu_data = Reg(chiselTypeOf(io.cpu.req.bits.data))
  val cpu_mask = Reg(chiselTypeOf(io.cpu.req.bits.mask))

  // [FIX] 将 lru 提前到 alloc_way 之前
  // 替换策略 伪LRU算法，每组维护一位访问记录
  val lru = RegInit(VecInit(Seq.fill(nSets)(0.U(1.W)))) // 0表示路0最近使用，1表示路1最近使用

  // Counters
  require(dataBeats > 0)
  val (read_count, read_wrap_out) = Counter(io.nasti.r.fire, dataBeats)
  val (write_count, write_wrap_out) = Counter(io.nasti.w.fire, dataBeats)

  val is_idle = state === sIdle
  val is_read = state === sReadCache
  val is_write = state === sWriteCache
  val is_alloc = state === sRefill && read_wrap_out
  val is_alloc_reg = RegNext(is_alloc)

  val hit = Wire(Bool())
  val wen = is_write && (hit || is_alloc_reg) && !io.cpu.abort || is_alloc
  //将捕获条件改为使用一个统一的 miss 信号，该信号在 读或写 miss 时都有效
  val miss = !hit && (is_idle || is_read || is_write) && io.cpu.req.valid
  val ren = (!wen || miss) && (is_idle || is_read) && io.cpu.req.valid
  val ren_reg = RegNext(ren)

  val addr = io.cpu.req.bits.addr
  val idx = addr(slen + blen - 1, blen)
  val tag_reg = addr_reg(xlen - 1, slen + blen)
  val idx_reg = addr_reg(slen + blen - 1, blen)
  val off_reg = addr_reg(blen - 1, byteOffsetBits)

  // 分别读两路的tag和数据（组合输出）
  val rmeta0 = metaMem(0).read(idx, ren)
  val rmeta1 = metaMem(1).read(idx, ren)
  val rdata0 = Cat((dataMem(0).map(_.read(idx, ren).asUInt)).reverse)
  val rdata1 = Cat((dataMem(1).map(_.read(idx, ren).asUInt)).reverse)

  // 读数据缓冲
  val rdata0_buf = RegEnable(rdata0, ren_reg)
  val rdata1_buf = RegEnable(rdata1, ren_reg)
  val refill_buf = Reg(Vec(dataBeats, UInt(nasti.dataBits.W)))

  // 命中判断
  val hit0 = v(0)(idx_reg) && rmeta0.tag === tag_reg
  val hit1 = v(1)(idx_reg) && rmeta1.tag === tag_reg
  hit := hit0 || hit1
  val hit_way = Mux(hit0, 0.U, Mux(hit1, 1.U, 0.U)) // 命中路选择

  // [FIX] 重新组织 alloc_way 的计算，避免组合循环
  // 缺失时选择最近未使用的 way 替换
  val victim_way = Mux(!v(0)(idx_reg), 0.U, Mux(!v(1)(idx_reg), 1.U, Mux(lru(idx_reg) === 0.U, 1.U, 0.U)))  // 优先选无效路，都有效再用 LRU
  val alloc_way = Mux(hit, hit_way, victim_way)           // 实际写入的 way

  // [FIX] LRU 更新逻辑，与 alloc_way 同步更新，但不相互依赖
  when(io.cpu.req.valid) {
    when(hit) {
      lru(idx) := hit_way      // 命中时标记命中 way 为最近使用
    }.otherwise {
      lru(idx) := victim_way   // 缺失时标记 victim way 为最近使用（即刚被替换的 way）
    }
  }

  // 被选中替换的 way
  val evict_way = alloc_way
  // 被替换 way 的脏位
  val is_dirty_way0 = v(0)(idx_reg) && d(0)(idx_reg)
  val is_dirty_way1 = v(1)(idx_reg) && d(1)(idx_reg)
  val is_dirty = Mux(evict_way === 0.U, is_dirty_way0, is_dirty_way1)

  // 写回数据（被替换 way 的数据块）
  val evict_data = Mux(evict_way === 0.U, rdata0_buf, rdata1_buf)

  // 锁存被替换 way 的 tag、数据和 way 号（用于写回）
  val evict_tag_reg = Reg(UInt(tlen.W))
  val evict_data_reg = Reg(UInt(bBits.W))
  val evict_way_reg = Reg(UInt(1.W))

  // 实际写入的 way：refill 后合并 store 时复用锁存的 way，避免重新求值不一致
  val write_way = Mux(is_alloc_reg, evict_way_reg, alloc_way)

  // Read Mux：从命中 way 或 refill 数据中取数
  val read0 = Mux(is_alloc_reg, refill_buf.asUInt, Mux(ren_reg, rdata0, rdata0_buf))
  val read1 = Mux(is_alloc_reg, refill_buf.asUInt, Mux(ren_reg, rdata1, rdata1_buf))
  val way_read = Mux(hit_way === 0.U, read0, read1)
  io.cpu.resp.bits.data := VecInit.tabulate(nWords)(i => way_read((i + 1) * xlen - 1, i * xlen))(off_reg)

  // [FIX] 去除 is_idle，避免过早响应
  io.cpu.resp.valid := is_idle || (is_read && hit) || (is_alloc_reg && !cpu_mask.orR)

  when(io.cpu.req.valid) {
    addr_reg := addr
    cpu_data := io.cpu.req.bits.data
    cpu_mask := io.cpu.req.bits.mask
  }

  val wmeta = Wire(new MetaData(tlen))
  wmeta.tag := tag_reg

  val wmask = Mux(!is_alloc, (cpu_mask << Cat(off_reg, 0.U(byteOffsetBits.W))).zext, (-1).S)
  val wdata = Mux(
    !is_alloc,
    Fill(nWords, cpu_data),
    if (refill_buf.size == 1) io.nasti.r.bits.data
    else Cat(io.nasti.r.bits.data, Cat(refill_buf.init.reverse))
  )

  // 写入 cache（仅写选中的 way）
  when(wen) {
    when(write_way === 0.U) {
      v(0) := v(0).bitSet(idx_reg, true.B)
      d(0) := d(0).bitSet(idx_reg, !is_alloc)
      when(is_alloc) {
        metaMem(0).write(idx_reg, wmeta)
      }
      dataMem(0).zipWithIndex.foreach {
        case (mem, i) =>
          val data = VecInit.tabulate(wBytes)(k =>
            wdata(i * xlen + (k + 1) * 8 - 1, i * xlen + k * 8))
          mem.write(idx_reg, data, wmask((i + 1) * wBytes - 1, i * wBytes).asBools)
          // mem.suggestName(s"dataMem_0_${i}") // 可选，两路会重名，去掉或加不同前缀
      }
    }.otherwise {
      v(1) := v(1).bitSet(idx_reg, true.B)
      d(1) := d(1).bitSet(idx_reg, !is_alloc)
      when(is_alloc) {
        metaMem(1).write(idx_reg, wmeta)
      }
      dataMem(1).zipWithIndex.foreach {
        case (mem, i) =>
          val data = VecInit.tabulate(wBytes)(k =>
            wdata(i * xlen + (k + 1) * 8 - 1, i * xlen + k * 8))
          mem.write(idx_reg, data, wmask((i + 1) * wBytes - 1, i * wBytes).asBools)
          // mem.suggestName(s"dataMem_1_${i}")
      }
    }
  }

  // AXI read address
  io.nasti.ar.bits := NastiAddressBundle(nasti)(
    0.U,
    (Cat(tag_reg, idx_reg) << blen.U).asUInt,
    log2Up(nasti.dataBits / 8).U,
    (dataBeats - 1).U
  )
  io.nasti.ar.valid := false.B
  io.nasti.r.ready := state === sRefill
  when(io.nasti.r.fire) {
    refill_buf(read_count) := io.nasti.r.bits.data
  }

  // 写回地址：使用锁存的 evict_tag_reg
  io.nasti.aw.bits := NastiAddressBundle(nasti)(
    0.U,
    (Cat(evict_tag_reg, idx_reg) << blen.U).asUInt,
    log2Up(nasti.dataBits / 8).U,
    (dataBeats - 1).U
  )
  io.nasti.aw.valid := false.B

  // 写回数据：使用锁存的 evict_data_reg
  io.nasti.w.bits := NastiWriteDataBundle(nasti)(
    VecInit.tabulate(dataBeats)(i => evict_data_reg((i + 1) * nasti.dataBits - 1, i * nasti.dataBits))(write_count),
    None,
    write_wrap_out
  )
  io.nasti.w.valid := false.B

  // AXI write response
  io.nasti.b.ready := false.B

  // Cache FSM
  switch(state) {
    is(sIdle) {
      when(io.cpu.req.valid) {
        state := Mux(io.cpu.req.bits.mask.orR, sWriteCache, sReadCache)
      }
    }
    is(sReadCache) {
      when(hit) {
        when(io.cpu.req.valid) {
          state := Mux(io.cpu.req.bits.mask.orR, sWriteCache, sReadCache)
        }.otherwise {
          state := sIdle
        }
      }.elsewhen(miss && is_dirty) {
        evict_tag_reg := Mux(evict_way === 0.U, rmeta0.tag, rmeta1.tag)
        evict_data_reg := Mux(evict_way === 0.U, rdata0_buf, rdata1_buf)
        evict_way_reg := evict_way
        state := sEvict
      }.otherwise {
        io.nasti.ar.valid := true.B
        when(io.nasti.ar.fire) {
          state := sRefill
        }
      }
    }
    is(sWriteCache) {
      when(hit || is_alloc_reg || io.cpu.abort) {
        state := sIdle
      }.elsewhen(miss && is_dirty) {
        evict_tag_reg := Mux(evict_way === 0.U, rmeta0.tag, rmeta1.tag)
        evict_data_reg := Mux(evict_way === 0.U, rdata0_buf, rdata1_buf)
        evict_way_reg := evict_way
        state := sEvict
      }.otherwise {
        io.nasti.ar.valid := true.B
        when(io.nasti.ar.fire) {
          state := sRefill
        }
      }
    }
    is(sEvict) {
      io.nasti.aw.valid := true.B
      when(io.nasti.aw.fire) {
        state := sWriteBack
      }
    }
    is(sWriteBack) {
      io.nasti.w.valid := true.B
      when(write_wrap_out) {
        state := sWriteAck
      }
    }
    is(sWriteAck) {
      io.nasti.b.ready := true.B
      when(io.nasti.b.fire) {
        state := sRefillReady
      }
    }
    is(sRefillReady) {
      io.nasti.ar.valid := true.B
      when(io.nasti.ar.fire) {
        state := sRefill
      }
    }
    is(sRefill) {
      when(read_wrap_out) {
        state := Mux(cpu_mask.orR, sWriteCache, sIdle)
      }
    }
  }
}