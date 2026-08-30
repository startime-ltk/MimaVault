package com.passwordmaster.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片查看器对话框
 * 大图预览，双击可放大/还原
 */
public class ImageViewerDialog extends JDialog {

    private final ImageIcon originalIcon;
    private final JLabel imageLabel;

    public ImageViewerDialog(Window owner, String title, Path imagePath) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        ImageIcon icon = new ImageIcon(imagePath.toString());
        originalIcon = icon;
        imageLabel = new JLabel(icon, SwingConstants.CENTER);
        imageLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // 初始缩放到屏幕可容纳
        int maxW = Math.max(400, Toolkit.getDefaultToolkit().getScreenSize().width - 200);
        int maxH = Math.max(300, Toolkit.getDefaultToolkit().getScreenSize().height - 200);
        imageLabel.setIcon(scaleToFit(icon, maxW, maxH));

        // 双击切换 原始大小 / 适应窗口
        imageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleScale();
                }
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.DARK_GRAY);
        panel.add(imageLabel, BorderLayout.CENTER);
        add(panel);

        JLabel hint = new JLabel("双击切换原始大小/适应窗口", SwingConstants.CENTER);
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        hint.setForeground(Color.GRAY);
        add(hint, BorderLayout.SOUTH);

        setLocationRelativeTo(owner);
        pack();
        setMinimumSize(new Dimension(320, 240));
    }

    private ImageIcon scaleToFit(ImageIcon icon, int maxW, int maxH) {
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        double scale = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int nw = Math.max(1, (int) (w * scale));
        int nh = Math.max(1, (int) (h * scale));
        return new ImageIcon(icon.getImage().getScaledInstance(nw, nh, Image.SCALE_SMOOTH));
    }

    private boolean originalSize = false;

    private void toggleScale() {
        originalSize = !originalSize;
        if (originalSize) {
            imageLabel.setIcon(originalIcon);
        } else {
            int maxW = Math.max(400, Toolkit.getDefaultToolkit().getScreenSize().width - 200);
            int maxH = Math.max(300, Toolkit.getDefaultToolkit().getScreenSize().height - 200);
            imageLabel.setIcon(scaleToFit(originalIcon, maxW, maxH));
        }
        pack();
    }
}
