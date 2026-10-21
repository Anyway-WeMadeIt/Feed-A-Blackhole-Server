# Nginx 설정 (HTTPS 리버스 프록시)

운영 서버(EC2)에서 앱 앞에 서서 HTTPS를 처리하는 Nginx의 설정과 적용 절차다. 서버를 다시 만들거나 설정을 확인할 때 이 문서를 따른다.

```
클라이언트 ─HTTPS(443)─▶ Nginx(인증서로 암호화를 풀어 줌) ─HTTP─▶ 앱 컨테이너(127.0.0.1:8080)
```

이 저장소는 공개라서 도메인과 IP는 `<도메인>`, `<탄력적 IP>` 자리표시자로 적는다. 실제 값은 [CONTRIBUTING.md](../../CONTRIBUTING.md)의 "인프라 식별 정보" 규칙에 따라 저장소 밖에서 관리한다.

## 파일

| 파일 | 둘 위치(서버) | 역할 |
| --- | --- | --- |
| [blackhole.conf](blackhole.conf) | `/etc/nginx/conf.d/blackhole.conf` | 도메인의 요청을 앱으로 프록시한다. **인증서 적용 전 모양**이다 |
| [00-hardening.conf](00-hardening.conf) | `/etc/nginx/conf.d/00-hardening.conf` | 응답 헤더에서 Nginx 버전을 숨긴다(`server_tokens off`) |
| [01-default-reject.conf](01-default-reject.conf) | `/etc/nginx/conf.d/01-default-reject.conf` | 우리 도메인이 아닌 이름·IP의 HTTPS 요청을 앱까지 넘기지 않고 거부한다 |
| [02-rate-limit.conf](02-rate-limit.conf) | `/etc/nginx/conf.d/02-rate-limit.conf` | 인증 경로(`/api/v1/auth/`)의 요청 빈도를 IP별로 제한한다. 한도를 넘으면 `429`를 돌려준다 |

`blackhole.conf`는 서버에서 certbot이 직접 고친다(443 서버 블록, 인증서 경로, 리다이렉트 추가). 그래서 저장소에는 **certbot이 고치기 전 모양만** 두었다. 서버의 최종 파일을 그대로 복사해 두지 않는 이유는 인증서 경로가 들어가 도메인에 묶이기 때문이다.

## 전제

- DNS의 A 레코드가 서버의 탄력적 IP를 가리킨다(`nslookup <도메인>`으로 확인).
- 보안그룹이 80, 443을 연다(SSH는 열지 않는다. 접속은 SSM Session Manager).
- 앱 컨테이너가 `127.0.0.1:8080`에서 실행 중이다([`deploy/deploy.sh`](../deploy.sh)).

## 적용 절차 (EC2)

**순서를 지킨다.** 서버에서 검증된 순서이고, 2번(사이트 설정)과 3번(인증서) 뒤에 4번(하드닝)을 적용한다.

### 1. 설치와 저장소 받기

```bash
sudo dnf install -y nginx certbot python3-certbot-nginx git
nginx -v && certbot --version && sudo certbot plugins | grep -i nginx
git clone --depth 1 https://github.com/Anyway-WeMadeIt/Feed-A-Blackhole-Server.git ~/repo
```

- `certbot plugins`에 `nginx`가 보이면 된다. Amazon Linux 2023 저장소에 certbot이 들어 있어 `dnf`로 설치한다.
- 이 폴더가 아직 기본 브랜치에 병합되기 전이면 `git clone`에 `--branch <브랜치 이름>`을 붙인다.

### 2. 사이트 설정(HTTP)

```bash
DOMAIN=<도메인>
sed "s/<도메인>/$DOMAIN/g" ~/repo/deploy/nginx/blackhole.conf | sudo tee /etc/nginx/conf.d/blackhole.conf > /dev/null
sudo nginx -t && sudo systemctl enable --now nginx
curl -s http://$DOMAIN/actuator/health
```

- `nginx -t`는 문법 검사다. `&&` 덕분에 검사가 실패하면 시작하지 않는다.
- 마지막 명령이 `{"…","status":"UP"}`이면 DNS, Nginx, 앱 연결이 모두 정상이다.

### 3. 인증서 발급과 자동 갱신

```bash
sudo certbot --nginx -d $DOMAIN --register-unsafely-without-email --agree-tos --dry-run
sudo certbot --nginx -d $DOMAIN --register-unsafely-without-email --agree-tos --redirect
sudo systemctl enable --now certbot-renew.timer
sudo certbot renew --dry-run
sudo certbot certificates
```

