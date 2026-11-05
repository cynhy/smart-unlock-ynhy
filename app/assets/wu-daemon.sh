#!/system/bin/sh
# 亮屏自动解锁（读取同目录 config.conf，可选 options.conf 覆盖）
DIR=/sdcard/watch-unlock
CONF=$DIR/config.conf
OPTS=$DIR/options.conf
LOG=$DIR/wu.log
LOCK=/data/local/tmp/wu.lock
LOCKDIR=/data/local/tmp/wu.daemon.d
UD=/data/local/tmp/wu.unlock.d
SHOT=/data/local/tmp/wu.shot
STATE=/data/local/tmp/wu.state
BT=/data/local/tmp/wu.bt
HIT=/data/local/tmp/wu.hit
CM=/data/local/tmp/wu.cm
KW=/data/local/tmp/wu.kw
UI=/data/local/tmp/wu.ui
PRESENT=/data/local/tmp/wu.present

[ -f "$CONF" ] || { echo "缺少 $CONF，先在应用里部署"; exit 1; }
. $CONF
[ -f "$OPTS" ] && . $OPTS
: ${DEBOUNCE:=6}; : ${MAX_FAILS:=3}; : ${COOLDOWN:=600}
: ${WATCH_MODE:=any}; : ${RSSI_MIN:=0}; : ${WIFI_SSIDS:=}
# 新模型：TARGETS 每条「启用|MAC|名称」，WIFI_LIST 每条「启用|SSID」
: ${TARGETS:=}; : ${WIFI_LIST:=}; : ${WIFI_REQUIRED:=0}; : ${WIFI_UNLOCK:=0}
: ${SIM_GUARD:=0}
: ${MUSIC_RETURN:=0}; : ${MUSIC_OPEN_APP:=0}; : ${MUSIC_OPEN_PKG:=}; : ${MUSIC_SWIPE:=0}
: ${POCKET_GUARD:=0}; : ${FAST_SETTLE:=0.1}; : ${DEBUG_TIMING:=1}
TRIGGER_SRC=${TRIGGER_SRC:-event}
QUIET_ALREADY=${QUIET_ALREADY:-0}

LOGN=0
log() {
  LOGN=$((LOGN+1))
  if [ $((LOGN % 100)) -eq 0 ] && [ -f $LOG ]; then
    sz=$(wc -c < $LOG 2>/dev/null | tr -d ' ')
    [ -n "$sz" ] && [ "$sz" -gt 1048576 ] && mv -f $LOG $LOG.old
  fi
  echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG
}

# 毫秒时钟：读 /proc/uptime，无进程开销（10ms 精度，够用来定位瓶颈）
now_ms() {
  read -r t _ < /proc/uptime
  s=${t%%.*}; f=${t#*.}
  [ ${#f} -eq 1 ] && f="${f}0"
  echo $(( s * 1000 + f * 10 ))
}

# 跨进程互斥：应用触发与事件监听可能同时进入，只允许一个真正执行注入
attempt_begin() {
  if [ -d $UD ]; then
    old=$(cat $UD/pid 2>/dev/null)
    ast=$(cat $UD/ts 2>/dev/null)
    case "$ast" in ''|*[!0-9]*) ast=0;; esac
    # 进程活着且这次尝试不超过 30 秒，才算真的在进行；防止异常退出留下假锁
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null \
        && [ $(( $(now_ms) - ast )) -lt 30000 ]; then
      return 1
    fi
    rm -rf $UD 2>/dev/null
  fi
  mkdir $UD 2>/dev/null || return 1
  echo $$ > $UD/pid 2>/dev/null
  now_ms > $UD/ts 2>/dev/null
  return 0
}
attempt_end() { rm -rf $UD 2>/dev/null; }

# 1.5 秒内已经注入过就不再注入，避免应用重试和事件监听各打一次 PIN
shot_recent() {
  [ -f $SHOT ] || return 1
  last_shot=$(cat $SHOT 2>/dev/null)
  case "$last_shot" in ''|*[!0-9]*) return 1;; esac
  [ $(( $(now_ms) - last_shot )) -lt 1500 ]
}
mark_shot() { now_ms > $SHOT.tmp 2>/dev/null && mv -f $SHOT.tmp $SHOT; }

do_unlock_guarded() {
  if attempt_begin; then
    if shot_recent; then
      log "skip: recent injection"
      attempt_end
      return
    fi
    do_unlock
    attempt_end
  else
    log "skip: another attempt running"
  fi
}

load_state() {
  fails=0; paused_until=0; pause_until=0; cooldowns=0; status=ok; reason=ok
  if [ -f $STATE ]; then
    IFS= read -r first < $STATE 2>/dev/null
    case "$first" in
      fails=*) . $STATE ;;
      '') ;;
      *) fails=$first ;;
    esac
  fi
  case "$fails" in ''|*[!0-9]*) fails=0;; esac
  case "$paused_until" in ''|*[!0-9]*) paused_until=0;; esac
  case "$pause_until" in ''|*[!0-9]*) pause_until=0;; esac
  case "$cooldowns" in ''|*[!0-9]*) cooldowns=0;; esac
  [ -z "$status" ] && status=ok
  [ -z "$reason" ] && reason=ok
}

