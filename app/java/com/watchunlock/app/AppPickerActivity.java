package com.watchunlock.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 三级页面：读取已安装应用并添加到候选列表 */
public class AppPickerActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_ACCENT = 0xFF2563EB;

    private EditText search;
    private Switch launcherOnly;
    private TextView tabUser;
    private TextView tabSys;
    private TextView statusText;
    private Button reloadBtn;
    private ViewFlipperHolder holder;

    private final List<AppEntry> all = new ArrayList<>();
    private final Set<String> added = new HashSet<>();
    private AppAdapter userAdapter;
    private AppAdapter sysAdapter;
    private volatile boolean loading = false;
    private boolean firstTip = true;

    private static class AppEntry {
        String label;
        String pkg;
        boolean system;
        boolean launchable;
    }

    /** 避免和 android.widget.ViewFlipper 同名混淆 */
    private static class ViewFlipperHolder {
        android.widget.ViewFlipper flipper;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        setTitle(I18n.t("选择应用", "Choose an app"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(cardBg(0xFFFFFFFF));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabUser = tab(I18n.t("第三方应用", "Third-party"));
        tabSys = tab(I18n.t("系统应用", "System"));
        tabs.addView(tabUser, weight());
        tabs.addView(tabSys, weight());
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        search = new EditText(this);
        search.setHint(I18n.t("搜索应用名称或包名", "Search name or package"));
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(cardBg(0xFFFFFFFF));
        search.setPadding(dp(12), 0, dp(12), 0);
        root.addView(search, gap(8, -1, dp(42)));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(6), 0, dp(2), 0);
        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(C_SUB);
        statusRow.addView(statusText, weight());
        reloadBtn = new Button(this);
        reloadBtn.setText(I18n.t("重新读取", "Reload"));
        reloadBtn.setTextSize(12);
        reloadBtn.setOnClickListener(v -> load());
        statusRow.addView(reloadBtn);
        root.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

        TextView tip = new TextView(this);
        tip.setText(I18n.t(
                "首次进入可能需要在系统弹窗中允许「读取应用列表」；"
                        + "若列表为空或只显示部分应用，点「重新读取」，不必退出重进。",
                "On first entry the system may ask to allow reading the app list. If the list is empty "
                        + "or incomplete, tap Reload instead of leaving and re-entering."));
        tip.setTextSize(11);
        tip.setTextColor(C_SUB);
        tip.setPadding(dp(6), dp(2), dp(6), dp(4));
        root.addView(tip);

        LinearLayout filter = new LinearLayout(this);
        filter.setOrientation(LinearLayout.HORIZONTAL);
        filter.setGravity(Gravity.CENTER_VERTICAL);
        filter.setPadding(dp(4), dp(4), dp(4), dp(4));
        launcherOnly = new Switch(this);
        launcherOnly.setText(I18n.t("仅显示桌面可启动的应用", "Only apps with a launcher icon"));
        launcherOnly.setTextSize(13);
        launcherOnly.setChecked(true);
        filter.addView(launcherOnly, weight());
        root.addView(filter, new LinearLayout.LayoutParams(-1, -2));

        android.widget.ViewFlipper flipper = new android.widget.ViewFlipper(this);
        ListView userList = list();
        ListView sysList = list();
        flipper.addView(userList, new LinearLayout.LayoutParams(-1, -1));
        flipper.addView(sysList, new LinearLayout.LayoutParams(-1, -1));
        root.addView(flipper, new LinearLayout.LayoutParams(-1, 0, 1));

        holder = new ViewFlipperHolder();
        holder.flipper = flipper;

        userAdapter = new AppAdapter(true);
        sysAdapter = new AppAdapter(false);
        userList.setAdapter(userAdapter);
        sysList.setAdapter(sysAdapter);

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < dp(60) || Math.abs(vx) < 200) return false;
                showPage(dx < 0 ? 1 : 0);
                return true;
            }
        });
        for (ListView lv : new ListView[]{userList, sysList}) {
            lv.setOnTouchListener((v, ev) -> {
                gd.onTouchEvent(ev);
                return false;
            });
        }

        tabUser.setOnClickListener(v -> showPage(0));
        tabSys.setOnClickListener(v -> showPage(1));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                userAdapter.rebuild();
                sysAdapter.rebuild();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        launcherOnly.setOnCheckedChangeListener((v, checked) -> {
            userAdapter.rebuild();
            sysAdapter.rebuild();
        });

        Button done = new Button(this);
        done.setText(I18n.t("完成", "Done"));
        done.setTextSize(15);
        done.setOnClickListener(v -> finish());
        root.addView(done, gap(8, -1, dp(44)));

        setContentView(root);
        showPage(0);
    }

    private void load() {
        if (loading) return;
        loading = true;
        if (statusText != null) {
            statusText.setText(I18n.t("正在读取应用列表…", "Reading the app list…"));
        }
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(0);
            Collator collator = Collator.getInstance(Locale.CHINA);
            List<AppEntry> list = new ArrayList<>();
            for (ApplicationInfo ai : infos) {
                AppEntry e = new AppEntry();
                CharSequence label = pm.getApplicationLabel(ai);
                e.label = label == null || label.length() == 0 ? ai.packageName : label.toString();
                e.pkg = ai.packageName;
                e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                try {
                    e.launchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
                } catch (Throwable ignored) {
                    e.launchable = false;
                }
                list.add(e);
            }
            list.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                loading = false;
                all.clear();
                all.addAll(list);
                rebuildAdded();
                int user = 0;
                for (AppEntry e : all) if (!e.system) user++;
                if (statusText != null) {
                    statusText.setText(all.isEmpty()
                            ? I18n.t("未读到应用：请点「重新读取」；仍为空请到系统设置允许读取应用列表",
                            "No apps read. Tap Reload; if it stays empty, allow app-list access in system settings")
                            : I18n.t("共 %d 个应用（第三方 %d / 系统 %d）",
                            "%d apps (third-party %d / system %d)", all.size(), user, all.size() - user));
                }
                userAdapter.rebuild();
                sysAdapter.rebuild();
                firstTip = false;
            });
        }, "app-list").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统「应用列表」授权弹窗返回后立即刷新，不需要退出重进
        rebuildAdded();
        if (userAdapter != null) {
            userAdapter.notifyDataSetChanged();
            sysAdapter.notifyDataSetChanged();
        }
        load();
    }

    private void rebuildAdded() {
        added.clear();
        for (String s : Prefs.musicApps(this).split("\n")) {
            String t = s.trim();
            if (!t.isEmpty()) added.add(t);
        }
    }

    private void showPage(int i) {
        holder.flipper.setDisplayedChild(i);
        tabUser.setTextColor(i == 0 ? C_ACCENT : C_SUB);
        tabUser.setTypeface(null, i == 0 ? Typeface.BOLD : Typeface.NORMAL);
        tabSys.setTextColor(i == 1 ? C_ACCENT : C_SUB);
        tabSys.setTypeface(null, i == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabUser.setBackground(underline(i == 0));
        tabSys.setBackground(underline(i == 1));
    }

    private void toggle(AppEntry e) {
        if (added.contains(e.pkg)) {
            Prefs.removeMusicApp(this, e.pkg);
            added.remove(e.pkg);
            toast(I18n.t("已移除：%s", "Removed: %s", e.label));
        } else {
            Prefs.addMusicApp(this, e.pkg);
            added.add(e.pkg);
            toast(I18n.t("已添加：%s", "Added: %s", e.label));
        }
        userAdapter.notifyDataSetChanged();
        sysAdapter.notifyDataSetChanged();
    }

    private ListView list() {
        ListView lv = new ListView(this);
        lv.setDividerHeight(0);
        lv.setBackgroundColor(C_BG);
        lv.setPadding(0, dp(6), 0, dp(6));
        lv.setClipToPadding(false);
        return lv;
    }

    private class AppAdapter extends BaseAdapter {
        private final boolean userPage;
        private final List<AppEntry> shown = new ArrayList<>();

        AppAdapter(boolean userPage) {
            this.userPage = userPage;
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /** 预先算好当前页的可见列表，避免 getCount/getItem 每次都全量过滤 */
        void rebuild() {
            shown.clear();
            String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean onlyLaunch = launcherOnly == null || launcherOnly.isChecked();
            for (AppEntry e : all) {
                if (e.system != !userPage) continue;
                if (onlyLaunch && !e.launchable) continue;
                if (!q.isEmpty()
                        && !e.label.toLowerCase(Locale.ROOT).contains(q)
                        && !e.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(e);
            }
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                LinearLayout box = new LinearLayout(AppPickerActivity.this);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setBackground(cardBg(0xFFFFFFFF));
                box.setPadding(dp(14), dp(10), dp(14), dp(10));

                LinearLayout texts = new LinearLayout(AppPickerActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView label = new TextView(AppPickerActivity.this);
                label.setTextSize(15);
                label.setTextColor(C_TEXT);
                TextView pkg = new TextView(AppPickerActivity.this);
                pkg.setTextSize(11);
                pkg.setTextColor(C_SUB);
                pkg.setPadding(0, dp(2), 0, 0);
                texts.addView(label);
                texts.addView(pkg);
                box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

                TextView mark = new TextView(AppPickerActivity.this);
                mark.setTextSize(12);
                mark.setPadding(dp(10), dp(4), dp(10), dp(4));
                box.addView(mark);

                row = new Row(box, label, pkg, mark);
                box.setTag(row);
                int p = dp(14);
                box.setPadding(p, dp(10), p, dp(10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                box.setLayoutParams(lp);
                convertView = box;
            } else {
                row = (Row) convertView.getTag();
            }
            AppEntry e = getItem(position);
            row.label.setText(e.label);
            row.pkg.setText(e.pkg);
            boolean on = added.contains(e.pkg);
            row.mark.setText(on ? I18n.t("已添加", "Added") : I18n.t("添加", "Add"));
            row.mark.setTextColor(on ? 0xFF059669 : C_ACCENT);
            row.box.setOnClickListener(v -> toggle(e));
            return convertView;
        }
    }

    private static class Row {
        LinearLayout box;
        TextView label;
        TextView pkg;
        TextView mark;

        Row(LinearLayout box, TextView label, TextView pkg, TextView mark) {
            this.box = box;
            this.label = label;
            this.pkg = pkg;
            this.mark = mark;
        }
    }

    private TextView tab(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(9), 0, dp(9));
        return t;
    }

    private GradientDrawable underline(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(8));
        if (on) d.setStroke(dp(2), C_ACCENT);
        return d;
    }

    private GradientDrawable cardBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private LinearLayout.LayoutParams gap(int top, int width, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(top);
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
