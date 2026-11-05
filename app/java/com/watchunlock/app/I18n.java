package com.watchunlock.app;

import android.content.Context;
import java.util.Locale;

/**
 * 界面语言：默认跟随系统（系统语言不是中文时回落英文），
 * 也可在应用内强制中文 / 英文。
 */
public final class I18n {

    /** 当前是否为英文；由 init() / apply() 更新 */
    public static boolean EN = false;

    private I18n() {
    }

    /** 应用启动时调用，读取已保存的语言偏好 */
    public static void init(Context c) {
        apply(Prefs.lang(c));
    }

    /** lang："" = 跟随系统；"zh" / "en" = 强制 */
    public static void apply(String lang) {
        if ("en".equals(lang)) {
            EN = true;
        } else if ("zh".equals(lang)) {
            EN = false;
        } else {
            // 系统语言不是中文（含无法识别的情况）一律用英文
            EN = !"zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
        }
    }

    public static String t(String zh, String en) {
        return EN ? en : zh;
    }

    public static String t(String zh, String en, Object... args) {
        return String.format(EN ? en : zh, args);
    }
}