save_state() {
  {
    echo "fails=${fails:-0}"
    echo "paused_until=${paused_until:-0}"
    echo "pause_until=${pause_until:-0}"
    echo "cooldowns=${cooldowns:-0}"
    echo "status=${status:-ok}"
    echo "reason=${reason:-ok}"
    echo "updated=$(date +%s)"
  } > $STATE.tmp 2>/dev/null && mv -f $STATE.tmp $STATE
}

trim() { echo "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'; }

match_line() {
  [ -z "$1" ] && return 1
  out=$(awk -v n="$1" '
    { line=tolower($0) }
    line ~ /bonded devices/ { b=1; next }
    b && /^[[:space:]]*$/ { b=0 }
    b && /\]/ {
      split($0, a, "]")
      nm = a[length(a)]
      sub(/^[[:space:]]+/, "", nm)
      if (index(tolower(nm), tolower(n)) > 0) { print $0; exit }
    }' $BT)
  # 不同 ROM 的分组标题不一样，兜底：全文件按名字找带 MAC 的行
  if [ -z "$out" ]; then
    out=$(grep -i -m1 -- "$1" $BT 2>/dev/null | grep -E '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}')
  fi
  [ -n "$out" ] && { echo "$out"; return 0; }
  return 1
}

connected_macs() {
  awk '
    /bonded devices/ { b=1; next }
    b && /^[[:space:]]*$/ { b=0 }
    b && /\]/ && (index($0,"ACL BR/EDR:Y") || index($0,"ACL LE:Y")) {
      mac=$1; sub(/\(.*/, "", mac); print mac
    }' $BT
  awk '
    /StateMachine for/ { m=$4 }
    /mCurrentState:/ { if (/Connected/) print m }' $BT
  # 华为/EMUI：只有 "Peer: 41:42:**:**:**:b2" + "Connected: true"，MAC 被掩码
  awk '
    /[Pp]eer: / { p=$NF }
    /Connected: true/ && p != "" { print p; p="" }' $BT
}

# MAC 比对：支持系统掩码（41:42:**:**:**:b2），逐段忽略 *
cm_has_mac() {
  mm=$(echo "$1" | tr 'a-f' 'A-F')
  grep -qixF "$mm" $CM 2>/dev/null && return 0
  awk -v a="$mm" '
    BEGIN { n=split(a, A, ":") }
    {
      sub(/^[ \t]+/, ""); c=toupper($0); m=split(c, B, ":")
      if (m != n) next
      ok=1
      for (i=1; i<=n; i++) {
        gsub(/\*/, "", B[i])
        if (B[i] == "") continue
        if (A[i] != B[i]) { ok=0; break }
      }
      if (ok) { print; exit }
    }' $CM 2>/dev/null | grep -q . && return 0
  return 1
}

device_connected() {
  echo "$1" | grep -qE 'ACL BR/EDR:Y|ACL LE:Y' && return 0
  mac=$(echo "$1" | awk '{print $1}' | sed 's/(.*//')
  sfx=$(echo "$mac" | awk -F: '{print $(NF-1)":"$NF}')
  [ -z "$sfx" ] && return 1
  awk -v s="$sfx" '
    /StateMachine for/ { d=$4 }
    /mCurrentState:/   { if (d ~ s && /Connected/) f=1 }
    END { exit(f?0:1) }' $BT
}

# 启用的目标：每行 "mac|name"
enabled_targets() {
  echo "$TARGETS" | tr ';' '\n' | while IFS='|' read -r en tmac tnm; do
    [ "$en" = "1" ] || continue
    echo "${tmac}|${tnm}"
  done
}

# 在线判定：只算「已勾选启用」的设备；any 任一在线即触发，all 要求全部在线
watch_online() {
  rm -f $HIT $CM $KW
  dumpsys bluetooth_manager > $BT 2>/dev/null || return 1
  connected_macs > $CM
  total=0; hit=0; first=""; last=""
  if [ -n "$TARGETS" ]; then
    enabled_targets > $KW
    while IFS='|' read -r tmac tnm; do
      [ -z "$tmac" ] && [ -z "$tnm" ] && continue
      total=$((total+1))
      ok=0; line=""
      if [ -n "$tmac" ] && cm_has_mac "$tmac"; then
        ok=1
      elif [ -n "$tnm" ]; then
        line=$(match_line "$tnm") || line=""
        [ -n "$line" ] && device_connected "$line" && ok=1
      fi
      if [ $ok -eq 1 ]; then
        hit=$((hit+1))
        last=${tmac:-$(echo "$line" | awk '{print $1}' | sed 's/(.*//')}
        [ -z "$first" ] && { first=1; echo "${last} (${tnm})" > $HIT; }
      fi
    done < $KW
  else
    # 兼容旧配置（WATCH_NAME / WATCH_MAC_SUFFIX）
    echo "$WATCH_NAME" | sed 's/，/,/g; s/、/,/g; s/；/,/g; s/;/,/g' | tr ',' '\n' > $KW
    while IFS= read -r kw; do
      kw=$(trim "$kw")
      [ -z "$kw" ] && continue
      total=$((total+1))
      line=$(match_line "$kw") || continue
      mac=$(echo "$line" | awk '{print $1}' | sed 's/(.*//')
      ok=0
      grep -qxF "$mac" $CM 2>/dev/null && ok=1
      [ $ok -eq 0 ] && device_connected "$line" && ok=1
      if [ $ok -eq 1 ]; then
        hit=$((hit+1)); last=$mac
        [ -z "$first" ] && { first="$line"; echo "$line" > $HIT; }
      fi
    done < $KW
    if [ -z "$first" ] && [ -n "$WATCH_MAC_SUFFIX" ]; then
      total=1
      m=$(grep -i "$WATCH_MAC_SUFFIX" $CM 2>/dev/null | head -n1)
      [ -n "$m" ] && { hit=1; last=$m; echo "$m" > $HIT; }
    fi
  fi
  WATCH_HIT=$hit; WATCH_TOTAL=$total; LAST_MAC=$last
  case "$WATCH_MODE" in
    all) [ "$total" -gt 0 ] && [ "$hit" -ge "$total" ] ;;
    *)   [ "$hit" -gt 0 ] ;;
  esac
}

