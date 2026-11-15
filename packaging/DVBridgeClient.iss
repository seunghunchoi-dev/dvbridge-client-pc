; DVBridge 클라이언트 PC 설치 파일 (Inno Setup 6). build_pc_setup.bat 가 /DAppVer /DStage /DOutDir 로 컴파일.
; 사용자 계정에만 설치(관리자 권한 필요 없음) → %LOCALAPPDATA%\Programs\DVBridge Client
; 업데이트: AppId가 같아 새 설치 파일을 실행하면 덮어씀. 설정(%APPDATA%\DVBridgeClient)은 건드리지 않음.
; 개인정보 없음: 설정 파일은 설치하지 않으며 호출부호·서버 주소는 처음 실행 때 사용자가 입력.
#ifndef AppVer
  #define AppVer "0.5"
#endif
#ifndef Stage
  #define Stage "C:\dev\dmrc-build\compose\binaries\main\app\DVBridgeClient"
#endif
#ifndef OutDir
  #define OutDir "C:\dev\dmrc-build\setup"
#endif

[Setup]
AppId={{6CE3569B-CFF0-4613-AE6F-2F39F3B87D23}
AppName=DVBridge 클라이언트
AppVersion={#AppVer}
AppVerName=DVBridge 클라이언트 {#AppVer}
AppPublisher=6K2KZR 최승훈
AppCopyright=Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR), MIT License
VersionInfoVersion={#AppVer}.0.0
VersionInfoCompany=6K2KZR 최승훈
VersionInfoDescription=DVBridge 클라이언트 설치
VersionInfoCopyright=Copyright (c) 2026 6K2KZR 최승훈, MIT License
DefaultDirName={autopf}\DVBridge Client
DefaultGroupName=DVBridge 클라이언트
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
LicenseFile=LICENSE.txt
SetupIconFile=..\composeApp\src\desktopMain\resources\app.ico
UninstallDisplayIcon={app}\DVBridgeClient.exe
UninstallDisplayName=DVBridge 클라이언트
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
OutputDir={#OutDir}
OutputBaseFilename=DVBridgeClient-{#AppVer}-setup
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "바탕 화면에 바로 가기 만들기"; GroupDescription: "추가 작업:"

[InstallDelete]
; 이전 버전의 라이브러리 파일(이름에 해시가 붙음)이 쌓이지 않게 app 폴더를 비우고 새로 넣음
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
; 0.4까지의 옛 이름(DVBridge 클라이언트) 실행 파일·바로 가기 정리
Type: files; Name: "{app}\DVBridgeClient.exe"
Type: files; Name: "{app}\DVBridge 클라이언트 설명서.pdf"
Type: filesandordirs; Name: "{autoprograms}\DVBridge 클라이언트"
Type: files; Name: "{autodesktop}\DVBridge 클라이언트.lnk"

[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD_PARTY_NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "LICENSES\*"; DestDir: "{app}\LICENSES"; Flags: ignoreversion
#ifdef Manual
Source: "{#Manual}"; DestDir: "{app}"; DestName: "DVBridge 클라이언트 설명서.pdf"; Flags: ignoreversion
#endif

[Icons]
Name: "{group}\DVBridge 클라이언트"; Filename: "{app}\DVBridgeClient.exe"
#ifdef Manual
Name: "{group}\DVBridge 클라이언트 설명서"; Filename: "{app}\DVBridge 클라이언트 설명서.pdf"
#endif
Name: "{group}\DVBridge 클라이언트 제거"; Filename: "{uninstallexe}"
Name: "{autodesktop}\DVBridge 클라이언트"; Filename: "{app}\DVBridgeClient.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\DVBridgeClient.exe"; Description: "DVBridge 클라이언트 실행"; Flags: nowait postinstall skipifsilent
