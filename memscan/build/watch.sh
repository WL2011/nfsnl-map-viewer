#!/system/bin/sh
# usage: watch.sh <map> <tag> <rounds> <delay>
cd /data/local/tmp
sleep ${4:-0}
i=0
while [ $i -lt ${3:-6} ]; do
  PID=$(pidof com.ea.game.nfs14_row)
  CLASSPATH=/data/local/tmp/scan.dex app_process /data/local/tmp Scan watch $PID /data/local/tmp/pts/$1.bin 30 50 /data/local/tmp/w_$2_$i.txt >> w_$2.log 2>&1
  echo "round $i $(date +%T)" >> w_$2.log
  i=$((i+1))
done
echo finished >> w_$2.log
