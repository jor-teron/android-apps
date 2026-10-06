package com.jorteron.phonetv;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

/** About screen: icon, name, how-to, and credit / version / release date / package. No links. */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        String versionName = BuildConfig.VERSION_NAME;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        setRow(R.id.txt_credit, getString(R.string.credit_label), getString(R.string.credit_value));
        setRow(R.id.txt_version, getString(R.string.version_label), versionName);
        setRow(R.id.txt_release, getString(R.string.release_label), BuildConfig.BUILD_DATE);
        setRow(R.id.txt_package, getString(R.string.package_label), getPackageName());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void setRow(int viewId, String label, String value) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(label).append(": ");
        sb.setSpan(new ForegroundColorSpan(0xB3FFFFFF), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append(value);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) findViewById(viewId)).setText(sb);
    }
}
