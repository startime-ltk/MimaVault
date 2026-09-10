package com.mimavault.util;

import com.mimavault.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据目录自选与迁移工具（v8.30.1）
 *
 * 迁移语义（安全优先）：
 *  - 目标目录必须为空或尚不存在；禁止选到当前数据目录内部或程序目录本身上层。
 *  - 迁移 = 将当前数据目录（config.json / MimaVault.db / images / backup / temp / tessdata 等）
 *    整体复制到目标目录 → 写入全局定位文件 → 刷新 AppConfig 路径常量。
 *  - 旧位置数据默认保留（作为核验与回退备份），由用户确认新位置运行正常后手动删除；
 *    程序内不执行删除用户数据操作。
 *  - 生效时机：当前进程已打开的数据库仍指向旧路径，因此提示用户重启程序完成切换。
 */
public final class DataDirectoryUtil {

    private DataDirectoryUtil() {
    }

    /** 校验目标目录是否可作为新的数据目录；返回错误文案，null 表示通过 */
    public static String validateTarget(Path target, Path current) {
        if (target == null) {
            return "未选择目录";
        }
        Path dst = target.toAbsolutePath().normalize();
        Path cur = current.toAbsolutePath().normalize();
        if (dst.equals(cur)) {
            return "所选目录与当前数据目录相同，无需迁移。";
        }
        if (dst.startsWith(cur)) {
            return "不能选择当前数据目录内部的位置作为新数据目录。";
        }
        if (cur.startsWith(dst)) {
            return "不能选择包含当前数据目录的上层位置，否则会造成数据嵌套。";
        }
        if (dst.equals(AppConfig.APP_DIR) || dst.startsWith(AppConfig.APP_DIR)) {
            return "请勿把数据目录选择到程序安装目录内，卸载或升级程序可能导致数据丢失。";
        }
        if (Files.exists(dst)) {
            if (!Files.isDirectory(dst)) {
                return "目标位置不是文件夹。";
            }
            try (Stream<Path> s = Files.list(dst)) {
                List<Path> children = s.limit(2).collect(Collectors.toList());
                if (!children.isEmpty()) {
                    return "目标文件夹必须为空（或尚不存在），请新建空文件夹后再试。";
                }
            } catch (IOException e) {
                return "无法访问目标文件夹：" + e.getMessage();
            }
        }
        return null;
    }

    /**
     * 执行迁移：复制当前数据目录到目标 → 更新定位文件 → 刷新路径常量 → 冗余写回 config.json。
     *
     * @throws IOException 复制或写定位失败（此时旧数据未受影响，可安全重试）
     */
    public static void migrate(Path target) throws IOException {
        Path src = AppConfig.DATA_DIR.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();

        if (!Files.isDirectory(src)) {
            Files.createDirectories(src);
        }
        Files.createDirectories(dst);

        // 1) 复制整个数据目录
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path rel = src.relativize(p);
                Path dest = rel.toString().isEmpty() ? dst : dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }

        // 2) 写入全局定位文件并刷新 AppConfig 路径（新目录成为当前数据目录）
        AppConfig.saveLocator(dst);
        AppConfig.initDataPaths();

        // 3) config.json 冗余记录 dataDir（load/save 使用当前已切换的路径）
        try {
            AppConfig cfg = AppConfig.load();
            cfg.save();
        } catch (Exception ignored) {
            // 冗余字段写入失败不影响主流程
        }
    }
}
