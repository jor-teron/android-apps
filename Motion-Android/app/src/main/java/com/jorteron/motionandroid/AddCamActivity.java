package com.jorteron.motionandroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Main mode: pair a Remote Cam by QR scan or by typing its address, port and pairing code. */
public class AddCamActivity extends Activity {
    private EditText name, hosts, port, token;
    private TextView msg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Add Remote Cam", 24, Ui.ACCENT));
        col.addView(Ui.text(this, "Scan the QR code on the Remote Cam, or type what it shows under the code.", 13, Ui.DIM));
        Button scan = Ui.button(this, "Scan QR code", Ui.ACCENT);
        scan.setOnClickListener(v -> startActivityForResult(new Intent(this, ScanActivity.class), 5));
        col.addView(scan);
        name = field(col, "Camera name", "", false);
        hosts = field(col, "IP address (Wi-Fi/LAN or Tailscale 100.x; several separated by commas)", "", false);
        port = field(col, "Port", "8081", true);
        token = field(col, "Pairing code", "", false);
        msg = Ui.text(this, "", 14, 0xFFFF5252);
        col.addView(msg);
        Button save = Ui.button(this, "Save", Ui.ACCENT);
        save.setOnClickListener(v -> save());
        col.addView(save);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
    }

    private EditText field(LinearLayout col, String label, String value, boolean number) {
        col.addView(Ui.text(this, label, 14, Ui.DIM));
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(e);
        return e;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 5 || res != RESULT_OK || data == null) return;
        CamStore.Cam c = CamStore.parseQr(data.getStringExtra("qr"));
        if (c == null) { msg.setText("Not a Motion Android QR code."); return; }
        new CamStore(this).add(c);
        finish();
    }

    private void save() {
        String h = hosts.getText().toString().replace(" ", "");
        String t = token.getText().toString().trim();
        if (h.isEmpty() || t.isEmpty()) { msg.setText("IP address and pairing code are needed."); return; }
        CamStore.Cam c = new CamStore.Cam();
        c.hosts = h.split(",");
        c.token = t;
        String n = name.getText().toString().trim();
        c.name = n.isEmpty() ? "Remote_" + c.hosts[0].replace('.', '_') : n;
        try { c.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { c.port = 8081; }
        new CamStore(this).add(c);
        finish();
    }
}
