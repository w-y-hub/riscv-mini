#!/bin/bash
# Cache parameter sweep experiment
set -e

BASE_DIR=~/riscv-mini
CONFIG_FILE=$BASE_DIR/src/main/scala/mini/Config.scala
RESULT_FILE=$BASE_DIR/cache_experiment_results.csv
TEST_DIR=$BASE_DIR/tests
GEN_DIR=$BASE_DIR/generated-src
TARGET_DIR=$BASE_DIR/target

BENCHMARKS="median.riscv.hex multiply.riscv.hex qsort.riscv.hex"

# Configurations: name nWays nSets blockBytes
CONFIGS=(
  "c1_2w256s16b  2 256 16"
  "c2_2w512s16b  2 512 16"
  "c3_2w256s32b  2 256 32"
  "c4_2w256s8b   2 256  8"
  "c5_2w128s16b  2 128 16"
)

# Backup
cp $CONFIG_FILE $CONFIG_FILE.bak

echo "name,nWays,nSets,blockBytes,benchmark,total_cycles,instructions,cpi,icache_access,icache_miss,icache_miss_rate,icache_stall,dcache_access,dcache_miss,dcache_miss_rate,dcache_stall" > $RESULT_FILE

for cfg in "${CONFIGS[@]}"; do
  read name nways nsets bbytes <<< "$cfg"
  echo ""
  echo "=========================================="
  echo "  Config: $name (nWays=$nways nSets=$nsets blockBytes=$bbytes)"
  echo "=========================================="

  # Modify Config.scala
  sed -i "s/nWays *= *[0-9]*/nWays = $nways/" $CONFIG_FILE
  sed -i "s/nSets *= *[0-9]*/nSets = $nsets/" $CONFIG_FILE
  sed -i "s/blockBytes *= *[0-9]*/blockBytes = $bbytes/" $CONFIG_FILE

  # Clean
  echo "Cleaning..."
  rm -rf $GEN_DIR $TARGET_DIR VTile

  # Build Chisel
  echo "Building Chisel..."
  sbt -ivy .ivy2 "run --target-dir=$GEN_DIR --dump-fir" > /tmp/sbt_build.log 2>&1 || {
    echo "SBT FAILED for $name"
    cat /tmp/sbt_build.log | tail -10
    continue
  }

  # Build Verilator  
  echo "Building Verilator..."
  make verilator > /tmp/ver_build.log 2>&1 || {
    echo "Verilator FAILED for $name"
    continue
  }

  # Run benchmarks
  for bmark in $BENCHMARKS; do
    hex=$TEST_DIR/$bmark
    if [ ! -f "$hex" ]; then
      echo "SKIP $bmark (not found)"
      continue
    fi

    echo -n "  Running $bmark... "
    timeout 120 ./VTile "$hex" > /tmp/vt_stdout.txt 2> /tmp/vt_stderr.txt
    ret=$?

    if [ $ret -eq 124 ]; then
      echo "TIMEOUT"
      continue
    fi

    output=$(cat /tmp/vt_stderr.txt)

    total_cycles=$(echo "$output" | grep -oP 'Total Cycles:\s*\K\d+' || echo 0)
    instructions=$(echo "$output" | grep -oP 'Instructions:\s*\K\d+' || echo 0)
    cpi=$(echo "$output" | grep -oP 'CPI:\s*\K[\d.]+' || echo 0)
    ic_acc=$(echo "$output" | grep -oP 'I\$ Access:\s*\K\d+' || echo 0)
    ic_miss=$(echo "$output" | grep -oP 'I\$ Miss:\s*\K\d+' || echo 0)
    ic_mr=$(echo "$output" | grep -oP 'I\$ Miss Rate:\s*\K[\d.]+' || echo 0)
    ic_stall=$(echo "$output" | grep -oP 'I\$ Stall:\s*\K\d+' || echo 0)
    dc_acc=$(echo "$output" | grep -oP 'D\$ Access:\s*\K\d+' || echo 0)
    dc_miss=$(echo "$output" | grep -oP 'D\$ Miss:\s*\K\d+' || echo 0)
    dc_mr=$(echo "$output" | grep -oP 'D\$ Miss Rate:\s*\K[\d.]+' || echo 0)
    dc_stall=$(echo "$output" | grep -oP 'D\$ Stall:\s*\K\d+' || echo 0)

    echo "CPI=$cpi I\$Miss=$ic_mr% D\$Miss=$dc_mr%"

    echo "$name,$nways,$nsets,$bbytes,$bmark,$total_cycles,$instructions,$cpi,$ic_acc,$ic_miss,$ic_mr,$ic_stall,$dc_acc,$dc_miss,$dc_mr,$dc_stall" >> $RESULT_FILE
  done
done

# Restore
mv $CONFIG_FILE.bak $CONFIG_FILE
rm -rf $GEN_DIR $TARGET_DIR VTile

echo ""
echo "=========================================="
echo "  Restoring original build..."
echo "=========================================="
sbt -ivy .ivy2 "run --target-dir=$GEN_DIR --dump-fir" > /tmp/sbt_restore.log 2>&1
make verilator > /tmp/ver_restore.log 2>&1

echo ""
echo "Experiment complete! Results:"
cat $RESULT_FILE
