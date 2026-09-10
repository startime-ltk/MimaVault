package com.mimavault.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.mimavault.R;
import com.mimavault.util.PasswordGenerator;

import java.util.function.Consumer;

/**
 * 密码生成器对话框（纯本地生成，不联网）：
 * 随机密码（长度 / 排除易混淆字符 / 字符类型 / 自定义字符集）+ 口令短语（单词数 / 分隔符 / 首字母大写 / 末尾数字 / 自定义词表）
 */
public final class PasswordGeneratorDialog {

    /** 随机密码最短长度（SeekBar 0 对应值） */
    private static final int MIN_LENGTH = 8;
    /** 口令短语最少单词数（SeekBar 0 对应值） */
    private static final int MIN_WORDS = 2;

    private PasswordGeneratorDialog() {
    }

    /**
     * 弹出生成器对话框；用户点击「使用此密码」后回调结果
     *
     * @param activity 宿主 Activity
     * @param onUse    结果回调（在 UI 线程执行）
     */
    public static void show(Activity activity, Consumer<String> onUse) {
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_password_generator, null, false);

        final RadioButton rbRandom = view.findViewById(R.id.rbRandom);
        final RadioButton rbPassphrase = view.findViewById(R.id.rbPassphrase);
        final View panelRandom = view.findViewById(R.id.panelRandom);
        final View panelPassphrase = view.findViewById(R.id.panelPassphrase);
        final TextView tvPreview = view.findViewById(R.id.tvPreview);

        final Runnable regenerate = () -> {
            updateLabels(view);
            tvPreview.setText(build(view));
        };

        // 模式切换
        rbRandom.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                panelRandom.setVisibility(View.VISIBLE);
                panelPassphrase.setVisibility(View.GONE);
                regenerate.run();
            }
        });
        rbPassphrase.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                panelRandom.setVisibility(View.GONE);
                panelPassphrase.setVisibility(View.VISIBLE);
                regenerate.run();
            }
        });

        // 参数变化 → 实时重算预览
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                regenerate.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        ((SeekBar) view.findViewById(R.id.seekLength)).setOnSeekBarChangeListener(seekListener);
        ((SeekBar) view.findViewById(R.id.seekWords)).setOnSeekBarChangeListener(seekListener);

        int[] checkIds = {R.id.cbExcludeAmbiguous, R.id.cbUpper, R.id.cbLower, R.id.cbDigits,
                R.id.cbSymbols, R.id.cbCapitalize, R.id.cbAppendNumber};
        for (int id : checkIds) {
            ((CheckBox) view.findViewById(id)).setOnCheckedChangeListener(
                    (b, checked) -> regenerate.run());
        }

        int[] editIds = {R.id.etCustomCharset, R.id.etSeparator, R.id.etCustomWords};
        for (int id : editIds) {
            ((EditText) view.findViewById(id)).addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    regenerate.run();
                }
            });
        }

        view.findViewById(R.id.btnRegen).setOnClickListener(v -> {
            String pwd = build(view);
            tvPreview.setText(pwd);
        });

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.gen_title)
                .setView(view)
                .setPositiveButton(R.string.gen_use, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String pwd = tvPreview.getText().toString();
                if (pwd == null || pwd.isEmpty()) {
                    pwd = build(view);
                }
                if (onUse != null && !pwd.isEmpty()) {
                    onUse.accept(pwd);
                }
                dialog.dismiss();
            });
            regenerate.run();
        });
        dialog.show();
    }

    /** 按当前界面参数生成密码 / 口令短语（选项非法时回退默认值） */
    private static String build(View v) {
        if (((RadioButton) v.findViewById(R.id.rbPassphrase)).isChecked()) {
            PasswordGenerator.PassphraseOptions o = new PasswordGenerator.PassphraseOptions();
            o.words = ((SeekBar) v.findViewById(R.id.seekWords)).getProgress() + MIN_WORDS;
            String sep = ((EditText) v.findViewById(R.id.etSeparator)).getText().toString();
            o.separator = sep.isEmpty() ? "-" : sep;
            o.capitalize = ((CheckBox) v.findViewById(R.id.cbCapitalize)).isChecked();
            o.appendNumber = ((CheckBox) v.findViewById(R.id.cbAppendNumber)).isChecked();
            o.customWords = ((EditText) v.findViewById(R.id.etCustomWords)).getText().toString();
            return PasswordGenerator.generatePassphrase(o);
        }
        PasswordGenerator.Options o = new PasswordGenerator.Options();
        o.length = ((SeekBar) v.findViewById(R.id.seekLength)).getProgress() + MIN_LENGTH;
        o.excludeAmbiguous = ((CheckBox) v.findViewById(R.id.cbExcludeAmbiguous)).isChecked();
        o.useUpper = ((CheckBox) v.findViewById(R.id.cbUpper)).isChecked();
        o.useLower = ((CheckBox) v.findViewById(R.id.cbLower)).isChecked();
        o.useDigits = ((CheckBox) v.findViewById(R.id.cbDigits)).isChecked();
        o.useSymbols = ((CheckBox) v.findViewById(R.id.cbSymbols)).isChecked();
        o.customCharset = ((EditText) v.findViewById(R.id.etCustomCharset)).getText().toString();
        return PasswordGenerator.generate(o);
    }

    /** 刷新长度 / 单词数标签 */
    private static void updateLabels(View v) {
        SeekBar length = v.findViewById(R.id.seekLength);
        ((TextView) v.findViewById(R.id.tvLengthLabel))
                .setText(v.getContext().getString(R.string.gen_length_label, length.getProgress() + MIN_LENGTH));
        SeekBar words = v.findViewById(R.id.seekWords);
        ((TextView) v.findViewById(R.id.tvWordsLabel))
                .setText(v.getContext().getString(R.string.gen_words_label, words.getProgress() + MIN_WORDS));
    }
}