| 명령 | 의미 |
| --- | --- |
| 첫 줄(`--dry-run`) | Let's Encrypt **스테이징**에서 연습한다. 인증서를 저장하지 않고 발급 횟수 한도도 쓰지 않으면서 DNS, 80 포트, 설정을 검증한다. 성공해야 다음으로 간다 |
| 둘째 줄 | 실제 발급. certbot이 443 서버 블록, 인증서 경로, HTTP → HTTPS 리다이렉트(`--redirect`)를 `blackhole.conf`에 추가한다 |
| `certbot-renew.timer` | 하루에 두 번쯤 깨어나 만료 30일 이내인 인증서만 갱신한다 |
| `renew --dry-run` | 갱신 경로 전체를 시뮬레이션한다 |
| `certificates` | 만료일을 보여 준다 |

- **이메일 없이 발급한다.** Let's Encrypt가 2025-06-04에 만료 알림 메일 발송을 종료했고 이메일은 선택 사항이다. 대신 **갱신이 실패해도 알림이 오지 않으므로** `sudo certbot certificates`로 만료일을 직접 확인한다.
- 이름이 `duckdns.org` 같은 공용 무료 도메인이면 Let's Encrypt의 "등록 도메인당 발급 횟수" 한도를 다른 사용자와 나눌 수 있어 발급이 거부될 수 있다. 그래서 반드시 `--dry-run`을 먼저 한다.

### 4. 하드닝 적용

```bash
sudo cp ~/repo/deploy/nginx/00-hardening.conf ~/repo/deploy/nginx/01-default-reject.conf ~/repo/deploy/nginx/02-rate-limit.conf /etc/nginx/conf.d/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot renew --dry-run
```

- `reload`는 기존 연결을 끊지 않고 설정을 다시 읽는다(무중단). 문법이 틀리면 `nginx -t`에서 멈추고 `reload`는 실행되지 않아 운영에 영향이 없다.
- 마지막 줄은 하드닝이 갱신 경로에 영향이 없는지 확인한다.
- 이미 하드닝을 적용한 서버에 요청 빈도 제한만 더할 때는 `sudo cp ~/repo/deploy/nginx/02-rate-limit.conf /etc/nginx/conf.d/`만 하고 같은 방식으로 검사·반영한다.

### 5. 정리

```bash
rm -rf ~/repo
```

서버에는 저장소 복사본을 남기지 않는다(설정은 `/etc/nginx/`로 복사됐다).

## 검증 (내 PC)

| 명령(PowerShell) | 기대 결과 |
| --- | --- |
| `curl.exe -I http://<도메인>/actuator/health` | `301`, `Location: https://<도메인>/…` |
| `curl.exe -s https://<도메인>/actuator/health` | `"status":"UP"` |
| `curl.exe -s -o NUL -w "%{http_code}" https://<도메인>/api/v1/me` | `401`(토큰 없음) |
| `curl.exe -sI https://<도메인>/actuator/health` | `Server: nginx`(버전 숫자 없음). `Strict-Transport-Security`는 앱이 보낸다 |
| `curl.exe -sk -o NUL -w "%{http_code}" -H "Host: example.invalid" https://<도메인>/` | `000`(응답 없이 연결을 닫음. `01-default-reject.conf`의 `return 444`) |
| `curl.exe -k https://<탄력적 IP>/` | TLS 핸드셰이크 실패(종료 코드 35). SNI 없는 IP 접속은 거부된다 |

요청 빈도 제한은 두 가지로 확인한다(2026-10-19 운영 서버에서 확인).

**① 인증 경로 전체(분당 60회, 순간 30회): 부작용 없음.** 잘못된 리프레시 토큰으로 `refresh`를 호출하면 `401`만 돌려주고 DB에는 아무것도 쓰이지 않는다.

```bash
for i in $(seq 1 45); do curl -s -o /dev/null -w '%{http_code} ' -X POST -H 'Content-Type: application/json' -d '{"refreshToken":"x"}' https://<도메인>/api/v1/auth/refresh; done; echo
```

(Git Bash 등 bash에서 실행한 명령이다. PowerShell은 JSON의 따옴표 처리가 달라서 그대로 쓰면 깨질 수 있다.)

앞의 약 31~38개는 `401`이고 그 뒤로 `429`가 섞여 나온다. 요청 간격 동안 한도가 조금씩 풀려서 정확한 개수는 달라진다.

**② `guest/register`(분당 10회, 순간 20회): 시험 계정이 생긴다.** 앱은 **빈 본문 `{}`도 받아서 게스트 계정을 만든다**(201). 거부되지 않으므로 이 시험은 **테스트 단계에서만, 만들어진 계정을 나중에 지워도 될 때** 한다.

```bash
for i in $(seq 1 40); do curl -s -o /dev/null -w '%{http_code} ' -X POST -H 'Content-Type: application/json' -d '{}' https://<도메인>/api/v1/auth/guest/register; done; echo
```

