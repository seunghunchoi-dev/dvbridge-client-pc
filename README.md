# DMRBridge 클라이언트 — PC (Windows)

아마추어 무선 DMR 중계 서버 **DMRBridge**에 붙어서 DMR 무전(BrandMeister 등)을 PC로 듣고 말하는 프로그램입니다.
넓은 화면에 맞게 왼쪽은 말하기, 오른쪽은 슬롯·채널·설정을 함께 보여 줍니다.

- 제작자: **6K2KZR 최승훈** · 라이선스: [MIT](LICENSE)
- 안드로이드판: [dmrbridge-client-android](https://github.com/seunghunchoi-dev/dmrbridge-client-android) — 화면과 통신 코드가 같습니다
- 설명서: [Wiki](https://github.com/seunghunchoi-dev/dmrbridge-client-pc/wiki)
- 내려받기: [Releases](https://github.com/seunghunchoi-dev/dmrbridge-client-pc/releases) (설치 파일)

## 할 수 있는 것
- 스페이스바 또는 큰 버튼으로 말하기
- 상대방 콜사인·이름·지역·무전기 이름표 보기
- 채널 슬롯 — 개수 제한 없음, 즐겨찾기 ★, 그룹, 검색
- 번호로 채널 바꾸기, 타임슬롯 1/2, 브릿지 내부 채널
- 소리 장치 선택 — 처음 값은 Windows "기본 통신 장치"(헤드셋)
- 켜면 자동 연결

## 설치
`DMRBridgeClient-<버전>-setup.exe` 실행 → 관리자 권한 없이 현재 사용자에게 설치됩니다. Java는 함께 들어 있습니다.
서명이 없는 개인 프로그램이라 "Windows의 PC 보호"가 나오면 **추가 정보 → 실행**을 누르세요.

## 쓰려면
- DMRBridge 서버 주소와 내 포트 번호 (서버 운영자에게 받음)
- 송신하려면 아마추어 무선국 허가(콜사인)와 DMR ID
- Windows 10/11 64비트

음성 변환(보코더)은 서버가 하므로 이 프로그램에는 보코더가 들어 있지 않습니다.

## 빌드
- JDK 17 (jlink·jpackage 포함), Inno Setup 6
- `build_desktop.bat` — 실행 폴더(`composeApp/build/compose/binaries/main/app`)
- `build_pc_setup.bat` — 설치 파일 (`MANUAL=설명서.pdf` 를 지정하면 함께 넣음)
- 폴더 경로에 영문이 아닌 글자가 있으면 `mklink /J` 로 영문 경로를 만들어 그곳에서 실행하세요.

## 구조
| 폴더 | 내용 |
|---|---|
| `composeApp/src/jvmShared` | 안드로이드판과 같은 공통 코드: 서버 통신(USRP), 소리 변환, 상태, 화면(Compose) |
| `composeApp/src/desktopMain` | PC 전용: Java Sound, Windows 통신 장치(JNA), 창·스페이스바 |
| `packaging` | 설치 파일 스크립트(Inno Setup), 라이선스 고지 |
| `docs/PROTOCOL.md` | 클라이언트 ⇄ DMRBridge 통신 규격 |

## 사용한 오픈소스
설치 파일에 함께 들어가는 부품과 라이선스는 [packaging/THIRD_PARTY_NOTICES.txt](packaging/THIRD_PARTY_NOTICES.txt)에 있습니다.
(Eclipse Temurin 17 — GPLv2+Classpath Exception, Kotlin·Compose·Skiko·JNA — Apache 2.0, Skia — BSD 3-Clause, ICU — Unicode License V3)
아이콘은 제작자가 직접 그렸습니다.

DMRBridge 서버도 같은 제작자가 만들었습니다. BrandMeister는 해당 단체의 이름이며, 이 프로그램은 그 단체와 관계가 없습니다.