rssi_value() {
  grep -o 'RSSI: *-*[0-9][0-9]*' $BT 2>/dev/null | head -n1 | grep -o -- '-*[0-9][0-9]*'
}

rssi_ok() {
  [ -z "$RSSI_MIN" ] && return 0
  [ "$RSSI_MIN" = "0" ] && return 0
  case "$RSSI_MIN" in -*|[0-9]*) ;; *) return 0 ;; esac
  v=$(rssi_value)
  if [ -z "$v" ]; then
    log "rssi: 系统未暴露 RSSI，本次跳过距离门槛"
    return 0
  fi
  if [ "$v" -lt "$RSSI_MIN" ]; then
    log "skip: rssi ${v} < ${RSSI_MIN}（判断为离得太远）"
    return 1
  fi
  return 0
}

wifi_ssid() {
  s=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: "[^"]*"' | head -n1 | sed 's/SSID: //; s/"//g')
  [ -z "$s" ] && s=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: [^,}]*' | head -n1 | sed 's/SSID: //; s/"//g')
  [ -z "$s" ] && s=$(cmd wifi status 2>/dev/null | grep -m1 -i 'ssid' | sed 's/.*SSID: //; s/"//g')
  trim "$s" | head -n1
}

wifi_trusted() {
  s=$(wifi_ssid)
  [ -z "$s" ] && return 1
  if [ -n "$WIFI_LIST" ]; then
    echo "$WIFI_LIST" | tr ';' '\n' | while IFS='|' read -r en ssid; do
      [ "$en" = "1" ] || continue
      ssid=$(trim "$ssid")
      [ -n "$ssid" ] && [ "$s" = "$ssid" ] && { echo yes; break; }
    done | grep -q yes
    return $?
  fi
  [ -z "$WIFI_SSIDS" ] && return 1
  echo "$WIFI_SSIDS" | tr '|' '\n' | while IFS= read -r t; do
    t=$(trim "$t")
    [ -z "$t" ] && continue
    [ "$s" = "$t" ] && { echo yes; break; }
  done | grep -q yes
}

# 应用进程是否活着。活着且已登记监听蓝牙广播，说明连接/断开事件都被接住了，
# 缓存一定是最新的 —— 这时不需要任何轮询，也不用重新查蓝牙
app_alive() {
  p=$(pidof com.watchunlock.app 2>/dev/null)
  [ -n "$p" ]
}

