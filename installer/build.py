# -*- coding: utf-8 -*-
"""密匣 MimaVault 桌面版打包脚本（Windows）。

用法：
    python build.py 8.35.2

流程：
    1. 准备 jpackage 输入目录（fat jar + JNativeHook 原生库）
    2. jpackage --type app-image 生成免安装版 -> 成品安装包/_v<版本去点>
    3. 以上一版 installer_*.iss 为模板生成新版本 .iss
    4. Inno Setup 编译出安装包

注意：
  - 密匣的运行时已经包含 jdk.crypto.ec（糖伴 1.3.6 踩过的坑），这里不用额外加模块；
    若以后精简运行时，务必保留 jdk.crypto.ec，否则检查更新会 TLS 握手中失败。
  - 数据目录与安装目录解耦（AppConfig 的定位文件），所以覆盖安装不会动数据。
"""
import os
import re
import shutil
import subprocess
import sys

REPO = r"F:\Java\work\MimaVault"
OUT = r"F:\项目成品安装包以及源码\密码大师 MimaVault\成品安装包"
INSTALLER_DIR = os.path.join(REPO, "installer")
ISCC = r"F:\Inno Setup 7\ISCC.exe"
ICON = os.path.join(REPO, "data", "images", "密匣logo.ico")
DLL_SOURCES = [
    r"F:\Programs\密匣 MimaVault\app\JNativeHook.x86_64.dll",
    os.path.join(OUT, "_v8351", "密匣 MimaVault", "app", "JNativeHook.x86_64.dll"),
]
TEMPLATE_ISS = os.path.join(INSTALLER_DIR, "installer_mimavault_v8.35.1.iss")


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "8.35.2"
    short = version.replace(".", "")
    dest = os.path.join(OUT, "_v" + short)
    jar = os.path.join(REPO, "target", "MimaVault.jar")
    if not os.path.exists(jar):
        print("!! 找不到 %s，请先执行 mvn clean package" % jar)
        return 1

    # 1) 输入目录
    inp = os.path.join(REPO, "target", "jpackage-input")
    shutil.rmtree(inp, ignore_errors=True)
    os.makedirs(inp, exist_ok=True)
    shutil.copy(jar, inp)
    dll = next((p for p in DLL_SOURCES if os.path.exists(p)), None)
    if dll is None:
        print("!! 找不到 JNativeHook.x86_64.dll，全局快捷键会不可用")
        return 1
    shutil.copy(dll, inp)
    print("输入目录：%s（含 %s）" % (inp, os.path.basename(dll)), flush=True)

    # 2) app-image
    shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest, exist_ok=True)
    cmd = [
        "jpackage", "--type", "app-image",
        "--input", inp,
        "--main-jar", "MimaVault.jar",
        "--main-class", "com.mimavault.Main",
        "--name", "密匣 MimaVault",
        "--app-version", version,
        "--dest", dest,
    ]
    if os.path.exists(ICON):
        cmd += ["--icon", ICON]
    print("=== jpackage app-image %s ===" % version, flush=True)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=900)
    print("  返回码", r.returncode, (r.stdout + r.stderr)[-500:], flush=True)
    if r.returncode != 0:
        return 1

    # 3) 生成新 .iss
    text = open(TEMPLATE_ISS, encoding="utf-8").read()
    text = text.replace("(v8.35.1)", "(v%s)" % version)
    text = re.sub(r"AppVersion=8\.35\.1", "AppVersion=%s" % version, text)
    text = re.sub(r"OutputBaseFilename=密匣MimaVault安装程序_v8\.35\.1",
                  "OutputBaseFilename=密匣MimaVault安装程序_v%s" % version, text)
    text = text.replace("_v8351", "_v" + short)
    text = text.replace(
        ";    主列表四梯队排序（收藏☆ → 热门Top3* → 字母拼音 → #特殊符号）\n",
        ";    主列表四梯队排序（收藏☆ → 热门Top3* → 字母拼音 → #特殊符号）\n"
        ";  - v%s 新增：数据目录丢失时不再静默覆盖定位文件，启动前弹窗提示并可重新指定\n"
        ";    （修复：数据目录被移动后，应用静默回退空库并改写定位文件，界面显示 0 条）\n" % version)
    iss = os.path.join(INSTALLER_DIR, "installer_mimavault_v%s.iss" % version)
    open(iss, "w", encoding="utf-8").write(text)
    print("生成安装脚本：%s" % iss, flush=True)

    # 4) Inno 编译
    print("=== Inno Setup 编译 ===", flush=True)
    r2 = subprocess.run([ISCC, "/O" + OUT, iss], capture_output=True, text=True,
                        encoding="utf-8", errors="replace", timeout=900)
    print("  返回码", r2.returncode, (r2.stdout + r2.stderr)[-500:], flush=True)

    exe = os.path.join(OUT, "密匣MimaVault安装程序_v%s.exe" % version)
    print("=== 产物 ===")
    print("  app-image :", dest)
    print("  安装包    :", exe, os.path.getsize(exe) if os.path.exists(exe) else "缺失")
    return 0


if __name__ == "__main__":
    sys.exit(main())
