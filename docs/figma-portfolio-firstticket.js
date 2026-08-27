/**
 * First Ticket 포트폴리오 — Figma 1920×1080 슬라이드 자동 생성 스크립트
 *
 * [실행 방법]
 *   1. Figma에서 대상 파일 열기
 *   2. 우클릭 → Plugins → "Scripter" 실행
 *      (없으면 Figma Community에서 "Scripter" 무료 설치)
 *   3. 이 파일 내용 전체를 Scripter 편집창에 붙여넣고 실행(▶ 또는 Cmd/Ctrl+Enter)
 *
 * [결과] 기존 콘텐츠 오른쪽 빈 공간에 1920×1080 프레임 7장이 생성된다.
 *        원본 PDF 11장을 16:9에 맞게 7장으로 압축했다(세로가 짧아 페이지당 밀도를 낮춰야 한다).
 *
 * [BTLLM 스크립트와 공존] 슬라이드 이름을 "FT 01 · " 형식으로 붙여, 같은 페이지에 있는
 *   BTLLM 슬라이드("01 · " 형식)를 서로 지우지 않는다.
 *
 * [디자인 근거] 원본 박동진_portfolio_firstticket_001.pdf를 Ghostscript로 렌더링해
 *   픽셀에서 직접 추출한 색상값을 쓴다 — 눈대중 아님.
 *   티얼 #0B8A7A / 네이비 #0A1D2C / 민트 #E8F5F2 / 빨강 #DC2626 / 카드 #F9FAFB
 */

// ── 팔레트 ────────────────────────────────────────────────────
const hex = (h) => ({
  r: parseInt(h.slice(0, 2), 16) / 255,
  g: parseInt(h.slice(2, 4), 16) / 255,
  b: parseInt(h.slice(4, 6), 16) / 255,
});
const C = {
  accent:   hex('0B8A7A'), // 주 강조 — 섹션 라벨 · 개선 후 수치
  accentDk: hex('077668'), // pill 텍스트처럼 작은 글씨에서 대비가 필요할 때
  navy:     hex('0A1D2C'), // 제목
  ink:      hex('1A2332'), // 본문 강조
  gray:     hex('6B7785'), // 본문
  soft:     hex('E8F5F2'), // 민트 강조 배경 (pill · 노트)
  card:     hex('F9FAFB'),
  border:   hex('E5E7EB'),
  dark:     hex('1F2937'), // 코드 블록 · 다크 노드
  white:    hex('FFFFFF'),
  amber:    hex('FFFBEB'), // 앰버 배경 (트레이드오프 · 주의)
  amberInk: hex('B45309'), // 앰버 텍스트
  red:      hex('DC2626'), // 개선 전 수치 — 원본이 before/after를 색으로 대비시킨다
};
const fill = (c) => [{ type: 'SOLID', color: c }];

/**
 * 글자 크기 스케일.
 * BTLLM 스크립트(1.15)보다 낮게 잡았다 — 이 포트폴리오는 트러블슈팅 4건에 지표·코드가
 * 함께 들어가 페이지당 밀도가 더 높기 때문이다.
 * 실행 후 "넘침 발생"이라고 나오면 1.0~1.05로 더 낮추고, 여백이 허전하면 1.15로 올린다.
 */
const FS = 1.1;

// ── 폰트 자동 감지 ────────────────────────────────────────────
const all = await figma.listAvailableFontsAsync();
const byFamily = {};
for (const f of all) {
  if (!byFamily[f.fontName.family]) byFamily[f.fontName.family] = [];
  byFamily[f.fontName.family].push(f.fontName.style);
}
const FAM_PREF = ['Pretendard', 'Pretendard Variable', 'Noto Sans KR', 'Spoqa Han Sans Neo',
                  'Apple SD Gothic Neo', 'Malgun Gothic', 'Inter'];
const FAMILY = FAM_PREF.find((f) => byFamily[f]) || 'Inter';
const styles = byFamily[FAMILY] || ['Regular'];
const pick = (cands) => cands.find((s) => styles.includes(s)) || styles[0];
const S_BOLD = pick(['Bold', '700', 'Semi Bold', 'SemiBold', 'Medium']);
const S_SEMI = pick(['Semi Bold', 'SemiBold', '600', 'Medium', 'Bold']);
const S_REG  = pick(['Regular', '400', 'Normal', 'Medium']);

for (const st of new Set([S_BOLD, S_SEMI, S_REG])) {
  await figma.loadFontAsync({ family: FAMILY, style: st });
}

// ── 헬퍼 ──────────────────────────────────────────────────────
const created = [];

/** 오토레이아웃 프레임 (표준 Plugin API만 사용 — figma.createAutoLayout()은 MCP 전용 확장) */
function AL(direction, props) {
  const f = figma.createFrame();
  f.layoutMode = direction;
  f.primaryAxisSizingMode = 'AUTO';
  f.counterAxisSizingMode = 'AUTO';
  f.itemSpacing = 0;
  f.fills = [];
  if (props) for (const k in props) f[k] = props[k];
  return f;
}

function T(chars, { size = 16, style = S_REG, color = C.ink, w = null, lh = 1.5, ls = 0 } = {}) {
  const t = figma.createText();
  t.fontName = { family: FAMILY, style };
  t.characters = chars;
  t.fontSize = Math.round(size * FS);
  t.fills = fill(color);
  t.lineHeight = { unit: 'PERCENT', value: lh * 100 };
  if (ls) t.letterSpacing = { unit: 'PIXELS', value: ls };
  if (w) { t.textAutoResize = 'HEIGHT'; t.resize(w, t.height); }
  else   { t.textAutoResize = 'WIDTH_AND_HEIGHT'; }
  return t;
}

function VStack(gap = 16, props = {}) {
  const f = AL('VERTICAL', Object.assign({ itemSpacing: gap }, props));
  f.fills = [];
  return f;
}
function HStack(gap = 16, props = {}) {
  const f = AL('HORIZONTAL', Object.assign({ itemSpacing: gap }, props));
  f.fills = [];
  return f;
}

