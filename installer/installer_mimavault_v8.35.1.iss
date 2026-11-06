; ============================================================
; 密匣 MimaVault 安装脚本 (v8.35.1)
; 说明：
;  - 安装目录默认自动落在非系统盘（本机 F:\Programs\密匣 MimaVault）
;  - DisableDirPage=no：显式显示"选择安装位置"页，用户可改到任意位置
;  - 若用户仍选了系统盘，会弹确认框提醒（默认按钮为"否"）
;  - 权限为当前用户级（PrivilegesRequired=lowest），无需管理员即可安装
;  - 数据目录与安装目录解耦：程序首次运行默认把数据写到 {app}\data，
;    用户可在主界面「数据目录」中把数据迁移到任意位置
;  - v8.35.1 新增：App 内检查更新（多源：CDN/镜像/直连 国内加速）、
;    条目收藏与使用次数（收藏列 ☆ 金色标识、点开详情累计次数、次数列展示）、
;    主列表四梯队排序（收藏☆ → 热门Top3* → 字母拼音 → #特殊符号）
; ============================================================
[Setup]
AppName=密匣 MimaVault
AppVersion=8.35.1
AppPublisher=startime-ltk
DefaultDirName={code:GetDefaultInstallDir}
PrivilegesRequired=lowest
DisableDirPage=no
DisableProgramGroupPage=yes
OutputDir=F:\项目成品安装包以及源码\密码大师 MimaVault\成品安装包
OutputBaseFilename=密匣MimaVault安装程序_v8.35.1
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=密匣 MimaVault

[Files]
; Source 指向 F 盘上的干净 app-image（不再依赖 C 盘临时目录，临时目录被清理后会打出坏包）
Source: "F:\项目成品安装包以及源码\密码大师 MimaVault\成品安装包\_v8351\密匣 MimaVault\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"
Name: "runapp"; Description: "安装完成后立即运行密匣 MimaVault"; GroupDescription: "附加任务:"; Flags: unchecked

[Icons]
Name: "{autoprograms}\密匣 MimaVault"; Filename: "{app}\密匣 MimaVault.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\密匣 MimaVault"; Filename: "{app}\密匣 MimaVault.exe"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{autoprograms}\卸载密匣 MimaVault"; Filename: "{app}\Uninstall 密匣 MimaVault.exe"

[Run]
Filename: "{app}\密匣 MimaVault.exe"; Description: "立即运行密匣 MimaVault"; Flags: nowait postinstall skipifsilent; Tasks: runapp

; 默认装到非系统盘；用户仍可在「选择目标位置」页自行修改
[Code]
const
  AppFolderName = '密匣 MimaVault';

function GetDefaultInstallDir(Param: String): String;
var
  I: Integer;
  D: String;
  Drives: String;
begin
  Drives := 'FDEGHIJKLMNOPQRSTUVWXYZ';
  for I := 1 to Length(Drives) do
  begin
    D := Drives[I] + ':';
    if DirExists(D + '\') then
    begin
      Result := D + '\Programs\' + AppFolderName;
      Exit;
    end;
  end;
  Result := ExpandConstant('{autopf}') + '\' + AppFolderName;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  SysDrive: String;
begin
  Result := True;
  if CurPageID = wpSelectDir then
  begin
    SysDrive := Copy(ExpandConstant('{win}'), 1, 2);
    if CompareText(Copy(WizardForm.DirEdit.Text, 1, 2), SysDrive) = 0 then
    begin
      Result := MsgBox(
        '你选择的位置在系统盘 ' + SysDrive + ' 上。' + #13#10 + #13#10 +
        '建议改到其它盘（例如 F:\Programs\...），避免占用系统盘空间。' + #13#10 +
        '仍要装到系统盘吗？',
        mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
    end;
  end;
end;