# 完整判定一次并写缓存。$1=track 表示「应用正在监听蓝牙事件」，
# 此后缓存长期可信，直到应用被杀或自己撤销监听
presence_refresh() {
  tr=0
  if [ "${1:-}" = "track" ]; then
    tr=1
  elif [ -f $PRESENT ]; then
    . $PRESENT 2>/dev/null
    [ "${track:-0}" = "1" ] && tr=1
  fi
  ts=$(date +%s)
  bt_ok=0; w_ok=0
  watch_online && bt_ok=1
  # WiFi 只在被启用时才参与判定；两种模式：
  #   WIFI_REQUIRED=1  蓝牙必须在线，且连着可信 WiFi（更严格，冷查多约 300ms）
  #   WIFI_UNLOCK=1    蓝牙或可信 WiFi 任一在线即可（WiFi 可独立解锁）
  if [ "$WIFI_REQUIRED" = "1" ] || [ "$WIFI_UNLOCK" = "1" ]; then
    wifi_trusted && w_ok=1
  fi
  if [ "$WIFI_REQUIRED" = "1" ]; then
    if [ $bt_ok -eq 1 ] && [ $w_ok -eq 1 ]; then online=yes; src="bt+wifi"
    elif [ $bt_ok -eq 1 ]; then online=no; src=no-wifi
    elif [ $w_ok -eq 1 ]; then online=no; src=no-bt
    else online=no; src=none
    fi
  else
    if [ $bt_ok -eq 1 ] && [ $w_ok -eq 1 ]; then online=yes; src="bt+wifi"
    elif [ $bt_ok -eq 1 ]; then online=yes; src=bt
    elif [ $w_ok -eq 1 ]; then online=yes; src=wifi
    else online=no; src=none
    fi
  fi
  echo "ts=$ts online=$online src=$src hit=${WATCH_HIT:-0} total=${WATCH_TOTAL:-0} track=$tr" > $PRESENT.tmp \
    && mv -f $PRESENT.tmp $PRESENT
}

# 亮屏热路径：只读缓存，0 进程查询。
# 缓存由「蓝牙连接/断开广播 → 应用 → --present」实时维护；只有应用不在
# （被杀 / 未启动 / 没有蓝牙权限）时才现场查一次，慢但正确
present_ok_fast() {
  # 开了 RSSI 门槛就必须现查：缓存里没有 RSSI
  [ -n "$RSSI_MIN" ] && [ "$RSSI_MIN" != "0" ] && { presence_refresh; }
  online=no; src=none; hit=0; total=0; track=0
  [ -f $PRESENT ] && . $PRESENT
  if [ "$online" = "yes" ] && [ "$track" = "1" ] && app_alive; then
    log "present(cache): $src ${hit}/${total}"
    return 0
  fi
  presence_refresh
  if [ "$online" = "yes" ]; then
    log "present: $src ${WATCH_HIT}/${WATCH_TOTAL}"
    return 0
  fi
  return 1
}

locked() {
  p=$(dumpsys window policy 2>/dev/null)
  case "$p" in
    *mIsShowing=true*) return 0 ;;
    *mIsShowing=false*) return 1 ;;
  esac
  # 个别 ROM 改了输出格式，退回焦点窗口判断
  dumpsys window 2>/dev/null | grep -m1 mCurrentFocus \
    | grep -qE 'Keyguard|keyguard|NotificationShade|StatusBar'
}

# 息屏/口袋模式：一个 dumpsys 同时看 wakefulness 和显示状态
awake() {
  dumpsys power 2>/dev/null | grep -qE 'Display Power: state=ON|mWakefulness=Awake'
}

wait_unlocked() {
  n=0
  while [ $n -lt 12 ]; do
    locked || return 0
    sleep 0.25; n=$((n+1))
  done
  return 1
}

keypad_present() {
  timeout 3 uiautomator dump $UI >/dev/null 2>&1 || return 1
  grep -qE 'keyguard_pin_view|/pinEntry' $UI 2>/dev/null
}

wait_keypad() {
  n=0
  while [ $n -lt 5 ]; do
    keypad_present && return 0
    sleep 0.2; n=$((n+1))
  done
  return 1
}

sim_locked() {
  st="$(getprop gsm.sim.state) $(getprop gsm.sim.state.2)"
  case "$st" in
    *PIN_REQUIRED*|*PUK_REQUIRED*|*NETWORK_LOCKED*|*PERM_DISABLED*|*CARD_RESTRICTED*) return 0 ;;
  esac
  return 1
}

on_keyguard() {
  KG_FOCUS=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)
  echo "$KG_FOCUS" | grep -qE 'NotificationShade|StatusBar|Keyguard|keyguard|com.android.systemui|miui.systemui'
}

screen_xy() {
  s=$(wm size 2>/dev/null | tail -n 1 | awk '{print $3}')
  w=$(echo "$s" | cut -dx -f1); h=$(echo "$s" | cut -dx -f2)
  [ -z "$w" ] && { w=1080; h=2400; }
  echo "$((w/2)) $((h*92/100)) $((w/2)) $((h*19/100))"
}

