package com.mimavault.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mimavault.R;
import com.mimavault.util.GestureParser;

/**
 * 九宫格手势录入（与 PC 端一致）
 */
public class GestureActivity extends AppCompatActivity {

    public static final String EXTRA_SEQ = "seq";
    public static final String EXTRA_INITIAL = "initial";

    private GestureView gestureView;
    private TextView tvHint;
    private Button btnClear;
    private String current = "";
    private String initial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gesture);
        initial = getIntent().getStringExtra(EXTRA_INITIAL);
        gestureView = findViewById(R.id.gestureView);
        tvHint = findViewById(R.id.tvGestureHint);
        btnClear = findViewById(R.id.btnGestureClear);

        if (initial != null && !initial.isEmpty()) {
            current = initial;
        }

        gestureView.setCallback(seq -> {
            if (seq.isEmpty()) {
                Toast.makeText(this, "至少连接 4 个点", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!GestureParser.valid(seq)) {
                Toast.makeText(this, "手势序列无效", Toast.LENGTH_SHORT).show();
                return;
            }
            current = seq;
            tvHint.setText("已录入：" + GestureParser.describe(seq));
            Toast.makeText(this, "手势已录入，点击右上角完成", Toast.LENGTH_SHORT).show();
        });

        btnClear.setOnClickListener(v -> {
            current = "";
            gestureView.reset();
            tvHint.setText("请滑动连接至少 4 个点");
        });

        findViewById(R.id.btnGestureDone).setOnClickListener(v -> {
            if (current.isEmpty()) {
                Toast.makeText(this, "请先录入手势", Toast.LENGTH_SHORT).show();
                return;
            }
            setResult(RESULT_OK, new android.content.Intent().putExtra(EXTRA_SEQ, current));
            finish();
        });
    }
}