앞의 21개(평상시 1 + burst 20)가 `201`이고 그 뒤로 `429`가 나온다. 응답 본문의 `guestSecret`은 화면에 남기지 않도록 위처럼 `-o /dev/null`로 버린다. 만들어진 계정은 런북 6장의 초기화 절차로 지운다.

- 다른 경로가 막히지 않는지도 본다: 제한이 걸린 직후에도 `curl.exe -s -o NUL -w "%{http_code}" https://<도메인>/actuator/health`는 `200`, `/api/v1/me`(토큰 없음)는 `401`이어야 한다.
- 시험으로 걸린 제한은 약 6초(분당 10회) 뒤부터 풀리기 시작한다(실측: 70초 뒤 정상). 거부 기록은 서버의 `sudo grep limiting /var/log/nginx/error.log | tail`로 본다.
- 한도에 걸린 요청은 **더 엄격한 규칙(register)에서 먼저 거부되어 인증 경로 전체 한도를 소모하지 않는다.**

끝으로 클라이언트로 로그인과 게임 진행이 되는지 확인한다.

## 운영 메모

- **80 포트와 HTTP 서버 블록을 닫지 않는다.** 인증서 갱신이 HTTP-01 방식(80 포트로 소유 확인)이다.
- `blackhole.conf`의 `# managed by Certbot` 줄은 직접 고치지 않는다. 설정을 바꾼 뒤에는 항상 `sudo nginx -t && sudo systemctl reload nginx`로 검사하고 반영한다.
- **HSTS 헤더를 Nginx에 추가하지 않는다.** 앱(Spring Security)이 이미 보내서 중복된다. `X-Content-Type-Options`, `X-Frame-Options`도 앱이 보낸다.
- 만료일 확인: `sudo certbot certificates`. 갱신 상태: `systemctl list-timers | grep certbot`, `sudo journalctl -u certbot-renew`.
- 로그: `/var/log/nginx/access.log`, `error.log`.

## 되돌리기

하드닝만 되돌리려면 추가한 파일을 지우고 다시 반영한다. 요청 빈도 제한 때문에 정상 사용자가 막힌다면 `02-rate-limit.conf`만 지우면 즉시 풀린다.

```bash
sudo rm /etc/nginx/conf.d/00-hardening.conf /etc/nginx/conf.d/01-default-reject.conf /etc/nginx/conf.d/02-rate-limit.conf
sudo nginx -t && sudo systemctl reload nginx
```

## 도메인을 바꿀 때

임시 도메인에서 정식 도메인으로 옮길 때의 개요다. 전환 기간에는 두 도메인을 함께 서비스한다.

1. 새 도메인의 A 레코드를 같은 탄력적 IP로 연결한다.
2. `blackhole.conf`의 `server_name`에 새 도메인을 추가하고(`nginx -t` 후 reload), `sudo certbot --nginx --expand -d <기존 도메인> -d <새 도메인>`으로 인증서를 확장한다(`--expand`가 없으면 확장 여부를 대화형으로 묻는다).
3. 클라이언트의 서버 주소를 새 도메인으로 바꿔 배포한다. 구 클라이언트가 사라지면 기존 도메인을 `server_name`에서 빼고 인증서를 다시 발급한다.

JWT 발급자(`app.jwt.issuer`)는 URL이 아니라 고정 문자열이라 도메인을 바꿔도 이미 발급된 토큰은 무효화되지 않는다.

## 이 설정이 다루지 않는 것

- 여러 IP를 쓰는 분산 공격: 요청 빈도 제한은 한 IP의 반복 호출만 막는다. IPv6 사용자가 주소를 바꿔 가며 우회하는 경우도 막지 못한다.
- 로그 전송, 알람: 운영 점검 단계에서 다룬다.

## 요청 빈도 제한 메모

- 대상은 인증 경로뿐이다. `guest/register`(토큰 없이 DB에 계정을 만드는 경로)는 **IP당 분당 10회, 순간 20회**, 인증 경로 전체(`login`, `refresh`, `logout` 포함)는 **분당 60회, 순간 30회**다. 한도는 통신사나 공유 네트워크의 IP를 여러 사람이 나눠 쓰는 경우를 감안해 넉넉하다.
- 한도를 넘으면 `429`를 돌려준다. 클라이언트는 `429`를 받으면 잠시 후 재시도해야 한다.
- 값은 `02-rate-limit.conf`에서 바꾼다. 앞단에 로드밸런서나 CDN을 두게 되면 `$remote_addr`가 그 장비의 IP가 되므로, 클라이언트 IP를 복원하는 `real_ip` 설정을 먼저 더해야 한다(안 하면 모두가 한 IP로 보여 정상 사용자가 막힌다).

전체 배포 계획과 진행 상황은 [docs/deploy-plan.md](../../docs/deploy-plan.md)를 본다.