# 开门方式：优先 wm dismiss-keyguard（约 50ms，直接把 PIN 界面拉起来），
# 比上滑手势（300ms 动画 + 等待）快得多。读不出来就默认 dismiss，
# 真的拉不起 PIN 界面时慢路径会自动改用上滑
UNLOCK_MODE=dismiss

open_dismiss() { wm dismiss-keyguard; sleep 0.3; }

# 热路径的开门动作：只发 dismiss，不等整段动画；input 自身启动的 150ms 正好当缓冲
open_fast() {
  if [ "$UNLOCK_MODE" = "dismiss" ]; then
    wm dismiss-keyguard
  else
    set -- $(screen_xy)
    input swipe $1 $2 $3 $4 300
  fi
  [ "$FAST_SETTLE" != "0" ] && sleep $FAST_SETTLE
}
open_swipe() {
  set -- $(screen_xy)
  input swipe $1 $2 $3 $4 300
  sleep 0.6
}
open_primary() { [ "$UNLOCK_MODE" = "dismiss" ] && open_dismiss || open_swipe; }
open_alt()     { [ "$UNLOCK_MODE" = "dismiss" ] && open_swipe   || open_dismiss; }

unlock_ok() {
  log "result: $1 (total=$(($(now_ms) - T0))ms)"
  if [ "$DEBUG_TIMING" = "1" ] && [ -n "$T_INJ" ]; then
    log "timing: lock=$((T_LOCK-T0))ms presence=$((T_PRES-T_LOCK))ms inject=$((T_INJ-T_PRES))ms"
  fi
  fails=0; cooldowns=0; paused_until=0; status=ok; reason=ok
  save_state
  # 音乐锁屏返回：放到后台，避免拖慢解锁完成时间
  [ "$MUSIC_RETURN" = "1" ] && ( music_return ) &
  return 0
}

# 音乐锁屏在输入密码后仍停在音乐页：优先按包名打开主界面，否则向右滑动一次
music_return() {
  sleep 0.6
  if [ "$MUSIC_OPEN_APP" = "1" ] && [ -n "$MUSIC_OPEN_PKG" ]; then
    if am start -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER -p "$MUSIC_OPEN_PKG" >/dev/null 2>&1; then
      log "music: opened $MUSIC_OPEN_PKG"
      return 0
    fi
    log "music: open $MUSIC_OPEN_PKG failed"
  fi
  [ "$MUSIC_SWIPE" = "1" ] || return 0
  sz=$(wm size 2>/dev/null | tail -n1 | awk '{print $NF}')
  w=${sz%x*}
  h=${sz#*x}
  case "$w" in ''|*[!0-9]*) w=1080;; esac
  case "$h" in ''|*[!0-9]*) h=2400;; esac
  input swipe $((w / 20)) $((h / 2)) $((w * 19 / 20)) $((h / 2)) 250
  log "music: swiped right"
}

