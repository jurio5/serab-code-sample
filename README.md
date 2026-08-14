# SeRab Code Sample

SeRab 운영 서비스에서 전투 기록, 미디어 업로드, 인증, 실시간 상태 관리의 핵심 구현만 발췌한 코드 샘플입니다.
운영 중인 서비스의 전체 소스는 private로 관리하고 있으며, 주요 구현 흐름을 확인할 수 있도록 일부 코드를 패키지 구조 그대로 정리했습니다.

- Docs: https://github.com/jurio5/serab-docs
- Service: https://www.serab.dev

## 코드 구성

| 영역 | 주요 코드 | 확인할 흐름 |
|---|---|---|
| 전투 기록 / 통계 | `content/stats` | 원본 전투 기록을 집계용 데이터로 분리하고, 조회 단계에서 스킬 순서를 정규화하는 방식 |
| 미디어 업로드 | `content/upload` | Presigned URL 발급, 업로드 완료 검증, 도메인 연결, 만료 파일 정리 |
| 실시간 상태 | `global/websocket`, `content/room/scheduler` | 일회용 티켓 인증과 연결 상태·방 참여 상태 보정 |
| 인증 | `global/security/jwt` | Refresh Token 관리와 Access Token blacklist 처리 |
| 테스트 | `src/test` | 통계 집계, 업로드 상태 전이, WebSocket 티켓, 토큰 정책 검증 |

## 읽기 순서

1. `content/stats`에서 전투 기록이 통계 조회 데이터로 바뀌는 흐름을 확인합니다.
2. `content/upload`에서 브라우저 직접 업로드를 위한 세션 수명주기와 정리 로직을 확인합니다.
3. `global/websocket`과 `content/room/scheduler`에서 실시간 연결 상태를 다루는 흐름을 확인합니다.
4. `global/security/jwt`과 `src/test`에서 인증 정책과 검증 방식을 확인합니다.

## 공개 범위

- 민감 설정 파일, 환경 변수, 배포 스크립트는 포함하지 않았습니다.
- 전체 애플리케이션이 아니라 실제 서비스 코드 일부를 패키지 구조 그대로 발췌했습니다.
- 객체 저장소 접근 정보와 운영 환경별 어댑터 구현은 공개 범위에서 제외했습니다.
- 독립 실행용 프로젝트가 아니라, 실제 서비스의 코드 흐름을 확인하기 위한 발췌본입니다.
