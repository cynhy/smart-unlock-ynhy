# SIM 卡 PIN 防呆：重启后别把手机 PIN 输进 SIM 卡

适用环境：小米 15（24129PN74C），HyperOS 3（V816），Android 16，未 root，双卡（中国电信）。
本文只讲 2026-11-03 新增的 SIM 卡防呆逻辑，整套方案的完整说明见《酷安教程-手表联动解锁.md》。
效果：SIM 卡处于 PIN / PUK / 网络锁状态时，守护进程跳过注入并写日志，不会连续输错把 SIM 卡锁死。

---

## 一、问题

启用 SIM 卡 PIN 后，手机重启时会先出现 SIM 卡解锁键盘。这套脚本只认"屏幕亮了 + 处于锁屏"，此时如果照常注入手机 PIN：

```
重启 → SIM 卡 PIN 键盘 → 脚本注入 6 位手机 PIN → 错误
     → 重试再注入一次 → 错误
     → 连续 3 次后 SIM 卡被锁，需要 PUK 码解锁
```

PUK 码一般不在手边，连续输错 10 次还会永久报废 SIM 卡。这个后果比"手机被锁"严重得多，必须在注入前拦住。

---

## 二、方案比较

最初的想法是在 `dumpsys window` 里找 SIM / ICC 相关界面，命中就跳过。可行，但不够稳：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 匹配窗口标题里的 `SIM` / `ICC` | 直观 | 各 ROM 的 Activity 名差异大，改版就失效；厂商定制界面未必带这些词 |
| 读 `gsm.sim.state` | 与界面无关、开销极低、状态语义明确 | 依赖该属性是否存在（本机实测存在） |
| 校验当前焦点是否在锁屏 | 通用，能覆盖 SIM 卡以外的凭证界面 | 需要维护锁屏窗口白名单 |

最终采用后两者组合：**先用 SIM 状态做快速否决，再用焦点做通用兜底**。两层都通过才允许按键。

---

## 三、原理

### 3.1 SIM 状态

`getprop gsm.sim.state` 是 RIL 写入的系统属性。双卡设备用逗号分隔，本机实测：

```
[gsm.sim.state]: [LOADED,LOADED]
```

需要拦截的状态：

| 值 | 含义 |
| --- | --- |
| `PIN_REQUIRED` | 需要 SIM 卡 PIN，正是重启后的场景 |
| `PUK_REQUIRED` | PIN 输错次数用尽，需要 PUK |
| `NETWORK_LOCKED` | 网络锁 |
| `PERM_DISABLED` | SIM 被永久禁用 |
| `CARD_RESTRICTED` | 卡受限 |

`LOADED`、`READY`、`ABSENT` 等正常状态不拦。部分机型还会额外暴露 `gsm.sim.state.2`，脚本把两个属性拼在一起判断，避免漏掉副卡。

### 3.2 焦点校验

`dumpsys window | grep mCurrentFocus` 能拿到当前焦点窗口：

| 场景 | 焦点 |
| --- | --- |
| 锁屏（含密码键盘） | `NotificationShade`（MIUI） / `StatusBar`（AOSP） |
| SIM 卡相关设置页 | `com.android.settings/...IccLockSettingsActivity` |
| 桌面 | `com.miui.home/...Launcher` |

脚本只接受包含 `NotificationShade`、`StatusBar`、`Keyguard`、`systemui` 的焦点，其余一律跳过。这样即使 SIM 状态属性缺失或读不到，也不会盲打。

### 3.3 为什么不在每次解锁前都做 UI dump

上一版已经有一个 `uiautomator dump` 的键盘校验，但它只在失败重试路径调用，约 0.4 秒。如果提到每次解锁都跑，正常解锁会从 1 秒变成 1.4 秒以上。SIM 状态读取是 `getprop`，焦点读取是一次 `dumpsys window`，加起来不到 0.2 秒，足够便宜。

---

## 四、实现

### 4.1 两个判定函数

```sh
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
```

### 4.2 插入位置

```
屏幕点亮
  ├─ 防抖 / 唤醒 / 锁屏 / 设备在线检查
  └─ do_unlock
       ├─ 显示熄灭？→ skip: display off
       ├─ SIM 锁定？  → skip: sim locked (...)
       ├─ dismiss-keyguard
       ├─ 焦点不是锁屏？→ skip: not on keyguard (...)
       └─ input text PIN
```

`sim_locked` 放在唤出键盘之前，焦点校验放在唤出之后、注入之前。前者省掉无谓操作，后者拿到的是"键盘已展开后的真实焦点"。

