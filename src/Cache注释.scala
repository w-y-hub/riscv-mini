// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._
import junctions._

/**
 * CPU 侧 cache 访存请求 Bundle。
 *
 * @param addrWidth  地址位宽（riscv-mini 中为 xlen，即 32）
 * @param dataWidth  数据位宽（与 CPU 字长相同，32 位）
 *
 * 字段含义：
 *   - addr：访存地址（取指地址或 load/store 有效地址）
 *   - data：写数据（store 时由 CPU 提供；读请求时通常忽略）
 *   - mask：字节写掩码，每位对应 1 字节；全 0 表示读，非 0 表示写
 */
class CacheReq(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val mask = UInt((dataWidth / 8).W)
}

/**
 * CPU 侧 cache 读响应 Bundle。
 *
 * @param dataWidth  返回数据的位宽
 *
 * 字段含义：
 *   - data：读到的字数据（已按地址偏移选出的 32 位）
 */
class CacheResp(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}

/**
 * Cache 与 CPU（Datapath）之间的完整 IO 接口。
 *
 * @param addrWidth  地址位宽
 * @param dataWidth  数据位宽
 *
 * 字段含义：
 *   - abort：输入，高电平表示 CPU 发生异常，中止当前 cache 事务（如 D$ 在 expt 时拉高）
 *   - req：   CPU 发起的 Valid 请求（addr/data/mask）
 *   - resp：  Cache 返回的 Valid 响应（data）；valid 为真时 CPU 可消费数据并解除 stall
 */
class CacheIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  // Input：声明该信号相对于「实例化此 Bundle 的模块（Cache）」为输入，由 CPU/Datapath 驱动
  // Bool：1 位布尔硬件信号（true/false），此处表示是否中止当前 cache 事务
  val abort = Input(Bool())
  // Flipped：翻转内层 Bundle 中各信号的默认方向；配合 Valid 使 req 成为 Cache 的输入侧
  // Valid：带有效位的握手接口，包含 req.valid（请求是否有效）与 req.bits（CacheReq 载荷）
  //        当 valid=1 时，bits 中的 addr/data/mask 才被 Cache 采信
  val req = Flipped(Valid(new CacheReq(addrWidth, dataWidth)))
  // Valid（未 Flipped）：相对 Cache 为输出；resp.valid=1 时 resp.bits.data 为可读数据
  val resp = Valid(new CacheResp(dataWidth))
}

/**
 * Cache 模块顶层 IO：一侧接 CPU，一侧接 AXI4（NASTI）主存总线。
 *
 * @param nastiParams  AXI 总线参数（地址/数据/ID 位宽）
 * @param addrWidth    CPU 侧地址位宽
 * @param dataWidth    CPU 侧数据位宽
 *
 * 字段含义：
 *   - cpu：   与 Core/Datapath 的 cache 接口
 *   - nasti：与片外存储器通信的 AXI4 Master 接口（AR/R 读，AW/W/B 写）
 */
class CacheModuleIO(nastiParams: NastiBundleParameters, addrWidth: Int, dataWidth: Int) extends Bundle {
  val cpu = new CacheIO(addrWidth, dataWidth)
  val nasti = new NastiBundle(nastiParams)
}

/**
 * Cache 容量与组织参数（case class，仅用于 Scala 配置，不生成硬件）。
 *
 * @param nWays      相联度；riscv-mini 默认为 1（直接映射 cache）
 * @param nSets      组（set）数量，即 cache 行数；默认 256
 * @param blockBytes 每个 cache 块的字节数；默认 16B（4 个 32 位字）
 */
case class CacheConfig(nWays: Int, nSets: Int, blockBytes: Int)

/**
 * 每个 cache 行对应的元数据（仅存 tag，valid/dirty 由独立寄存器维护）。
 *
 * @param tagLength  tag 字段位宽 = 地址位宽 - index 位宽 - block offset 位宽
 *
 * 字段含义：
 *   - tag：主存块地址的高位，用于与当前访问地址的 tag 比较以判断命中
 */
class MetaData(tagLength: Int) extends Bundle {
  val tag = UInt(tagLength.W)
}

