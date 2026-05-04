# SeRab Code Sample

SeRab 운영 서비스에서 핵심 구현 일부만 발췌한 공개 코드 샘플입니다.
원본 서비스 레포지터리는 운영 설정과 전체 소스 보호를 위해 private로 유지합니다.

- Docs: https://github.com/jurio5/serab-docs
- Service: https://www.serab.dev

## 구성

- WebSocket 티켓 인증과 실시간 접속 상태 관리
- JWT Refresh Token과 Access Token blacklist 관리
- 전투 기록 기반 통계 집계
- 주요 흐름 테스트 코드 일부

## 공개 범위

- 민감 설정 파일, 환경 변수, 배포 스크립트는 포함하지 않았습니다.
- 전체 애플리케이션이 아니라 실제 서비스 코드 일부를 패키지 구조 그대로 발췌했습니다.