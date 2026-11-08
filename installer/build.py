# -*- coding: utf-8 -*-
"""密匣 MimaVault 桌面版打包脚本（Windows）。

用法：
    python build.py 8.35.4

流程：
    1. 准备 jpackage 输入目录（fat jar + JNativeHook 原生库）
    2. jpackage --type app-image 生成免安装版 -> 成品安装包/_v<版本去点>
    3. 以上一版 installer_*.iss 为模板生成新版本 .iss
    4. Inno Setup 编译出安装包

注意：
  - 密匣的运行时已经包含 jdk.crypto.ec（糖伴 1.3.6 踩过的坑），这里不用额外加模块；
    若以后精简运行时，务必保留 jdk.crypto.ec，否则检查更新会 TLS 握手中失败。
  - 数据目录与安装目录解耦（AppConfig 的定位文件），所以覆盖安装不会动数据。
  - 从 v8.35.4 起，App 内「立即更新」会用 /SILENT 调本安装包做覆盖安装，
    安装脚本 [Run] 里 Check: IsSilentInstall 那一条负责装完自动重开新版本；
    因此模板里这两处不能删，否则用户点完更新程序会「消失」。
  - 打完包发布时，把安装包的 sha256 写进 output/version.txt 第三段，
    App 下载完会比对，不一致就中止，不会拿半截/被篡改的包去覆盖安装。
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
TEMPLATE_ISS = os.path.join(INSTALLER_DIR, "installer_mimavault_v8.35.4.iss")
TEMPLATE_VERSION = "8.35.4"


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else TEMPLATE_VERSION
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

    # 3) 生成新 .iss：以 v8.35.4 为模板，只做版本号替换，
    #    保留 [Run] 的静默自动重开与 [Code] 的 IsSilentInstall（自动更新链路依赖）
    text = open(TEMPLATE_ISS, encoding="utf-8").read()
    text = text.replace(TEMPLATE_VERSION, version)
    text = re.sub(r"(_v)\d+(\\密匣 MimaVault)",
                  lambda m: m.group(1) + short + m.group(2), text)
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
