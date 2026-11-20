package com.palette.unlock;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 极简设置页：显示已生效的钩子与取证结论。开关走 LSPosed 远程偏好。 */
public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setText("调色盘强开 v1.0\n\n"
                + "作用域：com.oplus.camera\n\n"
                + "强制开关：\n"
                + "  com.oplus.feature.color.palette.support\n"
                + "  com.oplus.camera.basic.tone.lut.support\n"
                + "  com.oplus.palette.capture.enable\n"
                + "能力位：\n"
                + "  key_support_palette\n\n"
                + "路径重定向：" + (Prefs.redirectPath() ? "开" : "关（ROM 自带 palette 资源，无需开）") + "\n\n"
                + "日志：/sdcard/Android/data/com.oplus.camera/files/palette_unlock.log");
        root.addView(tv);
        setContentView(root);
    }
}
