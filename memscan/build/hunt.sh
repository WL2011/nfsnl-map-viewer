#!/system/bin/sh
PID=$(pidof com.ea.game.nfs14_row)
cd /data/local/tmp
CLASSPATH=/data/local/tmp/scan.dex app_process /data/local/tmp Scan hunt $PID /data/local/tmp/pts/${1:-all}.bin 30 ${2:-25} 1000 /data/local/tmp/hunt.txt > /data/local/tmp/hunt.log 2>&1
echo finished >> /data/local/tmp/hunt.log