/** 세로 여백 — 자식 없는 오토레이아웃은 높이가 붕괴하므로 일반 프레임을 쓴다 */
function Spacer(h, w = 10) {
  const f = figma.createFrame();
  f.fills = [];
  f.resize(w, h);
  return f;
}

function SectionLabel(txt) {
  return T(txt, { size: 15, style: S_BOLD, color: C.accent, ls: 1.2 });
}

/** 태그 pill — 민트 배경 + 진한 티얼 텍스트 */
function Pill(txt) {
  const p = AL('HORIZONTAL', {
    itemSpacing: 0, paddingLeft: 18, paddingRight: 18, paddingTop: 9, paddingBottom: 9,
  });
  p.cornerRadius = 999;
  p.fills = fill(C.soft);
  p.appendChild(T(txt, { size: 15, style: S_SEMI, color: C.accentDk }));
  return p;
}

function Card(width, { bg = C.card, pad = 28, gap = 12, radius = 12, stroke = true } = {}) {
  const c = AL('VERTICAL', {
    itemSpacing: gap, paddingLeft: pad, paddingRight: pad, paddingTop: pad, paddingBottom: pad,
  });
  c.fills = fill(bg);
  c.cornerRadius = radius;
  if (stroke) { c.strokes = fill(C.border); c.strokeWeight = 1; }
  c.resize(width, c.height);
  c.layoutSizingHorizontal = 'FIXED';
  c.layoutSizingVertical = 'HUG';
  return c;
}

/**
 * 개선 전/후 지표 박스.
 * 원본 PDF가 before를 빨강, after를 티얼로 대비시키므로 그 용법을 그대로 가져왔다.
 * [주의] 카드는 FIXED 폭이라 값이 길면 잘린다 — 좁은 칸에서는 size를 낮춘다.
 */
function BeforeAfter(width, before, after, label, { sub = null, size = 30 } = {}) {
  const m = Card(width, { bg: C.white, pad: 24, gap: 6 });
  const iw = width - 48;
  const row = HStack(9);
  row.counterAxisAlignItems = 'CENTER';
  row.appendChild(T(before, { size, style: S_BOLD, color: C.red }));
  row.appendChild(T('→', { size: size * 0.75, style: S_BOLD, color: C.gray }));
  row.appendChild(T(after, { size, style: S_BOLD, color: C.accent }));
  m.appendChild(row);
  m.appendChild(T(label, { size: 15, style: S_REG, color: C.gray, w: iw }));
  if (sub) m.appendChild(T(sub, { size: 13, style: S_SEMI, color: C.accent, w: iw }));
  return m;
}

/** 단일 수치 지표 */
function Metric(width, value, label, { color = C.accent, sub = null, valueSize = 36 } = {}) {
  const m = Card(width, { bg: C.white, pad: 24, gap: 6 });
  const iw = width - 48;
  m.appendChild(T(value, { size: valueSize, style: S_BOLD, color, w: iw, lh: 1.25 }));
  m.appendChild(T(label, { size: 15, style: S_REG, color: C.gray, w: iw }));
  if (sub) m.appendChild(T(sub, { size: 13, style: S_REG, color: C.gray, w: iw }));
  return m;
}

/** 불릿 */
function Bullet(width, txt, { color = C.ink, size = 16 } = {}) {
  const row = HStack(10);
  row.resize(width, row.height);
  row.layoutSizingHorizontal = 'FIXED';
  row.appendChild(T('·', { size, style: S_BOLD, color: C.accent }));
  row.appendChild(T(txt, { size, style: S_REG, color, w: width - 20 }));
  return row;
}

/** 코드 블록 — 여러 줄을 다크 배경에 */
function Code(width, lines, { mono = C.white, pad = 18 } = {}) {
  const c = Card(width, { bg: C.dark, pad, gap: 5, stroke: false, radius: 8 });
  const iw = width - pad * 2;
  lines.forEach(([txt, color]) => {
    c.appendChild(T(txt, { size: 14, style: S_REG, color: color || mono, w: iw }));
  });
  return c;
}

/**
 * 가로로 늘어선 카드들의 높이를 가장 큰 것에 맞춘다.
 * auto-layout 자식은 기본이 HUG라 내용량에 따라 제각각 끝나서 아랫변이 어긋나 보인다.
 * resize()가 sizing mode를 FIXED로 바꾸므로 그 뒤에 명시적으로 한 번 더 고정한다.
 */
function equalize(hstack) {
  const kids = hstack.children.filter((k) => k.type === 'FRAME');
  if (kids.length < 2) return hstack;
  const max = Math.max.apply(null, kids.map((k) => k.height));
  for (const k of kids) {
    if (Math.abs(k.height - max) < 0.5) continue;
    k.resize(k.width, max);
    if (k.layoutMode && k.layoutMode !== 'NONE') k.layoutSizingVertical = 'FIXED';
  }
  return hstack;
}

/** 아키텍처 다이어그램용 노드 박스 */
function NodeBox(w, title, sub, { bg = C.white, tc = C.navy, sc = C.gray } = {}) {
  const b = AL('VERTICAL', {
    itemSpacing: 4, paddingLeft: 16, paddingRight: 16, paddingTop: 14, paddingBottom: 14,
  });
  b.fills = fill(bg);
  b.cornerRadius = 10;
  b.strokes = fill(C.border);
  b.strokeWeight = 1;
  b.primaryAxisAlignItems = 'CENTER';
  b.counterAxisAlignItems = 'CENTER';
  b.appendChild(T(title, { size: 16, style: S_BOLD, color: tc }));
  if (sub) b.appendChild(T(sub, { size: 13, style: S_REG, color: sc }));
  b.resize(w, b.height);
  b.layoutSizingHorizontal = 'FIXED';
  b.layoutSizingVertical = 'HUG';
  return b;
}

// ── 슬라이드 프레임 생성 ──────────────────────────────────────
const W = 1920, H = 1080, PAD_X = 120, PAD_TOP = 84;
const PREFIX = 'FT'; // BTLLM 슬라이드("01 · ")와 이름이 겹치지 않게 한다