# 慢路径：快路径失败后按 ROM 自适应上滑/关闭键盘，确认键盘在才重新注入
do_unlock_slow() {
  log "slow: keyguard still up, try open + verify"
  open_primary
  if ! on_keyguard; then
    log "skip: not on keyguard (${KG_FOCUS:-no focus})"
    status=skip; reason=not_keyguard; save_state; return
  fi
  if ! wait_keypad; then
    log "retry: keypad not ready, alternate path"
    open_alt
    if ! wait_keypad; then
      log "abort: no keypad, refuse to inject"
      status=skip; reason=no_keypad; save_state; return
    fi
  fi
  input text "$PIN_TEXT"
  if wait_unlocked; then unlock_ok "unlocked (slow)"; return; fi

  sleep 0.4
  if ! locked; then unlock_ok "unlocked (late)"; return; fi
  if ! keypad_present; then
    log "retry: keypad gone, re-check"
    sleep 0.8
    if wait_unlocked; then unlock_ok "unlocked (late)"; return; fi
    open_alt
    if ! wait_keypad; then
      log "abort: no keypad, refuse to inject"
      status=skip; reason=no_keypad; save_state; return
    fi
  fi

  dels=""; n=0; cnt=$(( ${#PIN_TEXT} + 2 ))
  while [ $n -lt $cnt ]; do dels="$dels 67"; n=$((n+1)); done
  log "retry: clear and inject again (del=$cnt)"
  input keyevent $dels
  input text "$PIN_TEXT"
  if wait_unlocked; then unlock_ok "unlocked (retry)"; return; fi

  log "result: still locked"
  reason=pin_mismatch
  fails=$((fails+1))
  if [ "$fails" -ge "$MAX_FAILS" ]; then
    cooldowns=$((cooldowns+1))
    paused_until=$(( $(date +%s) + COOLDOWN ))
    fails=0
    if [ "$cooldowns" -ge 2 ]; then
      status=disabled; reason=pin_changed
      log "FATAL: 连续两个冷却周期失败，守护已停用；请核对锁屏密码后重新部署"
      save_state
      kill $$ 2>/dev/null
      for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'logcat -b events' | grep -v grep | awk '{print $1}'); do kill $p 2>/dev/null; done
      return
    fi
    status=cooling
    log "too many failures, cooling down ${COOLDOWN}s (cycle $cooldowns)"
    log "提示：若刚改过锁屏密码，请重新部署；否则检查键盘是否被遮挡"
  else
    status=failed
  fi
  save_state
}

# 热路径复核：直接看当前焦点，跳过 policy dump（省约 30~40ms）
locked_fast() {
  dumpsys window 2>/dev/null | grep -m1 mCurrentFocus \
    | grep -qE 'Keyguard|keyguard|NotificationShade|StatusBar|systemui'
}

# 热路径：亮屏直接注入，不再 dismiss/滑动、不再 uiautomator dump
do_unlock() {
  T0=$(now_ms); T_INJ=""
  # 息屏/口袋模式时注入没有意义；POCKET_GUARD=1 才多花一次 dumpsys
  if [ "$POCKET_GUARD" = "1" ] && ! awake; then log "skip: display off"; return; fi
  if ! locked; then [ "$QUIET_ALREADY" = "1" ] || log "skip: already unlocked"; return; fi
  T_LOCK=$(now_ms)
  log "trigger: $TRIGGER_SRC"
  # SIM PIN 界面绝不能注入手机 PIN，否则可能锁卡；默认关闭，只有设置里开启才检查
  if [ "$SIM_GUARD" = "1" ] && sim_locked; then
    log "skip: sim locked ($st), refuse to inject"
    status=skip; reason=sim_locked; save_state; return
  fi
  if ! present_ok_fast; then
    log "skip: 解锁条件不满足（已启用设备未全部/任一在线，或 WiFi 不满足）"
    return
  fi
  T_PRES=$(now_ms)
  if ! rssi_ok; then return; fi

  # 先把 PIN 键盘拉起来再输入；dismiss 比上滑动画快，input 启动时间正好当缓冲
  open_fast
  mark_shot
  input text "$PIN_TEXT"
  T_INJ=$(now_ms)

  # 每次约 180ms（dumpsys 80ms + 100ms 间隔），12 次约 2 秒；
  # 解锁一成功就立即返回，所以放宽次数只影响「失败后的等待」，不影响正常耗时
  n=0
  while [ $n -lt 12 ]; do
    locked_fast || { T_VER=$(now_ms); unlock_ok "unlocked"; return; }
    sleep 0.1; n=$((n+1))
  done
  [ "$DEBUG_TIMING" = "1" ] && log "timing: lock=$((T_LOCK-T0))ms presence=$((T_PRES-T_LOCK))ms inject=$((T_INJ-T_PRES))ms"
  # 应用亮屏触发会每 1 秒左右重试一次。第一次往往落在键盘动画开始前，
  # 注入会被丢掉；这时不要进 10 秒慢路径占住互斥锁，立即返回让下一次重试接手。
  if [ "$TRIGGER_SRC" = "app" ]; then
    status=skip; reason=fast_miss; save_state
    log "result: still locked (app retry)"
    return
  fi
  do_unlock_slow
}

# ---- 命令行开关 ----
load_state
case "$1" in
  --pause)
    n=${2:-3600}
    case "$n" in ''|*[!0-9]*) n=3600;; esac
    pause_until=$(( $(date +%s) + n ))
    status=paused; reason=brief_pause
    save_state
    log "paused ${n}s (until $pause_until)"
    echo "paused ${n}s"
    exit 0
    ;;
  --resume)
    pause_until=0
    [ "$status" = "paused" ] && { status=ok; reason=ok; }
    save_state
    log "resume"
    echo "resumed"
    exit 0
    ;;
  --unlock)
    # 应用亮屏广播触发：一次性执行，不依赖常驻守护是否活着
    now=$(date +%s)
    [ "$status" = "disabled" ] && exit 0
    if [ "$pause_until" -gt "$now" ] || [ "$paused_until" -gt "$now" ]; then
      exit 0
    fi
    TRIGGER_SRC=app
    QUIET_ALREADY=1
    do_unlock_guarded
    exit 0
    ;;
  --present)
    # 应用调用。带第二个参数 track 表示「应用正在监听蓝牙事件」，缓存可长期信任；
    # 不带参数只刷新内容、保留原有登记状态（息屏预热用）
    presence_refresh ${2:-}
    cat $PRESENT 2>/dev/null
    exit 0
    ;;
  --untrack)
    # 应用监听不了蓝牙（没授权 / 注册失败）时调用：撤销登记，
    # 之后亮屏一律现查，宁可慢也不拿过期缓存去解锁
    if [ -f $PRESENT ]; then
      ts=0; online=no; src=none; hit=0; total=0
      . $PRESENT
      echo "ts=${ts:-0} online=$online src=$src hit=${hit:-0} total=${total:-0} track=0" > $PRESENT.tmp \
        && mv -f $PRESENT.tmp $PRESENT
    fi
    exit 0
    ;;
  --bench)
    # 定位瓶颈：每个关键命令的真实耗时
    i=0
    while [ $i -lt 3 ]; do
      t=$(now_ms); dumpsys bluetooth_manager >/dev/null 2>&1
      echo "bluetooth_manager : $(( $(now_ms) - t ))ms"
      t=$(now_ms); dumpsys window policy >/dev/null 2>&1
      echo "window policy     : $(( $(now_ms) - t ))ms"
      t=$(now_ms); dumpsys power >/dev/null 2>&1
      echo "power             : $(( $(now_ms) - t ))ms"
      t=$(now_ms); dumpsys wifi >/dev/null 2>&1
      echo "wifi              : $(( $(now_ms) - t ))ms"
      t=$(now_ms); uiautomator dump /data/local/tmp/wu.bench.ui >/dev/null 2>&1
      echo "uiautomator dump  : $(( $(now_ms) - t ))ms"
      t=$(now_ms); input --help >/dev/null 2>&1
      echo "input (spawn)     : $(( $(now_ms) - t ))ms"
      i=$((i+1))
    done
    exit 0
    ;;
