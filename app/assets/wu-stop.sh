#!/system/bin/sh
# 停止守护：只结束真正的守护/监听进程；排除自身与父进程，
# 避免手动在 adb shell 里执行（外层命令串里也含关键字）时把命令自身杀掉
me=$$
pp=$PPID
# 延长解锁：先写回锁屏密码再结束进程，避免留下无锁状态
if [ -f /data/local/tmp/wu.extended ] && [ -s /data/local/tmp/watch-unlock/extpin ]; then
  EP=$(cat /data/local/tmp/watch-unlock/extpin 2>/dev/null)
  locksettings set-pin "$EP" --user 0 >/dev/null 2>&1
  locksettings set-disabled false --user 0 >/dev/null 2>&1
  rm -f /data/local/tmp/wu.extended
fi
ps -A -o PID,ARGS 2>/dev/null | grep -E 'wu-daemon|logcat -b events' | grep -v grep \
  | awk -v me="$me" -v pp="$pp" '$1 != me && $1 != pp { print $1 }' > /data/local/tmp/wu.kill.tmp
for p in $(cat /data/local/tmp/wu.kill.tmp 2>/dev/null); do
  kill -9 $p 2>/dev/null
done
rm -f /data/local/tmp/wu.kill.tmp
rm -rf /data/local/tmp/wu.daemon.d /data/local/tmp/wu.unlock.d
rm -f /data/local/tmp/wu.lock /data/local/tmp/wu.shot /data/local/tmp/wu.ui
echo stopped