/**
 * 재실행 시 이 스크립트가 만든 슬라이드만 지우고 새로 만든다.
 * 이름이 "FT 01 · " 형식이면서 정확히 1920×1080인 프레임만 대상이라,
 * BTLLM 슬라이드나 직접 만든 프레임은 건드리지 않는다.
 */
const REPLACE_PREVIOUS = true;

let removedCount = 0;
if (REPLACE_PREVIOUS) {
  const namePattern = /^FT \d\d · /;
  const stale = figma.currentPage.children.filter(
    (n) => n.type === 'FRAME' && namePattern.test(n.name) && n.width === W && n.height === H
  );
  for (const n of stale) { n.remove(); removedCount++; }
}

let originX = 0;
for (const n of figma.currentPage.children) {
  originX = Math.max(originX, n.x + n.width);
}
originX += 400;
const originY = 0;

function Slide(idx, name) {
  const f = figma.createFrame();
  f.name = `${PREFIX} ${String(idx).padStart(2, '0')} · ${name}`;
  f.resize(W, H);
  f.x = originX + (idx - 1) * (W + 100);
  f.y = originY;
  f.fills = fill(C.white);
  f.clipsContent = true;
  figma.currentPage.appendChild(f);
  created.push(f);
  return f;
}

function Body(slide, gap = 28) {
  const col = VStack(gap);
  col.x = PAD_X;
  col.y = PAD_TOP;
  slide.appendChild(col);
  col.resize(W - PAD_X * 2, col.height);
  col.layoutSizingHorizontal = 'FIXED';
  col.layoutSizingVertical = 'HUG';
  return col;
}

function Footer(slide, idx, total) {
  const line = figma.createRectangle();
  line.resize(W - PAD_X * 2, 1);
  line.x = PAD_X; line.y = H - 92;
  line.fills = fill(C.border);
  slide.appendChild(line);

  const t = T(`First Ticket — 박동진 · ${idx} / ${total}`, { size: 14, style: S_REG, color: C.gray });
  t.x = PAD_X; t.y = H - 72;
  slide.appendChild(t);
}

const TOTAL = 7;
const CW = W - PAD_X * 2; // 1680

// ══════════════════════════════════════════════════════════════
// S1 — 표지
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(1, 'COVER');
  const col = Body(s, 0);

  col.appendChild(T('BACKEND ENGINEER · PORTFOLIO · 2026',
    { size: 17, style: S_BOLD, color: C.accent, ls: 1.6 }));

  col.appendChild(Spacer(60));

  const title = HStack(0);
  title.appendChild(T('First Ticket', { size: 96, style: S_BOLD, color: C.accent, lh: 1.05 }));
  title.appendChild(T('.', { size: 96, style: S_BOLD, color: C.navy, lh: 1.05 }));
  col.appendChild(title);

  col.appendChild(Spacer(28));

  col.appendChild(T('MSA 기반 공연 티켓 예매 플랫폼', { size: 30, style: S_BOLD, color: C.ink }));
  col.appendChild(Spacer(10));
  col.appendChild(T('OAuth2 · 토큰 보안 · Gateway 최적화로 안정적 인증 시스템 구현',
    { size: 22, style: S_REG, color: C.gray }));

  col.appendChild(Spacer(52));

  const pills = HStack(12);
  ['MSA', 'Keycloak OAuth2', 'JWT', 'Redis', 'DDD', 'Resilience4j', 'Zipkin Tracing']
    .forEach((p) => pills.appendChild(Pill(p)));
  col.appendChild(pills);

  const line = figma.createRectangle();
  line.resize(CW, 1); line.x = PAD_X; line.y = H - 210;
  line.fills = fill(C.border);
  s.appendChild(line);

  const nm = T('박 동 진', { size: 44, style: S_BOLD, color: C.navy, ls: 2 });
  nm.x = PAD_X; nm.y = H - 178;
  s.appendChild(nm);
  const role = T('Backend Engineer · 사용자 도메인 & Gateway 서버 담당',
    { size: 17, style: S_REG, color: C.gray });
  role.x = PAD_X; role.y = H - 116;
  s.appendChild(role);

  const contact = VStack(6);
  contact.appendChild(T('Email · straycat405@gmail.com', { size: 17, style: S_REG, color: C.gray }));
  contact.appendChild(T('GitHub · github.com/straycat405', { size: 17, style: S_REG, color: C.gray }));
  contact.appendChild(T('Blog · velog.io/@straycat405/posts', { size: 17, style: S_REG, color: C.gray }));
  s.appendChild(contact);
  contact.counterAxisAlignItems = 'MAX';
  contact.x = W - PAD_X - contact.width;
  contact.y = H - 178;
}

