# 클라이언트 ⇄ DMRBridge 통신 (USRP)

UDP, 서버 USRP 포트(기본 32001). 클라이언트는 아무 포트에서 보내고 서버는 보낸 곳으로 답합니다.

헤더 32바이트(빅엔디안): `"USRP"` seq(4) memory(4) keyup(4) talkgroup(4) type(4) mpxid(4) reserved(4)

| type | 방향 | 내용 |
|---|---|---|
| 0 음성 | 양쪽 | keyup=1 + 160샘플 8kHz 16bit(리틀엔디안) / keyup=0 = 송신 끝 |
| 1 DTMF | 클라→서버 | `"450"` 그룹, `"9990#"` 개인 (끝에 NUL) |
| 2 TEXT | 클라→서버 | `"REG:DVSWITCH"` 등록 → 서버 `"REG:OK"` + INFO |
| 2 TEXT | 서버→클라 | `INFO:{"tlv":{"ambe_mode":"DMR","ts":2},"last_tune":"450"}`, `INFO:MSG:…` 알림, 첫 바이트 8 = 수신 통화 정보(src3 rptr4 dst3 slot1 cc/priv1 콜사인\0) |
| 3 PING | 양쪽 | 클라가 5초마다, 서버가 되돌려 줌 (15초 없으면 '응답 없음') |
| 4 TLV | 클라→서버 | 5 = 원격 명령 `"txTs=1"` 슬롯 |
| (공통) | 서버→클라 | 클라이언트는 type 빅엔디안만 처리, 반대 순서 패킷 무시 |
| 2 TEXT | 클라→서버 | `CLIENT:{"call","dmr_id","name","device","app"}` 내 정보 (등록 때마다) |
| 2 TEXT | 서버→클라 | `INFO:CALL:{"src","call","name","loc","dst","priv","slot","alias"}` 상대방 정보 (수신 시작, 이름표 받으면 다시) |

- type 칸을 빅엔디안으로 보내야 서버가 INFO·MSG를 보냅니다(DVSwitch Mobile은 리틀엔디안이라 서버가 보내지 않음).
- 서버 보호: 음성 패킷 1초 끊기면 PTT 해제, TOT(기본 180초). 클라이언트도 180초에 스스로 멈춤.

## 장비명과 이름표 (서버가 만듦)
- BM 기기 위치(RPTC location, 20자): `DMRBridge ` + 장비명 예: `DMRBridge Galaxy A17` (20자 넘으면 "Galaxy " 생략 후 자름). 바뀌면 통화 없을 때 BM 한 번 재접속
  - 재접속 제한: 장비명 때문에 BM 재접속은 사용자마다 10분에 1번까지(처음 1번은 바로). 그 사이 바뀐 이름은 이름표에는 바로, BM 위치는 10분 뒤 최신값으로
  - BM은 RPTC 설명 칸을 쓰지 않고 위치 칸을 기기 정보로 쓰므로 위치 칸에 넣음
- 이름표(Talker Alias, ETSI TS 102 361-2, 31자): `콜사인 DMRBridge 장비명` 예: `N0CALL DMRBridge Galaxy A17` — 송신 음성에 실려 상대 무전기로 감
- 송신 콜사인·DMR ID는 항상 서버 설정 값 (클라이언트 값이 달라도 덮어쓰지 않음)

## 브릿지 공지 (서버 → 클라이언트)
- 순서는 일반 수신과 같음: TLV SET_INFO(8) → `INFO:CALL:{json}` → 음성 keyup=1 PCM(20ms) … → keyup=0
- SET_INFO: src=dst=그 사용자 서버 DMR ID, rptr=0, 개인(cc|0x80), 이름 = 모든 클라이언트에 UTF-8 "브릿지 공지" 하나
- 서버가 클라이언트 바이트 순서를 모를 때(음성 type 0만 받음)는 같은 SET_INFO를 빅엔디안 먼저, 리틀엔디안 마지막으로 두 번 보냄 (DVSwitch Mobile은 리틀엔디안 뒤에 빅엔디안이 오면 이름을 표시하지 않음).
  클라이언트는 자기 순서(빅엔디안)만 읽고 반대 순서 type(예: 0x02000000)은 무시. INFO:CALL은 순서를 아는 빅엔디안 클라이언트에만. 새 클라이언트 주소면 순서 판정 초기화
- INFO:CALL: `{"notice":true,"src","dst","priv":true,"slot","call":"브릿지 공지","name":"<공지 제목>","source":"tts"|"live"|"schedule"}`, 이어서 보내면 `"resume":true`
- BM 수신·클라이언트 PTT가 끼어들면 keyup=0으로 멈추고 끝난 뒤 처음부터(resume). 클라이언트 PTT 중에는 공지 음성 없음. BM으로는 나가지 않음
- 클라이언트 표시: 상대방 정보 "📢 브릿지 공지" + 종류(음성 합성/실시간 마이크/예약 공지) + 내용(name), 최근 수신에 공지로 기록
- 클라이언트는 새 통화 시작(SET_INFO)마다 이전 이름·이름표·공지 표시를 지움

## 내부 채널 (Intercom, 서버 → 클라이언트)
- 서버 설정 intercom_tg(기본 99, 0=끔). 클라이언트가 TG를 그 번호로 바꾸면(DTMF "99" 등) 그 사용자의 PTT 음성은 BM으로 안 가고 같은 브릿지에서 그 TG에 있는 다른 사용자에게만 감
- 듣는 쪽 순서는 일반 수신과 같음: SET_INFO(src=말한 사람 DMR ID, dst=99, 그룹, 이름=콜사인 UTF-8) → `INFO:CALL {"intercom":true,"src","dst":99,"priv":false,"slot","call":콜사인,"name":사용자 이름(예 "N0CALL·01")}`(이어서면 "resume":true) → 음성 → 끝
- BM 수신·PTT·공지 중이면 내부 채널 소리는 버림(BM 우선). TG 변경 시 서버 알림 "Intercom 99 (bridge only)". 이력 kind "intercom"
- 번호 알림: INFO JSON `"intercom_tg":N` (꺼져 있으면 키 없음). 웹에서 번호를 바꾸면 접속 중인 빅엔디안 클라이언트에 INFO를 다시 보냄. 클라이언트는 키가 없으면 내부 채널 버튼 숨김
- 클라이언트 표시: 상대방 정보에 "🔁 내부 채널 · BM으로 안 나감", 통화 대상이 그 번호면 "🔁 내부 채널 N", 채널 탭 빠른 버튼