/**
 * Cache 控制器有限状态机状态枚举。
 *
 * 状态含义：
 *   - sIdle：         空闲，等待 CPU 请求
 *   - sReadCache：    处理读请求（取指 / load），检查命中
 *   - sWriteCache：   处理写请求（store），检查命中或等待 refill 后写
 *   - sWriteBack：    未命中且该行 dirty，向主存写回旧 cache 块（W 通道）
 *   - sWriteAck：     等待主存写响应（B 通道）
 *   - sRefillReady：  写回完成后，准备发起读缺失填充（AR 通道）
 *   - sRefill：       从主存读入新 cache 块（R 通道）
 */
object CacheState extends ChiselEnum {
  val sIdle, sReadCache, sWriteCache, sWriteBack, sWriteAck, sRefillReady, sRefill = Value
}

/**
 * 写回（write-back）策略的直接映射 Cache 模块。
 *
 * 在 Tile 中实例化两次：icache（取指）与 dcache（load/store）。
 * 命中时单周期（或流水线约定周期）响应 CPU；未命中时通过 AXI 与主存交换整个 cache 块。
 *
 * @param p      Cache 组织参数（组数、块大小、相联度）
 * @param nasti  AXI4 总线位宽参数
 * @param xlen   CPU 字长（32），用于地址切分与数据宽度
 *
 * IO（通过 io 端口）：
 *   - io.cpu：  与 Datapath 的请求/响应
 *   - io.nasti：与 Arbiter 转发的 AXI 主存接口
 */
class Cache(val p: CacheConfig, val nasti: NastiBundleParameters, val xlen: Int) extends Module {
  // ---------------------------------------------------------------------------
  // 派生参数：由 CacheConfig 与 xlen 计算地址切分与存储尺寸
  // ---------------------------------------------------------------------------
  val nSets = p.nSets                          // 组数，如 256
  val bBytes = p.blockBytes                    // 块字节数，如 16
  val bBits = bBytes << 3                      // 块位数 = bBytes * 8
  val blen = log2Ceil(bBytes)                  // 块内字节偏移位宽（index 以下的低位）
  val slen = log2Ceil(nSets)                   // index 位宽（log2(组数)）
  val tlen = xlen - (slen + blen)              // tag 位宽
  val nWords = bBits / xlen                    // 每块包含的 CPU 字数（如 16B/4B = 4）
  val wBytes = xlen / 8                        // 每字字节数（4）
  val byteOffsetBits = log2Ceil(wBytes)        // 字内字节偏移位宽（2，用于 sb/sh 对齐）
  val dataBeats = bBits / nasti.dataBits       //  refill/写回时 AXI 突发传输的 beat 数

  val io = IO(new CacheModuleIO(nasti, addrWidth = xlen, dataWidth = xlen))

  // ---------------------------------------------------------------------------
  // 状态机与 SRAM 存储
  // ---------------------------------------------------------------------------
  import CacheState._
  val state = RegInit(sIdle)                   // 当前 FSM 状态

  // v[i]=1 表示第 i 组 cache 行有效；d[i]=1 表示该行被写过、写回主存前为 dirty
  val v = RegInit(0.U(nSets.W))
  val d = RegInit(0.U(nSets.W))
  val metaMem = SyncReadMem(nSets, new MetaData(tlen))   // 每组一行 tag
  // 数据区：每块拆成 nWords 个 SyncReadMem，每个存 wBytes 字节（便于按字节 mask 写）
  val dataMem = Seq.fill(nWords)(SyncReadMem(nSets, Vec(wBytes, UInt(8.W))))

  // 锁存当前 CPU 事务的地址、写数据、写掩码（在 resp.valid 时采样）
  val addr_reg = Reg(chiselTypeOf(io.cpu.req.bits.addr))
  val cpu_data = Reg(chiselTypeOf(io.cpu.req.bits.data))
  val cpu_mask = Reg(chiselTypeOf(io.cpu.req.bits.mask))