### 4.3 新增日志

| 日志 | 含义 |
| --- | --- |
| `skip: sim locked (PIN_REQUIRED,LOADED), refuse to inject` | SIM 卡需要 PIN，跳过 |
| `skip: not on keyguard (mCurrentFocus=Window{... com.android.settings/...IccLockSettingsActivity})` | 焦点不在锁屏，跳过 |

两条日志都带具体值，排查时能直接看到当时的状态。

---

## 五、部署

```sh
adb -s <设备序列号> push phone/deploy.sh /data/local/tmp/
adb -s <设备序列号> shell "sh /data/local/tmp/deploy.sh -p <密码> -w 'OPPO Watch X 4CD8,MOONDROP Pudding'"
```

手机端照旧执行 `一键部署.sh`。本次改动不需要迁移配置，`config.conf` 结构未变。

---

## 六、验证

### 6.1 状态匹配

把候选值直接喂给判定逻辑，设备上实测：

| 输入 | 结果 |
| --- | --- |
| `LOADED,LOADED` | 不拦截 |
| `PIN_REQUIRED,LOADED` | 拦截 |
| `PUK_REQUIRED` | 拦截 |
| `ABSENT,LOADED` | 不拦截 |
| 空值 | 不拦截（靠焦点校验兜底） |

### 6.2 焦点校验

在设备上打开「SIM 卡锁定」设置页时抓取：

```
focus=mCurrentFocus=Window{... com.android.settings/com.android.settings.Settings$IccLockSettingsActivity}
on_keyguard=no
```

说明非锁屏界面会被正确拒绝。

### 6.3 正常解锁不受影响

部署后连续三次熄屏亮屏：

```
17:34:11 inject: watch online + locked (mode=dismiss)
17:34:12 result: unlocked
17:34:20 inject: watch online + locked (mode=dismiss)
17:34:21 result: unlocked
17:34:28 inject: watch online + locked (mode=dismiss)
17:34:29 result: unlocked
```

三次全部成功，没有出现 `skip`，说明锁屏时焦点校验通过、SIM 处于 `LOADED` 不会误拦。

### 6.4 没有实测的部分

真正的 `PIN_REQUIRED` 状态需要给 SIM 卡设置 PIN 并重启才能复现，本次没有在主力卡上操作。判定逻辑已用等价输入验证；如要完整复测，建议用一张不重要的副卡：

1. 设置 → SIM 卡 → 锁定 SIM 卡 → 设置 PIN。
2. 重启，观察日志应为 `skip: sim locked (PIN_REQUIRED,...)`。
3. 手动输入 SIM PIN，进入手机锁屏后熄屏再亮屏，确认自动解锁恢复。

---

## 七、常见问题

**重启后日志一直是 `skip: sim locked`**

SIM PIN 还没输入。手动输完 SIM PIN 后，下一次熄屏亮屏就会自动解锁；脚本不会替用户输 SIM PIN，这是刻意设计。

**没启用 SIM PIN，为什么也出现 `skip: not on keyguard`**

当时焦点不在锁屏，可能停在设置页、系统弹窗或桌面上。日志会带具体焦点窗口，按需判断。若属于误判，把该窗口名加进 `on_keyguard` 的白名单即可。

**`gsm.sim.state` 在某些 ROM 上不存在怎么办**

属性为空时 `sim_locked` 返回"不锁定"，此时完全依赖焦点校验。两层设计就是为了这种情况。

**会不会增加解锁延迟**

`getprop` 约 10ms，`dumpsys window` 约 0.1 秒，正常解锁仍在 1 秒左右。

**SIM PIN 界面能不能自动输入**

不建议。SIM PIN 与手机 PIN 是两套凭证，脚本没有存储 SIM PIN；自动输入会扩大风险面，而重启本身是低频事件。

---

## 八、限制

1. 重启后需要用户手动输入一次 SIM PIN，之后自动解锁才会恢复；这是因为守护进程只响应亮屏事件，SIM PIN 输入完成时不会产生新事件。
2. SIM 状态属性依赖 RIL 上报，极少数 ROM 可能缺失，此时只剩焦点校验。
3. 焦点白名单按 MIUI / AOSP 常见窗口名编写。遇到其它 ROM 的锁屏窗口名，需要补充正则。

---

## 附录：命令速查

```sh
# 当前 SIM 状态
adb shell getprop gsm.sim.state

# 当前焦点窗口
adb shell "dumpsys window | grep -m1 mCurrentFocus"

# 最近日志
adb shell "tail -n 30 /sdcard/watch-unlock/wu.log"
```
