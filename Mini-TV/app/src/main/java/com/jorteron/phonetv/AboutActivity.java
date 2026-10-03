package com.jorteron.phonetv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Simple About screen: app name, version, links, how-to. */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = findViewById(R.id.txt_version);
        version.setText("Version " + BuildConfig.VERSION_NAME
                + " (build " + BuildConfig.VERSION_CODE + ")");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }
}