  // ---------------------------------------------------------------------------
  // AXI 突发计数器：一次传输整个 cache 块需多个 beat
  // ---------------------------------------------------------------------------
  require(dataBeats > 0)
  // read_count：当前 refill 已收到的 R beat 序号；read_wrap_out：收满一块时脉冲
  val (read_count, read_wrap_out) = Counter(io.nasti.r.fire, dataBeats)
  // write_count：写回主存时已发送的 W beat 序号；write_wrap_out：发满一块时脉冲
  val (write_count, write_wrap_out) = Counter(io.nasti.w.fire, dataBeats)

  // 状态判断辅助信号
  val is_idle = state === sIdle
  val is_read = state === sReadCache
  val is_write = state === sWriteCache
  // is_alloc：refill 最后一个 beat 到达，即将把主存数据装入 cache
  val is_alloc = state === sRefill && read_wrap_out
  val is_alloc_reg = RegNext(is_alloc)         // 延迟一拍，与写通路时序对齐

  // ---------------------------------------------------------------------------
  // 读写使能与命中判断
  // ---------------------------------------------------------------------------
  val hit = Wire(Bool())
  // wen：写 cache 数据/元数据（命中写、refill 装填、或 alloc 时刻）
  val wen = is_write && (hit || is_alloc_reg) && !io.cpu.abort || is_alloc
  // ren：读 cache（空闲或读状态且有 CPU 请求，且本周期不写）
  val ren = !wen && (is_idle || is_read) && io.cpu.req.valid
  val ren_reg = RegNext(ren)                   // 读使能延迟一拍，配合 SyncReadMem 读延迟

  // 地址切分：当前请求 addr vs 已锁存 addr_reg
  val addr = io.cpu.req.bits.addr
  val idx = addr(slen + blen - 1, blen)        // 当前请求的 index（选 set）
  val tag_reg = addr_reg(xlen - 1, slen + blen) // 锁存地址的 tag
  val idx_reg = addr_reg(slen + blen - 1, blen) // 锁存地址的 index
  val off_reg = addr_reg(blen - 1, byteOffsetBits) // 块内字索引（选 4 字中的哪一个）

  // SyncReadMem 读：下一拍数据可用
  val rmeta = metaMem.read(idx, ren)
  val rdata = Cat((dataMem.map(_.read(idx, ren).asUInt)).reverse) // 拼接整块数据
  val rdata_buf = RegEnable(rdata, ren_reg)    // 读出的整块数据缓冲
  val refill_buf = Reg(Vec(dataBeats, UInt(nasti.dataBits.W))) // 主存 refill 暂存
  // read：供 CPU 读 mux 使用的 128/块宽数据（来自 cache、缓冲或 refill）
  val read = Mux(is_alloc_reg, refill_buf.asUInt, Mux(ren_reg, rdata, rdata_buf))

  // 命中条件：该行 valid 且 tag 与当前访问 tag 一致
  hit := v(idx_reg) && rmeta.tag === tag_reg

  // ---------------------------------------------------------------------------
  // CPU 读响应通路
  // ---------------------------------------------------------------------------
  // 从整块 read 中按 off_reg 选出 32 位字返回 CPU
  io.cpu.resp.bits.data := VecInit.tabulate(nWords)(i => read((i + 1) * xlen - 1, i * xlen))(off_reg)
  // resp.valid：空闲可接受新读、读命中、或 refill 完成后的读；写 miss 路径在 alloc 后若 mask 非零则暂不 valid
  io.cpu.resp.valid := is_idle || is_read && hit || is_alloc_reg && !cpu_mask.orR

  // 在响应有效时锁存本拍 CPU 请求信息，供后续写/缺失处理使用
  when(io.cpu.resp.valid) {
    addr_reg := addr
    cpu_data := io.cpu.req.bits.data
    cpu_mask := io.cpu.req.bits.mask
  }

  // ---------------------------------------------------------------------------
  // Cache 写通路（CPU store 命中写 或 refill 装填）
  // ---------------------------------------------------------------------------
  val wmeta = Wire(new MetaData(tlen))
  wmeta.tag := tag_reg

