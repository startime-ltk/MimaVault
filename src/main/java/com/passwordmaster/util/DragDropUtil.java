package com.passwordmaster.util;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragSource;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 拖拽工具：为窗口注册「图片文件拖入」支持
 * - 接受 .png/.jpg/.jpeg/.bmp/.gif/.webp 图片文件（来自文件管理器/桌面）
 * - 拖拽经过时给出视觉反馈（边框高亮 / 光标变化）
 * - 非图片文件单独收集，由调用方提示
 * <p>
 * 使用方式：
 * <pre>
 * DragDropUtil.installImageDropRecursively(getContentPane(), (images, ignored) -> { ... });
 * </pre>
 */
public final class DragDropUtil {

    public static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "bmp", "gif", "webp"};

    /** 拖入回调：images 为图片文件；ignored 为被忽略的非图片文件 */
    public interface ImageDropHandler {
        void onImagesDropped(List<File> images, List<File> ignored);
    }

    private DragDropUtil() {
    }

    /** 判断是否为受支持的图片文件 */
    public static boolean isImageFile(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        String name = f.getName().toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    /** 在单个组件上注册图片拖入 */
    public static void installImageDrop(Component target, ImageDropHandler handler) {
        DropTargetListener listener = createListener(target, handler);
        new DropTarget(target, DnDConstants.ACTION_COPY, listener, true);
    }

    /**
     * 在容器及其全部子组件上递归注册图片拖入。
     * 跳过文本编辑组件（JTextField/JTextArea/JComboBox/JTable 等），
     * 避免覆盖它们内置的文本拖放编辑能力。
     */
    public static void installImageDropRecursively(Container root, ImageDropHandler handler) {
        DropTargetListener listener = createListener(root, handler);
        attach(root, listener);
        for (Component c : root.getComponents()) {
            if (c instanceof Container && !isTextEditComponent(c)) {
                installImageDropRecursively((Container) c, handler);
            }
        }
    }

    private static void attach(Component c, DropTargetListener listener) {
        new DropTarget(c, DnDConstants.ACTION_COPY, listener, true);
    }

    private static boolean isTextEditComponent(Component c) {
        return c instanceof JTextField
                || c instanceof JTextArea
                || c instanceof JComboBox
                || c instanceof JTable
                || c instanceof JSpinner
                || c instanceof JEditorPane
                || c instanceof JTextPane;
    }

    private static DropTargetListener createListener(Component target, ImageDropHandler handler) {
        return new DropTargetAdapter() {

            private boolean highlightOn = false;

            @Override
            public void dragEnter(DropTargetDragEvent e) {
                handleDrag(e);
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                handleDrag(e);
            }

            private void handleDrag(DropTargetDragEvent e) {
                boolean ok = e.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                if (ok) {
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                    if (!highlightOn) {
                        highlightOn = true;
                        highlight(target, true);
                    }
                    target.setCursor(DragSource.DefaultCopyDrop);
                } else {
                    e.rejectDrag();
                    if (highlightOn) {
                        highlightOn = false;
                        highlight(target, false);
                    }
                    target.setCursor(DragSource.DefaultCopyNoDrop);
                }
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                clearVisual();
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = e.getTransferable();
                    List<File> images = new ArrayList<>();
                    List<File> ignored = new ArrayList<>();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (data instanceof List) {
                            for (Object o : (List<?>) data) {
                                if (o instanceof File) {
                                    File f = (File) o;
                                    if (isImageFile(f)) {
                                        images.add(f);
                                    } else {
                                        ignored.add(f);
                                    }
                                }
                            }
                        }
                    }
                    e.dropComplete(true);
                    final List<File> imgs = images;
                    final List<File> ign = ignored;
                    // 在 EDT 上安全回调
                    SwingUtilities.invokeLater(() -> handler.onImagesDropped(imgs, ign));
                } catch (Exception ex) {
                    e.dropComplete(false);
                } finally {
                    clearVisual();
                }
            }

            private void clearVisual() {
                if (highlightOn) {
                    highlightOn = false;
                    highlight(target, false);
                }
                target.setCursor(Cursor.getDefaultCursor());
            }
        };
    }

    /** 视觉反馈：JFrame 用 rootPane 边框高亮，普通组件用背景色 + 光标 */
    private static void highlight(Component target, boolean on) {
        if (target instanceof JFrame) {
            ((JFrame) target).getRootPane().setBorder(on
                    ? BorderFactory.createLineBorder(new Color(0xFFB300), 4)
                    : null);
        } else if (target instanceof JDialog) {
            ((JDialog) target).getRootPane().setBorder(on
                    ? BorderFactory.createLineBorder(new Color(0xFFB300), 4)
                    : null);
        } else {
            target.setBackground(on ? new Color(0xFFF3CD) : null);
        }
    }
}
