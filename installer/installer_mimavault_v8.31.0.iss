; ============================================================
; 密匣 MimaVault 安装脚本 (v8.31.0)
; 说明：
;  - 安装目录默认可由用户修改（未设置 DisableDirPage，Wizard 默认显示"选择安装位置"页）
;  - DefaultDirName 仅在用户未手动更改时使用，安装过程中仍可随时改到任意位置
;  - 权限为当前用户级（PrivilegesRequired=lowest），无需管理员即可安装
;  - 数据目录与安装目录解耦：程序首次运行默认把数据写到 {app}\data，
;    用户可在主界面「数据目录」中把数据迁移到任意位置
;  - v8.31.0 新增：动态验证码（TOTP，RFC 6238 纯离线生成 / 展示 / 一键复制，
;    与安卓端 TotpUtil 行为一致）、条目编辑页绑定与解绑验证器、CSV 导入识别 2FA 列
; ============================================================
[Setup]
AppName=密匣 MimaVault
AppVersion=8.31.0
AppPublisher=startime-ltk
DefaultDirName={localappdata}\Programs\MimaVault
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir=F:\项目成品安装包以及源码\密码大师 MimaVault\成品安装包
OutputBaseFilename=密匣MimaVault安装程序_v8.31.0
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=密匣 MimaVault

[Files]
; Source 指向 jpackage 生成的 app-image（每次打包后由 F:\Java\work\MimaVault\target\MimaVault.jar 重新生成）
Source: "C:\Users\25323\AppData\Roaming\Tencent\Marvis\User\427DB28A6D593974C57919E2E330E344\workspace\conv_19fa4164394_39e9fc071869\temp\jpackage-out-v8310\密匣 MimaVault\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"
Name: "runapp"; Description: "安装完成后立即运行密匣 MimaVault"; GroupDescription: "附加任务:"; Flags: unchecked

[Icons]
Name: "{autoprograms}\密匣 MimaVault"; Filename: "{app}\密匣 MimaVault.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\密匣 MimaVault"; Filename: "{app}\密匣 MimaVault.exe"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{autoprograms}\卸载密匣 MimaVault"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\密匣 MimaVault.exe"; Description: "立即运行密匣 MimaVault"; Flags: nowait postinstall skipifsilent; Tasks: runapp