  // wmask：CPU 写字时把字内字节掩码扩展到整块；refill 时全块写入
  val wmask = Mux(!is_alloc, (cpu_mask << Cat(off_reg, 0.U(byteOffsetBits.W))).zext, (-1).S)
  // wdata：CPU 写时把 32 位数据复制到块内各字位置；refill 时拼接 AXI R 数据
  val wdata = Mux(
    !is_alloc,
    Fill(nWords, cpu_data),
    if (refill_buf.size == 1) io.nasti.r.bits.data
    else Cat(io.nasti.r.bits.data, Cat(refill_buf.init.reverse))
  )
  when(wen) {
    v := v.bitSet(idx_reg, true.B)             // 该行变为 valid
    d := d.bitSet(idx_reg, !is_alloc)          // CPU 写则置 dirty；纯 refill 不置 dirty
    when(is_alloc) {
      metaMem.write(idx_reg, wmeta)            // refill 时更新 tag
    }
    dataMem.zipWithIndex.foreach {
      case (mem, i) =>
        val data = VecInit.tabulate(wBytes)(k => wdata(i * xlen + (k + 1) * 8 - 1, i * xlen + k * 8))
        mem.write(idx_reg, data, wmask((i + 1) * wBytes - 1, i * wBytes).asBools)
        mem.suggestName(s"dataMem_${i}")
    }
  }

  // ---------------------------------------------------------------------------
  // AXI 读通道（AR / R）：miss 时从主存 refill
  // ---------------------------------------------------------------------------
  // AR：读地址 = {tag, idx, block_offset=0}，突发长度 dataBeats
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

  // ---------------------------------------------------------------------------
  // AXI 写通道（AW / W / B）：dirty 行写回主存
  // ---------------------------------------------------------------------------
  // AW：写地址使用 cache 中旧 tag（rmeta.tag）与 idx_reg
  io.nasti.aw.bits := NastiAddressBundle(nasti)(
    0.U,
    (Cat(rmeta.tag, idx_reg) << blen.U).asUInt,
    log2Up(nasti.dataBits / 8).U,
    (dataBeats - 1).U
  )
  io.nasti.aw.valid := false.B
  // W：按 write_count 从 cache 读出块数据逐 beat 发送
  io.nasti.w.bits := NastiWriteDataBundle(nasti)(
    VecInit.tabulate(dataBeats)(i => read((i + 1) * nasti.dataBits - 1, i * nasti.dataBits))(write_count),
    None,
    write_wrap_out
  )
  io.nasti.w.valid := false.B
  io.nasti.b.ready := false.B

  // ---------------------------------------------------------------------------
  // Cache 主状态机（FSM）
  // ---------------------------------------------------------------------------
  val is_dirty = v(idx_reg) && d(idx_reg)      // 当前行需先写回再 refill
  switch(state) {
    is(sIdle) {
      // 收到 CPU 请求：mask 非零走写路径，否则走读路径
      when(io.cpu.req.valid) {
        state := Mux(io.cpu.req.bits.mask.orR, sWriteCache, sReadCache)
      }
    }
    is(sReadCache) {
      when(hit) {
        // 读命中：若仍有新请求则继续读/写状态，否则回空闲
        when(io.cpu.req.valid) {
          state := Mux(io.cpu.req.bits.mask.orR, sWriteCache, sReadCache)
        }.otherwise {
          state := sIdle
        }
      }.otherwise {
        // 读未命中：dirty 则先发 AW 写回，否则直接 AR refill
        io.nasti.aw.valid := is_dirty
        io.nasti.ar.valid := !is_dirty
        when(io.nasti.aw.fire) {
          state := sWriteBack
        }.elsewhen(io.nasti.ar.fire) {
          state := sRefill
        }
      }
    }
    is(sWriteCache) {
      // 写命中、refill 后写、或 CPU abort：完成写并回空闲
      when(hit || is_alloc_reg || io.cpu.abort) {
        state := sIdle
      }.otherwise {
        // 写未命中：同样可能需要先写回再 refill
        io.nasti.aw.valid := is_dirty
        io.nasti.ar.valid := !is_dirty
        when(io.nasti.aw.fire) {
          state := sWriteBack
        }.elsewhen(io.nasti.ar.fire) {
          state := sRefill
        }
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
        // refill 完成：若原是写 miss（cpu_mask 非零）则进入写 cache，否则回空闲
        state := Mux(cpu_mask.orR, sWriteCache, sIdle)
      }
    }
  }
}
