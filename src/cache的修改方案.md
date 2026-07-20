## 1.代码整体情况
### 模块参数与IO
- p:CacheConfig(nWays,nSets,blockbytes) 
- nasti:NastiBundleParameters 
- xlen:Int  
- slen(组索引),blen(块偏移),tlen(tag总长度),nwords(块内字数量),字节偏移位宽等 

### 存储结构
```c
val v = RegInit(VecInit(Seq.fill(nWays)(0.U(nSets.W))))  // valid 位向量
val d = RegInit(VecInit(Seq.fill(nWays)(0.U(nSets.W))))  // dirty 位向量
val metaMem = Seq.fill(nWays)(SyncReadMem(nSets, ...))   // tag 存储
val dataMem = Seq.fill(nWays)(Seq.fill(nWords)(...))      // 数据存储
```

### 数据读取与命中判断
```c
val rmeta = metaMem.read(idx, ren)   // 实际只读取第一个 way？
val rdata = Cat((dataMem.map(_.read(idx, ren).asUInt)).reverse)
...
hit := v(idx_reg) && rmeta.tag === tag_reg
```

### 更新逻辑
```c
when(wen) {
  v := v.bitSet(idx_reg, true.B)       // 将第 idx_reg 位置 1（但只影响第几个 way？）
  d := d.bitSet(idx_reg, !is_alloc)    // dirty 位
  ...
  dataMem.zipWithIndex.foreach { case (mem, i) => ... }   // 写入所有 way 的同一组？
}
```

### 替换策略
直接映射下，每个组只有一个 way，不存在替换选择，因此当 miss 时直接分配该组唯一的 way。

## 2.更改方案

- 读取tag   91-92   ★★★   从单路读 → 分别读 way0/way1  
- hit判断   97      ★      hit0 || hit1- 读数据    100     ★★     选命中那路的数据
- 写路径   119-131  ★★★★  写时要指定写到哪一路 
- 替换策略  新增     ★★★    引入新寄存器 tracking way
- 写回地址  149     ★★     WriteBack时取正确way的tag
- is_dirty  165     ★★     检查两个way的dirty
- FSM逻辑  166-225  ★★     miss时选way做替换