esac

if [ "$1" = "--check" ]; then
  t=$(now_ms)
  watch_online >/dev/null && online=yes || online=no
  bt_ms=$(( $(now_ms) - t ))
  own=$(wifi_ssid); wtr=no
  wifi_trusted && wtr=yes
  mac=$(awk '{print $1}' $HIT 2>/dev/null | sed 's/(.*//')
  if [ -z "$mac" ]; then
    firstkw=$(echo "$WATCH_NAME" | cut -d, -f1)
    mac=$(match_line "$firstkw" | awk '{print $1}' | sed 's/(.*//')
  fi
  load_state
  left=$((${paused_until:-0} - $(date +%s)))
  [ "$left" -lt 0 ] && left=0
  pleft=$((${pause_until:-0} - $(date +%s)))
  [ "$pleft" -lt 0 ] && pleft=0
  if [ -n "$TARGETS" ]; then
    echo "targets     : $(enabled_targets | wc -l) 启用 / $(echo "$TARGETS" | tr ';' '\n' | grep -c '^[01]|') 全部"
  else
    echo "watch_name  : ${WATCH_NAME:-（用 MAC 末四位）}"
  fi
  echo "watch_mode  : ${WATCH_MODE:-any}"
  echo "watch_hit   : ${WATCH_HIT:-0}/${WATCH_TOTAL:-0}"
  echo "match_mac   : ${mac:-未匹配到设备}"
  echo "watch_online: $online"
  echo "bt_check_ms : ${bt_ms}ms"
  echo "rssi        : $(rssi_value)"
  echo "rssi_min    : ${RSSI_MIN:-0}"
  echo "wifi_unlock : ${WIFI_UNLOCK:-0}"
  echo "wifi_required: ${WIFI_REQUIRED:-0}"
  echo "sim_guard   : ${SIM_GUARD:-0}"
  echo "wifi_ssid   : ${own:-（未连接）}"
  echo "wifi_trusted: $wtr"
  echo "locked      : $(locked && echo yes || echo no)"
  echo "awake       : $(awake && echo yes || echo no)"
  echo "pin_len     : ${#PIN_TEXT}"
  echo "status      : $status"
  echo "reason      : $reason"
  echo "cooldowns   : $cooldowns"
  echo "cooldown_in : ${left}s"
  echo "pause_in    : ${pleft}s"
  exit 0
fi