// ══════════════════════════════════════════════════════════════
// S2 — ABOUT + PROJECT OVERVIEW
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(2, 'ABOUT · OVERVIEW');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('01 · ABOUT'));
  col.appendChild(T('보안을 설계로 증명하는 개발자', { size: 40, style: S_BOLD, color: C.navy }));
  col.appendChild(T(
    '"동작하는 코드"에서 멈추지 않고 측정과 개선의 사이클로 시스템의 완성도를 높이는 것을 좋아합니다. First Ticket에서 User Service와 API Gateway, Eureka Service Discovery를 단독 설계·구현하고, Keycloak ROPC 연동부터 Redis 블랙리스트·토큰 로테이션, Gateway Caffeine L1 캐시 최적화까지 인증 흐름 전체를 책임졌습니다.',
    { size: 18, style: S_REG, color: C.gray, w: CW * 0.78 }));

  const m = HStack(20);
  const mw = (CW - 40) / 3;
  m.appendChild(BeforeAfter(mw, '24회', '1회', 'Gateway 블랙리스트 조회 스파이크', { sub: '-95.8% · Caffeine L1 캐시 적용' }));
  m.appendChild(Metric(mw, '1,720 TPS', 'GET /users/me 부하테스트 안정 성능', { sub: 'Little\'s Law 오차 1%' }));
  m.appendChild(Metric(mw, '0건', '비밀번호 변경 후 고아 세션', { sub: 'RFC 7009 즉시 revoke' }));
  col.appendChild(equalize(m));

  col.appendChild(Spacer(6));
  col.appendChild(SectionLabel('02 · PROJECT OVERVIEW'));

  const banner = Card(CW, { bg: C.accent, pad: 26, gap: 10, stroke: false });
  banner.appendChild(T('매크로 · 동시 접속 · 중복 예매 — 티켓팅의 세 가지 문제를 해결합니다',
    { size: 24, style: S_BOLD, color: C.white }));
  banner.appendChild(T(
    '대기열로 트래픽을 흡수하고, 분산락으로 선점 좌석을 직렬화하고, Kafka Saga로 결제 실패를 보상합니다. 모든 요청의 진입점 Gateway에서 인증·인가·보안을 단일 흐름으로 처리합니다.',
    { size: 17, style: S_REG, color: C.soft, w: CW - 52 }));

  const meta = HStack(14);
  meta.counterAxisAlignItems = 'CENTER';
  [['기간', '2026.04 ~ 2026.05 (5주)'],
   ['인원', '6인 백엔드'],
   ['개인 담당', '사용자 도메인 · Gateway · Eureka'],
   ['저장소', 'github.com/first-ticket']
  ].forEach(([k, v], i) => {
    if (i) meta.appendChild(T('·', { size: 15, style: S_REG, color: C.soft }));
    const item = HStack(7);
    item.counterAxisAlignItems = 'CENTER';
    item.appendChild(T(k, { size: 14, style: S_BOLD, color: C.white }));
    item.appendChild(T(v, { size: 15, style: S_REG, color: C.soft }));
    meta.appendChild(item);
  });
  banner.appendChild(meta);
  col.appendChild(banner);

  // [레이아웃] 원본 PDF는 A4 세로 1장에 지표 3개 + 개요 + "도전 과제/기여" 2단 카드를 모두
  //   담았지만, 16:9는 세로가 짧아 그대로 옮기면 1080을 넘긴다. 도전 과제는 뒤 슬라이드에서
  //   트러블슈팅으로 다시 다루므로, 여기서는 "개인이 무엇을 맡았는가"만 3열로 남긴다.
  const contrib = HStack(20);
  contrib.counterAxisAlignItems = 'MIN';
  const ccw = (CW - 40) / 3;
  [['토큰 보안 시스템', 'Redis JTI 블랙리스트 · 토큰 로테이션 · RFC 7009 revoke'],
   ['Gateway 인증 필터', '헤더 위조 차단 · 2단 캐시 (Caffeine L1 + Redis L2)'],
   ['DDD 도메인 설계', 'User · HostRequest Aggregate · Keycloak 보상 트랜잭션']
  ].forEach(([t, d]) => {
    const c = Card(ccw, { bg: C.card, pad: 22, gap: 5 });
    c.appendChild(T(t, { size: 17, style: S_BOLD, color: C.navy, w: ccw - 44 }));
    c.appendChild(T(d, { size: 15, style: S_REG, color: C.gray, w: ccw - 44 }));
    contrib.appendChild(c);
  });
  col.appendChild(equalize(contrib));

  Footer(s, 2, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S3 — ARCHITECTURE
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(3, 'ARCHITECTURE');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('03 · ARCHITECTURE'));
  col.appendChild(T('시스템 아키텍처', { size: 40, style: S_BOLD, color: C.navy }));
  col.appendChild(T(
    '6개의 마이크로서비스를 Spring Cloud Gateway · Eureka · Config Server로 묶었습니다. Gateway가 모든 요청의 진입점으로 JWT 검증 · 헤더 주입 · 서킷브레이킹을 담당하고, User Service가 Keycloak과 협력해 인증 · 인가 흐름을 처리합니다. 인프라는 AWS ECS Fargate에 GitHub Actions CI/CD로 자동 배포됩니다.',
    { size: 18, style: S_REG, color: C.gray, w: CW * 0.78 }));

  const arch = HStack(20);
  arch.counterAxisAlignItems = 'MIN';

  const cEdge = Card(370, { bg: C.card, pad: 22, gap: 10 });
  cEdge.appendChild(T('Edge', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cEdge.appendChild(NodeBox(326, 'Client → ALB', '로드밸런서'));
  cEdge.appendChild(NodeBox(326, 'API Gateway', 'JWT 검증 · 라우팅 · CB — 구현 담당', { bg: C.soft }));
  arch.appendChild(cEdge);

  const cSvc = Card(430, { bg: C.card, pad: 22, gap: 8 });
  cSvc.appendChild(T('Microservices (6)', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cSvc.appendChild(NodeBox(386, 'User Service', '사용자 도메인 — 구현 담당', { bg: C.soft }));
  const svcRow1 = HStack(8);
  svcRow1.appendChild(NodeBox(189, 'Program', null));
  svcRow1.appendChild(NodeBox(189, 'Venue', null));
  cSvc.appendChild(svcRow1);
  const svcRow2 = HStack(8);
  svcRow2.appendChild(NodeBox(189, 'Queue', null));
  svcRow2.appendChild(NodeBox(189, 'Booking', null));
  cSvc.appendChild(svcRow2);
  cSvc.appendChild(NodeBox(386, 'Payment', null));
  arch.appendChild(cSvc);

  const cData = Card(400, { bg: C.card, pad: 22, gap: 8 });
  cData.appendChild(T('Data & Messaging', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cData.appendChild(NodeBox(356, 'Redis', '블랙리스트 · RT · 캐시 — 구현 담당', { bg: C.soft }));
  cData.appendChild(NodeBox(356, 'Kafka (KRaft)', 'Saga · 이벤트 발행'));
  cData.appendChild(NodeBox(356, 'PostgreSQL', 'RDS · Flyway'));
  cData.appendChild(NodeBox(356, 'Keycloak 26', 'JWT 발급 · IAM — 구현 담당', { bg: C.soft }));
  arch.appendChild(cData);

  const cOps = Card(CW - 370 - 430 - 400 - 60, { bg: C.card, pad: 22, gap: 8 });
  const ow = CW - 370 - 430 - 400 - 60 - 44;
  cOps.appendChild(T('Observability & CI/CD', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cOps.appendChild(NodeBox(ow, 'Prometheus · Grafana', '메트릭 · Slack 알림', { bg: C.dark, tc: C.white, sc: C.border }));
  cOps.appendChild(NodeBox(ow, 'Zipkin', '분산 추적 (TraceId)', { bg: C.dark, tc: C.white, sc: C.border }));
  cOps.appendChild(NodeBox(ow, 'GitHub Actions', 'CI/CD → ECS Fargate', { bg: C.dark, tc: C.white, sc: C.border }));
  arch.appendChild(cOps);

  col.appendChild(equalize(arch));

  const gw = Card(CW, { bg: C.soft, pad: 24, gap: 10, stroke: false });
  gw.appendChild(T('Gateway 인증 필터 설계 원칙', { size: 18, style: S_BOLD, color: C.accentDk }));
  gw.appendChild(T('인바운드 헤더 sanitization → JWT 파싱 → 블랙리스트 2단 조회 → 헤더 주입',
    { size: 19, style: S_BOLD, color: C.ink }));
  const gwRow = HStack(20);
  const gwc = (CW - 48 - 40) / 3;
  [['클라이언트발 헤더 즉시 제거', 'X-User-Id · X-User-Role · X-JTI · X-Token-Exp — 위·변조 차단'],
   ['2단 캐시로 블랙리스트 조회', 'L1 Caffeine(TTL 5s, max 10,000) → L2 Redis fallback'],
   ['하위 서비스는 재검증 없음', '주입된 헤더를 신뢰 — Gateway가 곧 신뢰 경계']
  ].forEach(([t, d]) => {
    const b = VStack(4);
    b.resize(gwc, b.height); b.layoutSizingHorizontal = 'FIXED';
    b.appendChild(T(t, { size: 16, style: S_BOLD, color: C.accentDk, w: gwc }));
    b.appendChild(T(d, { size: 15, style: S_REG, color: C.gray, w: gwc }));
    gwRow.appendChild(b);
  });
  gw.appendChild(equalize(gwRow));
  col.appendChild(gw);

  Footer(s, 3, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S4 — KEY CONTRIBUTION (토큰 보안 + DDD)
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(4, 'KEY CONTRIBUTION');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('04 · KEY CONTRIBUTION'));
  col.appendChild(T('만료 전 무효화가 불가능한 JWT를, 무효화 가능하게',
    { size: 40, style: S_BOLD, color: C.navy }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2, iw = cw - 60;

  // #001 토큰 보안
  const a = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  a.appendChild(T('#001 · 토큰 보안 시스템', { size: 21, style: S_BOLD, color: C.navy }));
  a.appendChild(T(
    'Keycloak이 발급한 JWT는 만료 전 강제 무효화가 불가능합니다. 로그아웃·비밀번호 변경 후에도 탈취된 토큰이 유효한 문제를 Redis JTI 블랙리스트로, 탈취된 Refresh Token 재사용을 Rotation으로 해결했습니다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));

  a.appendChild(Code(iw, [
    ['로그인   → Keycloak ROPC → AT(15분) + RT(7일) 발급', C.white],
    ['로그아웃 → Redis blacklist:{jti} 등록 (TTL = 잔여 AT 유효시간)', hex('86EFAC')],
    ['Gateway → L1 Caffeine 조회 → Hit: Redis 미조회 / Miss: L2 Redis', hex('86EFAC')],
    ['재발급   → 기존 RT 즉시 무효화 → 신규 AT+RT (토큰 로테이션)', C.white],
  ]));

  a.appendChild(T('설계 의사결정', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  [['짧은 AT TTL만 사용', '로그아웃 즉시 무효화 불가, UX 저하', '기각'],
   ['Server-side session', 'Stateless 이점 상실, MSA 확장 부적합', '기각'],
   ['Redis JTI 블랙리스트', '로그아웃 후 최대 5s 지연 (L1 TTL)', '채택']
  ].forEach(([opt, tradeoff, verdict]) => {
    const row = HStack(0);
    row.resize(iw, row.height); row.layoutSizingHorizontal = 'FIXED';
    row.counterAxisAlignItems = 'CENTER';
    const isPick = verdict === '채택';
    row.appendChild(T(opt, { size: 15, style: isPick ? S_BOLD : S_REG, color: isPick ? C.ink : C.gray, w: iw * 0.34 }));
    row.appendChild(T(tradeoff, { size: 14, style: S_REG, color: C.gray, w: iw * 0.52 }));
    const v = T(verdict, { size: 14, style: S_BOLD, color: isPick ? C.accent : C.gray, w: iw * 0.14 });
    v.textAlignHorizontal = 'RIGHT';
    row.appendChild(v);
    a.appendChild(row);
  });

  const tw = Card(iw, { bg: C.amber, pad: 16, gap: 0, stroke: false });
  tw.appendChild(T(
    '트레이드오프 — 로그아웃 후 최대 5초간 해당 AT가 유효 처리될 수 있다. AT TTL(15분) 대비 0.3% 이하로, fail-open 정책과 일관성을 유지하는 선에서 수용했다.',
    { size: 14, style: S_REG, color: C.gray, w: iw - 32 }));
  a.appendChild(tw);
  two.appendChild(a);

  // #002 DDD
  const b = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  b.appendChild(T('#002 · DDD 기반 도메인 설계', { size: 21, style: S_BOLD, color: C.navy }));
  b.appendChild(T(
    'User Service는 두 개의 Aggregate Root(User, HostRequest)를 중심으로 설계했습니다. 비즈니스 규칙은 서비스가 아닌 엔티티 내부에서 강제하고, Keycloak(외부 시스템)과의 경계에서 원자성을 보상 트랜잭션으로 보장합니다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));

  b.appendChild(T('Value Objects — 검증 책임을 VO에 위임', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(Code(iw, [
    ['Email.of("user@example.com")  // RFC 형식 · 소문자 정규화 · 255자', hex('86EFAC')],
    ['Password.of("password1!")     // 8자 이상, toString() → "****"', hex('86EFAC')],
  ]));

  b.appendChild(T('Keycloak 회원가입 보상 트랜잭션', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(T('문제 — Keycloak 생성(성공) → DB 저장(실패) 시 고아 Keycloak 계정이 남는다',
    { size: 15, style: S_SEMI, color: C.ink, w: iw }));
  b.appendChild(Code(iw, [
    ['1. Email 중복 검증 (DB)', C.white],
    ['2. keycloakAuthService.createUser()   → Admin API POST /users', C.white],
    ['3. assignRole("CUSTOMER")   실패 시 → users().delete() 보상', hex('FCA5A5')],
    ['4. userRepository.save()    실패 시 → users().delete() 보상', hex('FCA5A5')],
  ]));

  b.appendChild(T('설계 의사결정', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  [['비밀번호 DB 미저장', 'Keycloak 위임 → 평문 저장 위험 제거'],
   ['현재 비밀번호 검증 → ROPC 재사용', 'Admin API reset-password는 현재 비밀번호 검증이 없음'],
   ['회원 탈퇴 → Keycloak disabled', '소셜 재가입 시 데이터 연속성 보장']
  ].forEach(([k, v]) => b.appendChild(Bullet(iw, `${k} — ${v}`, { size: 15, color: C.gray })));
  two.appendChild(b);

  col.appendChild(equalize(two));
  Footer(s, 4, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S5 — TROUBLESHOOTING 1 (캐시 · 격벽)
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(5, 'TROUBLESHOOTING · 캐시와 격벽');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('05 · TROUBLESHOOTING'));
  col.appendChild(T('병목은 코드가 아니라 설정 기본값에 있었다',
    { size: 40, style: S_BOLD, color: C.navy }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2, iw = cw - 60;

  // #001 Caffeine L1
  const a = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  a.appendChild(T('#001 · Redis 블랙리스트 조회 병목', { size: 21, style: S_BOLD, color: C.navy }));
  a.appendChild(T('GET /users/me · vuser 10 → 70 Ramp-Up · Gateway Server',
    { size: 14, style: S_REG, color: C.gray, w: iw }));

  const mA = HStack(12);
  const maw = (iw - 24) / 3;
  mA.appendChild(BeforeAfter(maw, '24회', '1회', '50ms 초과 스파이크', { size: 24 }));
  mA.appendChild(BeforeAfter(maw, '28.32', '26.18', 'MTT avg (ms)', { size: 24 }));
  mA.appendChild(BeforeAfter(maw, '1,400', '~14', 'Redis 호출/초', { size: 24 }));
  a.appendChild(equalize(mA));

  a.appendChild(T('원인 분석', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  a.appendChild(Bullet(iw, '모든 인증 요청마다 redisTemplate.hasKey("blacklist:{jti}") 1회 → 1,400 TPS면 초당 1,400회 Redis 왕복', { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, 'WebFlux는 Non-blocking이지만 Lettuce 커넥션 풀은 유한 — 동시 Mono 폭증 시 대기 큐 적체', { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, '동일 사용자는 AT 만료까지 같은 JTI를 반복 사용 → 캐시 히트율 ~99% 수렴 예상', { size: 15, color: C.gray }));

  a.appendChild(T('해결 & 결과', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  a.appendChild(Bullet(iw, 'Caffeine L1 캐시 추가 — TTL 5s · maxSize 10,000 (~2MB)', { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, 'Spring CacheManager 미사용 — LoadBalancer 캐시와 충돌해 인스턴스가 따로 생성되는 문제 회피', { size: 15, color: C.gray }));

  const twA = Card(iw, { bg: C.amber, pad: 16, gap: 0, stroke: false });
  twA.appendChild(T('트레이드오프 — 로그아웃 후 최대 5초 유효 처리 가능, 다중 Gateway 인스턴스 시 L1 캐시는 공유되지 않는다.',
    { size: 14, style: S_REG, color: C.gray, w: iw - 32 }));
  a.appendChild(twA);
  two.appendChild(a);

  // #002 Bulkhead
  const b = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  b.appendChild(T('#002 · Circuit Breaker 뒤에 숨은 Bulkhead', { size: 21, style: S_BOLD, color: C.navy }));
  b.appendChild(T('Gateway Server / User Service · vuser 25 → 50 전환 시점',
    { size: 14, style: S_REG, color: C.gray, w: iw }));

  const mB = HStack(12);
  const mbw = (iw - 24) / 3;
  mB.appendChild(BeforeAfter(mbw, '25', '125+', '안정 vuser 한계', { size: 24 }));
  mB.appendChild(BeforeAfter(mbw, '4,917', '0', '에러 건수', { size: 24 }));
  mB.appendChild(BeforeAfter(mbw, '25', '200', 'maxConcurrentCalls', { size: 24 }));
  b.appendChild(equalize(mB));

  b.appendChild(T('증상 — vuser 25까지는 정상(TPS ~160), 50으로 늘리는 순간 모든 요청 503 · TPS 0',
    { size: 15, style: S_SEMI, color: C.ink, w: iw }));
  b.appendChild(Code(iw, [
    ["CircuitBreaker 'user-service' recorded an error:", hex('FCA5A5')],
    ['  BulkheadFullException: Bulkhead is full and does not', hex('FCA5A5')],
    ['  permit further calls', hex('FCA5A5')],
    ['  elapsed time = 0 ms   ← 처리 시도조차 없음', hex('FDE68A')],
  ]));

  b.appendChild(T('원인 분석', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(Bullet(iw, 'Resilience4j Bulkhead maxConcurrentCalls 기본값 25가 명시 설정이 없어 그대로 적용됐다', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, 'vuser 50 전환 즉시 25개 초과분을 0ms에 거부 → CB sliding window에 실패로 집계 → 실패율 50% 초과 → CB OPEN', { size: 15, color: C.gray }));

  const insB = Card(iw, { bg: C.soft, pad: 16, gap: 0, stroke: false });
  insB.appendChild(T(
    '핵심 인사이트 — "Elapsed: 0ms"는 처리 시도조차 없었다는 뜻이고, 그것이 CB가 아니라 Bulkhead를 지목하는 결정적 단서였다. CB를 잠깐 꺼서 레이어별 로그를 벗겨야 진짜 원인이 보였다.',
    { size: 15, style: S_REG, color: C.ink, w: iw - 32 }));
  b.appendChild(insB);
  two.appendChild(b);

  col.appendChild(equalize(two));
  Footer(s, 5, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S6 — TROUBLESHOOTING 2 (자원 한계)
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(6, 'TROUBLESHOOTING · 자원 한계');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('05 · TROUBLESHOOTING'));
  col.appendChild(T('Little\'s Law로 한계를 계산하고, 그 수치대로 고쳤다',
    { size: 40, style: S_BOLD, color: C.navy }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2, iw = cw - 60;

  // #003 HikariCP
  const a = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  a.appendChild(T('#003 · 커넥션 풀 고갈 — TPS 1,342 피크 후 붕괴', { size: 21, style: S_BOLD, color: C.navy }));
  a.appendChild(T('GET /users/me · 50 vuser 정량 부하 · User Service / PostgreSQL',
    { size: 14, style: S_REG, color: C.gray, w: iw }));

  const mA = HStack(12);
  const maw = (iw - 24) / 3;
  mA.appendChild(BeforeAfter(maw, '붕괴', '1,720', '안정 TPS', { size: 24 }));
  mA.appendChild(BeforeAfter(maw, '5,163', '0', '에러 건수', { size: 24 }));
  mA.appendChild(BeforeAfter(maw, '37ms', '11ms', '평균 응답 (-70%)', { size: 24 }));
  a.appendChild(equalize(mA));

  a.appendChild(T('원인 분석', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  a.appendChild(Bullet(iw, "Little's Law 검증: 50 vuser ÷ 37ms = 이론 TPS 1,351 ≈ 실측 1,342 → 코드 버그가 아니라 이론 한계 도달", { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, 'HikariCP maximum-pool-size 기본값 10 vs 동시 vuser 50 → 커넥션 10개 즉시 소진', { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, '대기 스레드 40개가 connection-timeout 30초 동안 큐에 적체 → 응답 지연 폭증 → 연쇄 실패', { size: 15, color: C.gray }));

  a.appendChild(Code(iw, [
    ["Little's Law:  L = λ × W", hex('86EFAC')],
    ['pool-size = 동시 요청 수(vuser) × DB 접근 비율', C.white],
    ['          = 50 × 1.0 = 50', C.white],
    ['connection-timeout: 30,000ms → 3,000ms  (fast-fail)', hex('FDE68A')],
  ]));

  const insA = Card(iw, { bg: C.soft, pad: 16, gap: 0, stroke: false });
  insA.appendChild(T(
    '핵심 인사이트 — connection-timeout을 줄이는 것은 실패를 앞당기는 게 아니라 큐 적체를 막는 조치다. 30초 대기 시 뒤 요청까지 연쇄로 밀린다.',
    { size: 15, style: S_REG, color: C.ink, w: iw - 32 }));
  a.appendChild(insA);
  two.appendChild(a);

  // #004 Keycloak ROPC
  const b = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  b.appendChild(T('#004 · Keycloak ROPC 단일 병목 → Redis AT 캐시', { size: 21, style: S_BOLD, color: C.navy }));
  b.appendChild(T('POST /auth/login · 25 → 125 vuser · User Service / Keycloak / Redis',
    { size: 14, style: S_REG, color: C.gray, w: iw }));

  const mB = HStack(12);
  const mbw = (iw - 24) / 3;
  mB.appendChild(BeforeAfter(mbw, '175', '1,568', '평균 TPS (+796%)', { size: 24 }));
  mB.appendChild(BeforeAfter(mbw, '25', '125', '안정 vuser', { size: 24 }));
  mB.appendChild(BeforeAfter(mbw, '135ms', '63ms', '평균 응답 (-53%)', { size: 24 }));
  b.appendChild(equalize(mB));

  b.appendChild(T('원인 분석', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(Bullet(iw, '로그인 응답시간의 97%가 Keycloak ROPC — Gateway <1ms · User Service <5ms · Keycloak ~130ms', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, "Little's Law: 25 vuser × 135ms가 처리 한계. 30 vuser에서 Keycloak 큐가 누적되며 TimeLimiter 초과 → CB OPEN", { size: 15, color: C.gray }));

  b.appendChild(T('선택지 비교', { size: 15, style: S_BOLD, color: C.accent, ls: 0.5 }));
  const optRow = HStack(12);
  const ow2 = (iw - 12) / 2;
  const o1 = Card(ow2, { bg: C.white, pad: 16, gap: 4 });
  o1.appendChild(T('① Keycloak 클러스터링', { size: 15, style: S_SEMI, color: C.gray }));
  o1.appendChild(T('인프라 비용 2배 · 기각', { size: 14, style: S_REG, color: C.gray }));
  optRow.appendChild(o1);
  const o2 = Card(ow2, { bg: C.soft, pad: 16, gap: 4, stroke: false });
  o2.appendChild(T('② Redis AT 캐시', { size: 15, style: S_BOLD, color: C.accentDk }));
  o2.appendChild(T('Keycloak 호출 완전 우회 · 채택', { size: 14, style: S_SEMI, color: C.accentDk }));
  optRow.appendChild(o2);
  b.appendChild(equalize(optRow));

  b.appendChild(Bullet(iw, '캐시 키 at-cache:{SHA-256(lower(email))} — PII 보호 + DB LOWER(email) 인덱스 기준 일치', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, 'TTL을 JWT exp 기준으로 동적 계산 — Keycloak 설정이 바뀌어도 자동 동기화, 하드코딩 불일치 방지', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, 'AT만 캐시하고 RT는 RefreshTokenStore가 단일 진실 공급원 — 로테이션 후 stale RT 반환 불가', { size: 15, color: C.gray }));
  two.appendChild(b);

  col.appendChild(equalize(two));
  Footer(s, 6, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S7 — PERFORMANCE + 기술 스택 + RETROSPECTIVE
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(7, 'PERFORMANCE · RETROSPECTIVE');
  const col = Body(s, 20);

  col.appendChild(SectionLabel('06 · PERFORMANCE'));
  col.appendChild(T('개선의 기준은 항상 측정 가능한 수치였다', { size: 40, style: S_BOLD, color: C.navy }));

  const metrics = HStack(16);
  const mw = (CW - 16 * 4) / 5;
  [['24회 → 1회', '50ms 초과 스파이크', '-95.8%'],
   ['28.32 → 26.18', 'MTT avg (ms)', '-7.5%'],
   ['4,917 → 0', 'Bulkhead 에러 건수', 'CB CLOSED 유지'],
   ['붕괴 → 1,720', 'GET /users/me TPS', "Little's Law 오차 1%"],
   ['175 → 1,568', '로그인 TPS', '+796%']
  ].forEach(([v, l, sub]) => metrics.appendChild(Metric(mw, v, l, { sub, valueSize: 23 })));
  col.appendChild(equalize(metrics));

  const two = HStack(24);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 48) / 3;

  const tool = Card(cw, { bg: C.card, pad: 26, gap: 10 });
  const tw2 = cw - 52;
  tool.appendChild(T('측정 도구', { size: 20, style: S_BOLD, color: C.navy }));
  tool.appendChild(Bullet(tw2, '부하 테스트 — nGrinder (Ramp-Up, vuser 단계별 구간 적용)', { size: 15, color: C.gray }));
  tool.appendChild(Bullet(tw2, '분산 추적 — Zipkin span으로 병목 구간 식별', { size: 15, color: C.gray }));
  tool.appendChild(Bullet(tw2, '메트릭 — Prometheus + Grafana (JVM, Lettuce 커넥션)', { size: 15, color: C.gray }));
  tool.appendChild(T('의사결정의 주안점', { size: 16, style: S_BOLD, color: C.accent, ls: 0.5 }));
  tool.appendChild(Bullet(tw2, '대안을 최소 2개 이상 비교하고 채택 근거를 명시', { size: 15, color: C.gray }));
  tool.appendChild(Bullet(tw2, '최종 결정은 수치로 검증, 효과가 없으면 원인 재분석', { size: 15, color: C.gray }));
  two.appendChild(tool);

  const stack = Card(cw, { bg: C.card, pad: 26, gap: 8 });
  const sw = cw - 52;
  stack.appendChild(T('기술 스택', { size: 20, style: S_BOLD, color: C.navy }));
  [['Language & Framework', 'Java 21 · Spring Boot 3.5 · Spring Cloud 2025 · Spring Cloud Gateway (WebFlux) · Resilience4j Reactive'],
   ['Database & Security', 'PostgreSQL · Redis (Lettuce) · Caffeine · Keycloak 26 (ROPC, Admin API) · Flyway'],
   ['Infra & DevOps', 'AWS ECS Fargate · RDS · ECR · ALB · Docker · GitHub Actions'],
   ['Monitoring & Test', 'Prometheus · Grafana · Zipkin (Brave) · JUnit 5 · nGrinder']
  ].forEach(([k, v]) => {
    stack.appendChild(T(k, { size: 14, style: S_BOLD, color: C.accent, w: sw }));
    stack.appendChild(T(v, { size: 14, style: S_REG, color: C.gray, w: sw }));
  });
  two.appendChild(stack);

  const retro = Card(cw, { bg: C.white, pad: 26, gap: 10 });
  const rw = cw - 52;
  retro.appendChild(SectionLabel('07 · RETROSPECTIVE'));
  retro.appendChild(T('회고', { size: 20, style: S_BOLD, color: C.navy }));
  retro.appendChild(Bullet(rw, '외부 시스템 경계 설계 — Keycloak과 DB 간 원자성을 보상 트랜잭션으로 해결하며 MSA 분산 환경에서 트랜잭션의 의미를 체감했다', { size: 15, color: C.gray }));
  retro.appendChild(Bullet(rw, '보안을 레이어드로 설계 — 헤더 위조 차단 → JWT 검증 → 블랙리스트 조회 → 서비스 인가, 각 계층이 다른 위협을 방어한다', { size: 15, color: C.gray }));
  const q = Card(rw, { bg: C.soft, pad: 18, gap: 0, stroke: false });
  q.appendChild(T(
    '"인증은 동작하면 끝"이 아니라 탈취 · 재사용 · 병목까지가 설계의 범위라는 것을 이 프로젝트에서 배웠다.',
    { size: 16, style: S_SEMI, color: C.accentDk, w: rw - 36 }));
  retro.appendChild(q);
  two.appendChild(retro);

  col.appendChild(equalize(two));
  Footer(s, 7, TOTAL);
}

// ── 완료 ──────────────────────────────────────────────────────
figma.currentPage.selection = created;
figma.viewport.scrollAndZoomIntoView(created);

// 넘침 자체 검사 — 텍스트 길이/폰트에 따라 본문이 1080을 넘을 수 있다
const LIMIT = H - 110;
const overflow = [];
for (const f of created) {
  for (const child of f.children) {
    if (child.type !== 'FRAME' || child.layoutMode !== 'VERTICAL') continue;
    const bottom = child.y + child.height;
    if (bottom > LIMIT) {
      overflow.push(`  ⚠ ${f.name} — 본문 ${Math.round(bottom)}px (한계 ${LIMIT}px, ${Math.round(bottom - LIMIT)}px 초과)`);
    }
  }
}

`✅ First Ticket 슬라이드 ${created.length}장 생성 완료 (1920×1080)${removedCount ? ` · 이전 ${removedCount}장 교체` : ''}
폰트: ${FAMILY} (bold=${S_BOLD} / semi=${S_SEMI} / regular=${S_REG})
${overflow.length ? '넘침 발생 — 아래 슬라이드는 FS를 낮추거나 문구를 줄여야 한다:\n' + overflow.join('\n') : '넘침 없음 — 모든 슬라이드가 1080 안에 들어감'}`