# 原子目录锁：mkdir 成功才算抢到；旧式 wu.lock 只作为兼容展示
if [ -d $LOCKDIR ]; then
  old=$(cat $LOCKDIR/pid 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    log "already running (pid $old)"; exit 0
  fi
  rm -rf $LOCKDIR 2>/dev/null
fi
mkdir $LOCKDIR 2>/dev/null || { log "already running (lockdir), exit"; exit 0; }
echo $$ > $LOCKDIR/pid
echo $$ > $LOCK

# 抢到锁后清掉上一次守护留下的孤儿：mksh 会为 logcat|while 管道 fork 一个
# 「长得和主进程一样」的子 shell，老版本用 ps 判断重复会把它误当成守护。
# 只清理 NF==3 的常驻脚本进程（--present/--check 等一次性调用有参数，不误杀）。
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -v grep \
  | awk -v me=$$ 'NF == 3 && $2 ~ /^(sh|\/system\/bin\/sh|\/bin\/sh)$/ \
      && $3 ~ /(^|\/)wu-daemon\.sh$/ && $1 != me {print $1}'); do
  kill -9 $p 2>/dev/null
done
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'logcat -b events' | grep -v grep | awk '{print $1}'); do
  kill -9 $p 2>/dev/null
done

load_state
last=0
log "daemon started (pid $$) status=$status mode=${WATCH_MODE:-any} rssi_min=${RSSI_MIN:-0} wifi_unlock=${WIFI_UNLOCK:-0} wifi_required=${WIFI_REQUIRED:-0} sim_guard=${SIM_GUARD:-0}"
if [ "$status" = "disabled" ]; then
  log "state=disabled, refuse to start；请核对密码后重新部署"
  rm -rf $LOCKDIR 2>/dev/null
  rm -f $LOCK
  exit 0
fi

# 启动时刷新一次在线缓存；之后完全由应用收到「蓝牙连接/断开广播」时调用 --present 维护，
# 守护自身不做任何轮询
( presence_refresh ) >/dev/null 2>&1 &

now=$(date +%s)
if [ "$pause_until" -gt "$now" ]; then
  log "paused (until $pause_until), skip startup unlock"
elif [ "$paused_until" -gt "$now" ]; then
  log "cooling down (until $paused_until), skip startup unlock"
elif awake && locked && present_ok_fast; then
  last=$now; TRIGGER_SRC=start; do_unlock_guarded
fi

while :; do
  # -T 1：最多回放缓冲区里最后 1 条，避免重启时把历史亮屏事件全部重放；
  # -v epoch + 时间过滤：丢掉启动前的事件，防止息屏状态下去注入 PIN
  run_ts=$(date +%s)
  logcat -b events -s power_screen_state,wifi_network_state_changed -T 1 -v epoch 2>/dev/null \
    | while read -r line; do
    line="${line#"${line%%[! ]*}"}"
    ets=${line%% *}; eint=${ets%%.*}
    case "$eint" in ''|*[!0-9]*) eint=0;; esac
    if [ "$eint" -gt 0 ] && [ "$eint" -lt $((run_ts - 1)) ]; then continue; fi
    case "$line" in
      *power_screen_state*"[1,"*) ev=screen ;;
      *power_screen_state*"[0,"*) ev=screen_off ;;
      *wifi_network_state_changed*) ev=wifi ;;
      *) continue ;;
    esac
    # 息屏不参与防抖：否则「锁屏后 2 秒内又亮屏」会被上一次事件吞掉
    if [ "$ev" = "screen_off" ]; then last=0; continue; fi
    load_state
    if [ "$status" = "disabled" ]; then
      log "state=disabled, stop daemon"
      kill $$ 2>/dev/null
      for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'logcat -b events' | grep -v grep | awk '{print $1}'); do kill $p 2>/dev/null; done
      continue
    fi
    now=$(date +%s)
    if [ "$pause_until" -gt "$now" ]; then
      log "paused, skip (left $((pause_until-now))s)"
      continue
    fi
    # 亮屏事件本来就不会连发，用 2 秒短防抖即可；长防抖只留给 WiFi 触发，
    # 否则「锁屏后 6 秒内又亮屏」会被直接吞掉（表现为偶发不解锁）
    if [ "$ev" = "screen" ]; then deb=2; else deb=$DEBOUNCE; fi
    [ $((now - last)) -lt $deb ] && continue
    last=$now
    case "$ev" in
      wifi)
        # WiFi 没被用于解锁时不因 WiFi 事件触发，省一次 dumpsys
        [ "$WIFI_UNLOCK" = "1" ] || [ "$WIFI_REQUIRED" = "1" ] || continue
        wifi_trusted || continue
        awake || continue
        locked || continue
        log "trigger: 连上可信 WiFi $(wifi_ssid)"
        TRIGGER_SRC=wifi
        ;;
      screen)
        log "event: screen on"
        TRIGGER_SRC=screen
        ;;
    esac
    if [ "$paused_until" -gt 0 ] && [ "$now" -lt "$paused_until" ]; then
      log "cooling down, skip (left $((paused_until-now))s)"
      continue
    fi
    do_unlock_guarded
  done
  log "logcat exited, restart in 1s"
  sleep 1
done
