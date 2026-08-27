/**
 * BTLLM 포트폴리오 — Figma 1920×1080 슬라이드 자동 생성 스크립트
 *
 * [실행 방법]
 *   1. Figma에서 "포폴-20260827" 파일 열기
 *   2. 우클릭 → Plugins → "Scripter" 실행
 *      (없으면 Figma Community에서 "Scripter" 무료 설치)
 *   3. 이 파일 내용 전체를 Scripter 편집창에 붙여넣고 실행(▶ 또는 Cmd/Ctrl+Enter)
 *
 * [결과] 기존 콘텐츠 오른쪽 빈 공간에 1920×1080 프레임 7장이 생성된다.
 *        기존 노드는 읽지도 수정하지도 않는다 — 순수 추가만 한다.
 *
 * [디자인 근거] 기존 Personal LLM 포트폴리오 PDF(박동진_portfolio_personal_llm_001.pdf)를
 *   Ghostscript로 렌더링해 픽셀에서 직접 추출한 실제 색상값을 쓴다 — 눈대중 아님.
 *   보라 #5B3FD9 / 인디고 #2D1B6E / 연보라 #EEF0FB / 앰버 #F59E0B / 다크 #1E1E2E
 */

// ── 팔레트 ────────────────────────────────────────────────────
const hex = (h) => ({
  r: parseInt(h.slice(0, 2), 16) / 255,
  g: parseInt(h.slice(2, 4), 16) / 255,
  b: parseInt(h.slice(4, 6), 16) / 255,
});
const C = {
  accent:   hex('5B3FD9'), // 주 강조 — 섹션 라벨 · 지표 수치 · 포인트
  accentDk: hex('3B2A9E'), // pill 텍스트처럼 작은 글씨에서 대비를 확보해야 할 때
  navy:     hex('2D1B6E'), // 제목 (딥 인디고)
  ink:      hex('1A2332'), // 본문 강조
  gray:     hex('6B7684'), // 본문
  soft:     hex('EEF0FB'), // 연보라 강조 배경 (pill · 노트)
  card:     hex('F9FAFB'),
  border:   hex('E5E7EB'),
  dark:     hex('1E1E2E'), // 코드 블록 · 다크 노드
  white:    hex('FFFFFF'),
  amber:    hex('FEFBEA'), // 앰버 배경
  amberInk: hex('D97706'), // 앰버 텍스트
};
const fill = (c) => [{ type: 'SOLID', color: c }];

/**
 * 글자 크기 스케일.
 * 1920×1080은 A4보다 가로가 넓고 세로가 짧아, A4 기준 포인트를 그대로 쓰면 작아 보인다.
 * 실행 후 결과 메시지가 "넘침 발생"이라고 하면 이 값을 1.0~1.1로 낮추고,
 * 여백이 허전하면 1.2~1.25로 올린 뒤 다시 실행하면 된다.
 */
const FS = 1.15;

// ── 폰트 자동 감지 ────────────────────────────────────────────
// 환경마다 설치된 한글 폰트가 달라 하드코딩하면 깨진다. 실제 목록에서 고른다.
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

/**
 * 오토레이아웃 프레임 생성 (표준 Plugin API만 사용).
 * figma.createAutoLayout() 은 Figma MCP 실행 환경 전용 확장이라
 * Scripter/일반 플러그인에서는 존재하지 않는다 — 그래서 직접 구현한다.
 */
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

/** 섹션 라벨 — 기존 포폴의 "03 · ARCHITECTURE" 스타일 */
function SectionLabel(txt) {
  return T(txt, { size: 15, style: S_BOLD, color: C.accent, ls: 1.2 });
}

/** 태그 pill — 연보라 배경 + 진한 보라 텍스트 */
function Pill(txt) {
  const p = AL('HORIZONTAL', {
    itemSpacing: 0, paddingLeft: 18, paddingRight: 18, paddingTop: 9, paddingBottom: 9,
  });
  p.cornerRadius = 999;
  p.fills = fill(C.soft);
  p.appendChild(T(txt, { size: 15, style: S_SEMI, color: C.accentDk }));
  return p;
}

/** 카드 박스 */
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
 * 지표 박스 — 큰 수치 + 라벨.
 * [주의] 카드는 FIXED 폭이라, 수치 텍스트에 w를 주지 않으면 텍스트가 카드 밖으로
 *   자라다가 clipsContent에 잘린다(실제로 7번 슬라이드에서 "76.2%"가 "76.2"로 잘렸다).
 *   항상 내부 폭을 넘겨 줄바꿈되게 하고, 좁은 칸은 valueSize로 크기를 낮춘다.
 */
function Metric(width, value, label, { color = C.accent, sub = null, valueSize = 40 } = {}) {
  const m = Card(width, { bg: C.white, pad: 24, gap: 6 });
  const iw = width - 48;
  m.appendChild(T(value, { size: valueSize, style: S_BOLD, color, w: iw, lh: 1.25 }));
  m.appendChild(T(label, { size: 15, style: S_REG, color: C.gray, w: iw }));
  if (sub) m.appendChild(T(sub, { size: 13, style: S_REG, color: C.gray, w: iw }));
  return m;
}

/** Before → After 비교 행 */
function CompareRow(width, label, before, after) {
  const row = HStack(0, { paddingTop: 10, paddingBottom: 10 });
  row.resize(width, row.height);
  row.layoutSizingHorizontal = 'FIXED';
  row.counterAxisAlignItems = 'CENTER';

  const l = T(label, { size: 16, style: S_REG, color: C.ink, w: width * 0.44 });
  row.appendChild(l);
  const b = T(before, { size: 16, style: S_REG, color: C.gray, w: width * 0.2 });
  b.textAlignHorizontal = 'RIGHT';
  row.appendChild(b);
  const ar = T('→', { size: 15, style: S_REG, color: C.gray, w: width * 0.08 });
  ar.textAlignHorizontal = 'CENTER';
  row.appendChild(ar);
  const a = T(after, { size: 17, style: S_BOLD, color: C.accent, w: width * 0.28 });
  a.textAlignHorizontal = 'RIGHT';
  row.appendChild(a);
  return row;
}

/** 불릿 */
function Bullet(width, txt, { color = C.ink, size = 16 } = {}) {
  const row = HStack(10);
  row.resize(width, row.height);
  row.layoutSizingHorizontal = 'FIXED';
  const dot = T('·', { size, style: S_BOLD, color: C.accent });
  row.appendChild(dot);
  const body = T(txt, { size, style: S_REG, color, w: width - 20 });
  row.appendChild(body);
  return row;
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

/**
 * 재실행 시 이전에 이 스크립트가 만든 슬라이드를 지우고 새로 만든다.
 * 끄면(false) 옆에 계속 새로 쌓인다.
 *
 * [삭제 조건] 이름이 "01 · " 형식이면서 크기가 정확히 1920×1080인 프레임만 지운다.
 *   두 조건을 모두 걸어 사용자가 직접 만든 다른 프레임은 건드리지 않는다.
 */
const REPLACE_PREVIOUS = true;

let removedCount = 0;
if (REPLACE_PREVIOUS) {
  const namePattern = /^\d\d · /;
  const stale = figma.currentPage.children.filter(
    (n) => n.type === 'FRAME' && namePattern.test(n.name) && n.width === W && n.height === H
  );
  for (const n of stale) { n.remove(); removedCount++; }
}

// 기존 콘텐츠와 겹치지 않게 오른쪽 빈 공간부터 시작
let originX = 0;
for (const n of figma.currentPage.children) {
  originX = Math.max(originX, n.x + n.width);
}
originX += 400;
const originY = 0;

function Slide(idx, name) {
  const f = figma.createFrame();
  f.name = `${String(idx).padStart(2, '0')} · ${name}`;
  f.resize(W, H);
  f.x = originX + (idx - 1) * (W + 100);
  f.y = originY;
  f.fills = fill(C.white);
  f.clipsContent = true;
  figma.currentPage.appendChild(f);
  created.push(f);
  return f;
}

/** 본문 컬럼(자동 레이아웃)을 슬라이드에 얹는다 */
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

/** 하단 푸터 */
function Footer(slide, idx, total) {
  const line = figma.createRectangle();
  line.resize(W - PAD_X * 2, 1);
  line.x = PAD_X; line.y = H - 92;
  line.fills = fill(C.border);
  slide.appendChild(line);

  const t = T(`BTLLM — 박동진 · ${idx} / ${total}`, { size: 14, style: S_REG, color: C.gray });
  t.x = PAD_X; t.y = H - 72;
  slide.appendChild(t);
}

const TOTAL = 7;
const CW = W - PAD_X * 2; // 콘텐츠 폭 = 1680

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
  title.appendChild(T('BTLLM', { size: 108, style: S_BOLD, color: C.accent, lh: 1.05 }));
  title.appendChild(T('.', { size: 108, style: S_BOLD, color: C.navy, lh: 1.05 }));
  col.appendChild(title);

  col.appendChild(Spacer(28));

  col.appendChild(T('8GB VRAM 개인 PC에서 동작하는 로컬 RAG 문서 비서',
    { size: 30, style: S_BOLD, color: C.ink }));
  col.appendChild(Spacer(10));
  col.appendChild(T('문서가 외부로 나가지 않는 RAG · 골든셋 기반 자동 평가로 모든 개선을 검증',
    { size: 22, style: S_REG, color: C.gray }));

  col.appendChild(Spacer(52));

  const pills = HStack(12);
  ['Spring AI', 'RAG · pgVector', 'Ollama qwen3:8b', 'WebSocket 스트리밍',
   '평가 하네스', '보안 진단'].forEach((p) => pills.appendChild(Pill(p)));
  col.appendChild(pills);

  // 하단 프로필
  const line = figma.createRectangle();
  line.resize(CW, 1); line.x = PAD_X; line.y = H - 210;
  line.fills = fill(C.border);
  s.appendChild(line);

  const nm = T('박 동 진', { size: 44, style: S_BOLD, color: C.navy, ls: 2 });
  nm.x = PAD_X; nm.y = H - 178;
  s.appendChild(nm);
  const role = T('Backend Engineer · LLM 애플리케이션 · RAG 파이프라인',
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
// S2 — OVERVIEW + ARCHITECTURE
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(2, 'OVERVIEW · ARCHITECTURE');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('01 · OVERVIEW'));
  col.appendChild(T('밖으로 나가면 안 되는 문서를, 개인 PC 안에서', { size: 40, style: S_BOLD, color: C.navy }));

  // 프로젝트 메타 — 기간 · 인원 · 저장소를 한 줄로
  const meta = HStack(14);
  meta.counterAxisAlignItems = 'CENTER';
  [['기간', '2026.02 ~ 2026.03'],
   ['인원', '1인 개발 (개인 프로젝트)'],
   ['저장소', 'github.com/straycat405/personal-llm']
  ].forEach(([k, v], i) => {
    if (i) meta.appendChild(T('·', { size: 15, style: S_REG, color: C.gray }));
    const item = HStack(7);
    item.counterAxisAlignItems = 'CENTER';
    item.appendChild(T(k, { size: 14, style: S_BOLD, color: C.accent }));
    item.appendChild(T(v, { size: 15, style: S_REG, color: C.gray }));
    meta.appendChild(item);
  });
  col.appendChild(meta);

  col.appendChild(T(
    '상용 LLM API는 품질이 좋지만 문서 본문이 외부로 전송되고 사용량만큼 과금된다. 정부 공고문·계약서·사내 매뉴얼처럼 밖으로 나가면 안 되는 문서를 다루려면 로컬에서 끝나는 파이프라인이 필요하다. "로컬 모델은 느리고 부정확하다"는 전제를 직접 재고 확인하는 것이 이 프로젝트의 출발점이다.',
    { size: 18, style: S_REG, color: C.gray, w: CW * 0.72 }));

  col.appendChild(Spacer(8));

  // 아키텍처 다이어그램
  const arch = HStack(20);
  arch.counterAxisAlignItems = 'MIN';

  const cFront = Card(300, { bg: C.card, pad: 22, gap: 12 });
  cFront.appendChild(T('Frontend', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cFront.appendChild(NodeBox(256, 'React + TypeScript', 'WebSocket 토큰 스트리밍'));
  cFront.appendChild(T('REST · 인증 / 문서 / 이력', { size: 13, style: S_REG, color: C.gray }));
  arch.appendChild(cFront);

  const cBack = Card(700, { bg: C.card, pad: 22, gap: 10 });
  cBack.appendChild(T('Spring Boot 3.5 + Spring AI 1.1', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  const r1 = HStack(10);
  r1.appendChild(NodeBox(320, 'ChatClientFactory', 'Advisor 체인 · provider 라우팅'));
  r1.appendChild(NodeBox(320, 'LlmTools', 'Tool Calling 기반 조건부 검색'));
  cBack.appendChild(r1);
  const r2 = HStack(10);
  r2.appendChild(NodeBox(320, 'OllamaGenerationQueue', 'GPU 1슬롯 admission control', { bg: C.soft }));
  r2.appendChild(NodeBox(320, 'EtlPipelineService', 'Reader → Split → 임베딩', { bg: C.soft }));
  cBack.appendChild(r2);
  // 앰버 강조 — 참조 포폴이 ETL 경로를 앰버로 구분한 것과 같은 용법
  cBack.appendChild(NodeBox(656, 'SafeUrlFetcher', 'SSRF 가드 — scheme/포트/DNS 해석 IP 검증',
    { bg: C.amber, tc: C.amberInk, sc: C.amberInk }));
  arch.appendChild(cBack);

  const cInfra = Card(540, { bg: C.card, pad: 22, gap: 10 });
  cInfra.appendChild(T('Local Runtime', { size: 13, style: S_BOLD, color: C.accent, ls: 1 }));
  cInfra.appendChild(NodeBox(496, 'Ollama', 'qwen3:8b 생성 + bge-m3 임베딩', { bg: C.dark, tc: C.white, sc: C.border }));
  cInfra.appendChild(NodeBox(496, 'PostgreSQL + pgvector', '문서 청크 · 임베딩 · 대화 이력', { bg: C.dark, tc: C.white, sc: C.border }));
  arch.appendChild(cInfra);

  col.appendChild(arch);

  // 제약 배너
  const banner = Card(CW, { bg: C.soft, pad: 22, gap: 6, stroke: false });
  banner.appendChild(T('실행 제약 — 이 프로젝트 모든 수치의 전제',
    { size: 15, style: S_BOLD, color: C.accentDk, ls: 0.5 }));
  banner.appendChild(T(
    'RTX 4060 Ti 8GB · qwen3:8b(5.6GB) + bge-m3(0.7GB) → 여유 약 1.7GB. 이 한 줄이 청크 크기·재정렬 방식·GPU admission control까지 대부분의 설계를 규정한다.',
    { size: 17, style: S_REG, color: C.ink, w: CW - 44 }));
  col.appendChild(banner);

  Footer(s, 2, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S3 — KEY CONTRIBUTION
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(3, 'KEY CONTRIBUTION');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('02 · KEY CONTRIBUTION'));
  col.appendChild(T('개선보다 먼저 측정 체계를 만들었다', { size: 40, style: S_BOLD, color: C.navy }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';

  // 좌: 평가 하네스
  const left = Card(820, { bg: C.card, pad: 30, gap: 14 });
  left.appendChild(T('#001 · 재현 가능한 LLM 평가 하네스', { size: 21, style: S_BOLD, color: C.navy }));
  left.appendChild(T(
    'LLM 앱은 "고쳤더니 좋아진 것 같다"가 통하지 않는다. 같은 질문도 매번 다른 답이 나오고, 프롬프트 한 줄이 무관한 시나리오를 조용히 깬다.',
    { size: 16, style: S_REG, color: C.gray, w: 760 }));

  [['ragAccuracyExperiment', '청크 크기별 검색 Recall · 골든셋 35건'],
   ['ragGenerationQualityExperiment', '필수 사실 · 금지 오답 · 출처 정규식 판정'],
   ['providerComparisonExperiment', '동일 조건 모델 비교 · 3회 반복'],
   ['localConversationQualityExperiment', '정체성 · 기억 · 출력 계약 4종']
  ].forEach(([n, d]) => {
    const row = HStack(12);
    row.resize(760, row.height); row.layoutSizingHorizontal = 'FIXED';
    row.counterAxisAlignItems = 'CENTER';
    const tag = AL('HORIZONTAL', {
      paddingLeft: 12, paddingRight: 12, paddingTop: 6, paddingBottom: 6 });
    tag.fills = fill(C.soft); tag.cornerRadius = 6;
    tag.appendChild(T(n, { size: 13, style: S_SEMI, color: C.accentDk }));
    row.appendChild(tag);
    row.appendChild(T(d, { size: 15, style: S_REG, color: C.ink, w: 400 }));
    left.appendChild(row);
  });

  const warn = Card(760, { bg: C.amber, pad: 18, gap: 4, stroke: false });
  warn.appendChild(T('단발 결과를 믿지 않는다', { size: 15, style: S_BOLD, color: C.ink }));
  warn.appendChild(T(
    'provider 비교 1차는 "로컬 62.5% vs 상용 62.5% 동률" — 원하던 결론이라 더 위험했다. 3회 반복하자 50.0% vs 58.3%로 정정됐고, 편차는 오히려 상용이 컸다(12.5%p vs 0.0%p).',
    { size: 15, style: S_REG, color: C.gray, w: 724 }));
  left.appendChild(warn);
  two.appendChild(left);

  // 우: 컨텍스트 예산
  const right = Card(CW - 848, { bg: C.white, pad: 30, gap: 14 });
  right.appendChild(T('#002 · 컨텍스트 예산 재설계', { size: 21, style: S_BOLD, color: C.navy }));
  const rw = CW - 848 - 60;
  right.appendChild(T(
    '청크 1500토큰이 한국어 공고문에서 평균 5,425자 청크를 만들었다. topK=3 근거만 약 4,521토큰 — num_ctx 4,096을 검색 단계에서 이미 초과하고 있었다.',
    { size: 16, style: S_REG, color: C.gray, w: rw }));
  right.appendChild(T('검색이 정답을 찾아줘도 모델은 그 뒷부분을 보지 못했다.',
    { size: 16, style: S_SEMI, color: C.ink, w: rw }));

  const divider = figma.createRectangle();
  divider.resize(rw, 1); divider.fills = fill(C.border);
  right.appendChild(divider);

  right.appendChild(T('청크 1500 → 800 (동일 문서 · 동일 골든셋)',
    { size: 14, style: S_BOLD, color: C.accent, ls: 0.5 }));
  right.appendChild(CompareRow(rw, '문항 통과율', '0.0%', '50.0%'));
  right.appendChild(CompareRow(rw, '필수 사실 포함률', '38.1%', '76.2%'));
  right.appendChild(CompareRow(rw, '평균 응답시간', '65.8s', '50.6s'));

  const note = Card(rw, { bg: C.soft, pad: 18, gap: 0, stroke: false });
  note.appendChild(T(
    '76.2%는 gpt-4o-mini의 사실 포함률과 동일한 값이다 — "모델 체급 문제"로 분류했던 실패 상당수가 실제로는 컨텍스트 초과였다.',
    { size: 15, style: S_REG, color: C.ink, w: rw - 36 }));
  right.appendChild(note);
  two.appendChild(right);

  col.appendChild(two);
  Footer(s, 3, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S4 — TROUBLESHOOTING 1 : 설정은 있는데 전달되지 않았다
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(4, 'TROUBLESHOOTING · 설정 전달');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('03 · TROUBLESHOOTING'));
  col.appendChild(T('설정이 존재하는 것과, 그 값이 요청에 실리는 것은 다르다',
    { size: 40, style: S_BOLD, color: C.navy }));
  col.appendChild(T('두 사건 모두 앱은 정상 기동했고 설정 파일에는 올바른 값이 적혀 있었다.',
    { size: 18, style: S_REG, color: C.gray }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2;
  const iw = cw - 60;

  // 케이스 A — keep-alive
  const a = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  a.appendChild(T('#1 · 값의 형식이 틀렸다', { size: 21, style: S_BOLD, color: C.navy }));
  a.appendChild(T('증상 — 콜드스타트 제거 설정 직후 k6 baseline 에러율 100%',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));

  const codeA = Card(iw, { bg: C.dark, pad: 18, gap: 4, stroke: false, radius: 8 });
  codeA.appendChild(T('{"error":"time: missing unit in duration \\"-1\\""}',
    { size: 14, style: S_REG, color: hex('FCA5A5'), w: iw - 36 }));
  a.appendChild(codeA);

  a.appendChild(T(
    '원인 — 로컬 프록시로 백엔드→Ollama 요청 원문을 가로채 확인했다. YAML에 정수 -1을 썼지만 Spring AI의 keepAlive 필드가 String이라 "-1"로 직렬화됐고, Ollama의 Go time.ParseDuration이 단위 없는 값을 400으로 거부하고 있었다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));
  a.appendChild(T('해결 — 단위 명시 -1s', { size: 16, style: S_SEMI, color: C.accent, w: iw }));

  const mA = HStack(12);
  // 2단 카드 안의 2칸이라 폭이 좁다 — 기본 크기면 "188.6s → 74.5s"가 두 줄로 접혀
  // 좌우 카드 높이가 어긋난다. 네 칸 모두 같은 크기로 낮춰 한 줄에 맞춘다.
  const MW = (iw - 12) / 2, MV = 32;
  mA.appendChild(Metric(MW, '100% → 0%', 'k6 에러율', { valueSize: MV }));
  mA.appendChild(Metric(MW, '37.2s → 1.2s', '콜드스타트 (-97%)', { valueSize: MV }));
  a.appendChild(mA);
  two.appendChild(a);

  // 케이스 B — thinking
  const b = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  b.appendChild(T('#2 · 전달 경로가 끊겼다', { size: 21, style: S_BOLD, color: C.navy }));
  b.appendChild(T('증상 — 100~450자 답변에 평균 88.9s, p95 188.6s. 약 75초가 설명되지 않았다.',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));

  b.appendChild(T('두 번 틀렸다', { size: 14, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(Bullet(iw, '"출력이 길다" → num_predict 512 제한. 지연은 8.4s만 줄고 출처 표시율이 100%→37.5%로 붕괴. 되돌림', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, 'VRAM 압박 의심 → VRAM 93%에서 오히려 더 빨랐다(36.1 tok/s)', { size: 15, color: C.gray }));

  b.appendChild(T('진단 — 동일 질문, thinking 모드만 교체', { size: 14, style: S_BOLD, color: C.accent, ls: 0.5 }));
  b.appendChild(CompareRow(iw, 'think=true / think=false', '257토큰 35.1s', '50토큰 6.0s'));
  b.appendChild(T(
    '보이지 않는 <think> 토큰이 시간을 다 쓰고 있었다. application.yaml의 think:false는 설정돼 있었지만, ChatClientFactory가 defaultOptions를 통째로 지정하면서 실제 요청에 실리지 않았다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));

  const mB = HStack(12);
  mB.appendChild(Metric((iw - 12) / 2, '88.9s → 33.0s', '평균 지연 (-63%)', { valueSize: 32 }));
  mB.appendChild(Metric((iw - 12) / 2, '188.6s → 74.5s', 'p95 지연 (-60%)', { valueSize: 32 }));
  b.appendChild(mB);
  b.appendChild(T('끄는 것이 정답은 아니었다 — 사실 포함률은 77.8%→57.1%로 함께 떨어졌다. 트레이드오프를 규명하고 기본값은 품질 우선으로 뒀다.',
    { size: 15, style: S_REG, color: C.gray, w: iw }));
  two.appendChild(b);

  col.appendChild(two);
  Footer(s, 4, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S5 — TROUBLESHOOTING 2 : 모델에게 넘긴 결정권 / GPU
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(5, 'TROUBLESHOOTING · 위임과 자원');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('03 · TROUBLESHOOTING'));
  col.appendChild(T('모델에게 넘긴 결정권, 그리고 GPU 한 슬롯',
    { size: 40, style: S_BOLD, color: C.navy }));

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2;
  const iw = cw - 60;

  // A — Tool description
  const a = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  a.appendChild(T('#3 · description 어조 하나로 RAG가 죽었다', { size: 21, style: S_BOLD, color: C.navy }));
  a.appendChild(T('증상 — PDF 색인 후 "방금 준 문서 무슨 문서야?" → "어떤 문서도 제공받지 않았어요"',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));
  a.appendChild(Bullet(iw, 'vector_store 직접 조회 → 청크 정상 적재. ETL 문제 배제', { size: 15, color: C.gray }));
  a.appendChild(Bullet(iw, '도구 호출 로그 0건 → Ollama API 격리 테스트', { size: 15, color: C.gray }));

  const codeB = Card(iw, { bg: C.dark, pad: 18, gap: 6, stroke: false, radius: 8 });
  codeB.appendChild(T('"…필요할 때만 사용하세요"   → tool_calls: null',
    { size: 14, style: S_REG, color: hex('FCA5A5'), w: iw - 36 }));
  codeB.appendChild(T('"…물으면 반드시 호출하세요"  → 정상 호출',
    { size: 14, style: S_REG, color: hex('86EFAC'), w: iw - 36 }));
  a.appendChild(codeB);

  a.appendChild(T(
    '원인은 description의 어조였다. 상시 Advisor를 Tool로 바꾸는 것은 최적화처럼 보이지만, 실제로는 "동작 여부의 결정권을 모델에게 넘기는" 설계 변경이다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));
  a.appendChild(Metric(iw, '0% → 100%', '지식베이스 Tool 호출률'));
  two.appendChild(a);

  // B — GPU admission control
  const b = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  b.appendChild(T('#4 · GPU는 1개인데 요청은 무한정 쌓였다', { size: 21, style: S_BOLD, color: C.navy }));
  b.appendChild(T('증상 — 동시 2~3명이면 대부분 30초 초과. GPU가 느려서만이 아니었다.',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));
  b.appendChild(Bullet(iw, '세션마다 여러 요청을 동시에 시작 가능', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, '연결이 끊겨도 진행 중이던 Ollama 호출이 GPU를 계속 점유', { size: 15, color: C.gray }));
  b.appendChild(Bullet(iw, 'QUEUED 응답은 안내 문구일 뿐 — 서버가 순서를 강제하지 않았다', { size: 15, color: C.gray }));
  b.appendChild(T('원인 — .subscribe()의 반환값(Disposable)을 버려 취소할 방법 자체가 없었다.',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));

  const codeC = Card(iw, { bg: C.dark, pad: 18, gap: 5, stroke: false, radius: 8 });
  codeC.appendChild(T('// 워커 스레드를 실제로 점유해야 동시성 제약이 의미를 갖는다',
    { size: 13, style: S_REG, color: hex('9CA3AF'), w: iw - 36 }));
  codeC.appendChild(T('finalPrompt.stream().chatResponse()', { size: 14, style: S_REG, color: C.white, w: iw - 36 }));
  codeC.appendChild(T('  .doOnNext(r -> forwardToken(session, provider, r))',
    { size: 14, style: S_REG, color: hex('86EFAC'), w: iw - 36 }));
  codeC.appendChild(T('  .blockLast();', { size: 14, style: S_REG, color: hex('86EFAC'), w: iw - 36 }));
  b.appendChild(codeC);

  b.appendChild(T(
    'Reactor의 .subscribe()는 비동기라 큐 워커 스레드에 넘기면 즉시 반환되고 큐가 아무것도 직렬화하지 못한다. 토큰은 도착 즉시 전송하되 스레드는 실제로 블로킹하도록 바꿨다. 세션당 취소 핸들과 세대 카운터로 좀비 스트림도 정리한다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));
  two.appendChild(b);

  col.appendChild(two);
  Footer(s, 5, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S6 — SECURITY
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(6, 'SECURITY');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('04 · SECURITY'));
  col.appendChild(T('"로컬 앱"이라는 전제가 틀렸다', { size: 40, style: S_BOLD, color: C.navy }));
  col.appendChild(T(
    '개인 PC용 앱이라는 인상과 달리, 공개 회원가입과 JWT 인증이 있는 다중 사용자 웹 서비스였다. 정적 스캔(Critical 1 · High 5 · Medium 2) 이후 기능 개발을 멈추고 외부 노출 전 필수 항목부터 고쳤다.',
    { size: 18, style: S_REG, color: C.gray, w: CW * 0.75 }));

  const grid = HStack(20);
  grid.counterAxisAlignItems = 'MIN';
  const gw = (CW - 60) / 4;

  const items = [
    ['01', 'JWT 안전 실패', 'Compose가 저장소에 공개된 고정 키로 기동 — 알면 임의 사용자로 토큰 위조 가능',
     '미설정·저엔트로피 키는 기동 자체를 실패시킴. JWT subject를 매 요청 DB와 대조'],
    ['02', 'RAG 소유권 경계', '로그인한 누구나 타인이 올린 문서를 열람·검색·삭제할 수 있었음',
     'owner_id predicate를 목록·삭제·검색 전 경로에 강제. 신원 없으면 검색 거부(fail-closed)'],
    ['03', 'SSRF 차단기', '사용자·모델이 준 URL을 검증 없이 요청 — 내부망·클라우드 메타데이터 접근 가능',
     'scheme/포트 allowlist + DNS 해석 결과 기반 사설 IP 차단, 리다이렉트 매 홉 재검증'],
    ['04', '인프라 노출', 'DB·모니터링 포트가 전체 인터페이스에 공개, 기본 계정 그대로',
     'loopback 바인딩으로 전환, 기본 계정 제거 및 안전 실패 적용'],
  ];

  items.forEach(([no, title, prob, fix]) => {
    const c = Card(gw, { bg: C.card, pad: 24, gap: 10 });
    const head = HStack(10);
    head.counterAxisAlignItems = 'CENTER';
    const badge = AL('HORIZONTAL', {
      paddingLeft: 10, paddingRight: 10, paddingTop: 4, paddingBottom: 4 });
    badge.fills = fill(C.accent); badge.cornerRadius = 6;
    badge.appendChild(T(no, { size: 13, style: S_BOLD, color: C.white }));
    head.appendChild(badge);
    head.appendChild(T(title, { size: 18, style: S_BOLD, color: C.navy }));
    c.appendChild(head);

    const iw2 = gw - 48;
    c.appendChild(T(prob, { size: 15, style: S_REG, color: C.gray, w: iw2 }));
    const fixBox = Card(iw2, { bg: C.soft, pad: 14, gap: 0, stroke: false, radius: 8 });
    fixBox.appendChild(T(fix, { size: 14, style: S_REG, color: C.ink, w: iw2 - 28 }));
    c.appendChild(fixBox);
    grid.appendChild(c);
  });
  col.appendChild(grid);

  const honest = Card(CW, { bg: C.amber, pad: 24, gap: 6, stroke: false });
  honest.appendChild(T('정직하게 남긴 한계', { size: 16, style: S_BOLD, color: C.ink }));
  honest.appendChild(T(
    'SSRF 가드는 검증과 연결 사이 DNS가 바뀌는 rebinding까지는 막지 못한다. 완전 차단하려면 주소 pinning이 필요하고, 현재 위협 모델(로컬 단일 사용자)상 우선순위를 낮췄다는 판단을 코드 주석에 남겼다. "안 한 이유"를 남기는 것도 "한 것"만큼 중요하다고 봤다.',
    { size: 16, style: S_REG, color: C.gray, w: CW - 48 }));
  col.appendChild(honest);

  Footer(s, 6, TOTAL);
}

// ══════════════════════════════════════════════════════════════
// S7 — PERFORMANCE + RETROSPECTIVE
// ══════════════════════════════════════════════════════════════
{
  const s = Slide(7, 'PERFORMANCE · RETROSPECTIVE');
  const col = Body(s, 22);

  col.appendChild(SectionLabel('05 · PERFORMANCE'));
  col.appendChild(T('측정 없이는 개선을 주장할 수 없다', { size: 40, style: S_BOLD, color: C.navy }));

  const metrics = HStack(16);
  const mw = (CW - 16 * 4) / 5;
  [['0% → 50%', 'RAG 답변 통과율'],
   ['38.1% → 76.2%', 'RAG 사실 포함률'],
   ['37.2s → 1.2s', '콜드스타트'],
   ['88.9s → 33.0s', '평균 응답 지연'],
   ['0% → 100%', 'Tool 호출률']
  // 한 줄에 5칸이라 칸당 폭이 좁다 — 수치 크기를 낮춰 "38.1% → 76.2%" 같은 긴 값도 들어가게 한다
  ].forEach(([v, l]) => metrics.appendChild(Metric(mw, v, l, { valueSize: 27 })));
  col.appendChild(metrics);

  const two = HStack(28);
  two.counterAxisAlignItems = 'MIN';
  const cw = (CW - 28) / 2;
  const iw = cw - 60;

  const learn = Card(cw, { bg: C.card, pad: 30, gap: 12 });
  learn.appendChild(T('배운 것', { size: 21, style: S_BOLD, color: C.navy }));
  learn.appendChild(Bullet(iw, '단발 결과는 유리한 방향으로도 오도한다. provider 비교 1차 결과("로컬이 상용과 동률")는 내가 원하던 결론이었기에 더 위험했다.'));
  learn.appendChild(Bullet(iw, '같은 파이프라인 두 조건(로컬/상용)을 비교하는 것만으로는 두 조건에 공통으로 걸린 결함이 안 보인다. 컨텍스트 초과가 그랬다.'));
  learn.appendChild(Bullet(iw, '개선안을 만들기 전에, 그 개선이 드러날 수 있는 측정 조건인지 먼저 확인해야 한다.'));
  two.appendChild(learn);

  const left = Card(cw, { bg: C.white, pad: 30, gap: 12 });
  left.appendChild(T('남은 것', { size: 21, style: S_BOLD, color: C.navy }));
  left.appendChild(T(
    'PDF 표 구조가 평탄화되며 트랙 레이블과 값의 연결이 끊기는 문제는 미해결이다. 로컬·상용 두 모델이 12회 시도 전부 실패했다 — 근거에 없는 사실은 어떤 모델도 만들어낼 수 없다.',
    { size: 16, style: S_REG, color: C.gray, w: iw }));
  const q = Card(iw, { bg: C.soft, pad: 20, gap: 0, stroke: false });
  q.appendChild(T(
    '"근거가 있는데 못 쓴다"와 "근거가 없다" 사이에는 "근거는 있는데 의미 연결이 파괴된 채로 있다"는 제3의 상태가 존재한다.',
    { size: 16, style: S_SEMI, color: C.accentDk, w: iw - 40 }));
  left.appendChild(q);
  left.appendChild(T('다음 과제 — 표 구조를 보존하는 파서 도입',
    { size: 16, style: S_SEMI, color: C.ink, w: iw }));
  two.appendChild(left);

  col.appendChild(two);
  Footer(s, 7, TOTAL);
}

// ── 완료 ──────────────────────────────────────────────────────
figma.currentPage.selection = created;
figma.viewport.scrollAndZoomIntoView(created);

// 넘침 자체 검사 — 텍스트 길이/폰트에 따라 본문이 1080을 넘을 수 있으므로
// 실행 직후 어떤 슬라이드를 손봐야 하는지 바로 알려준다.
const LIMIT = H - 110; // 푸터 라인(H-92) 위 여유
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

`✅ 슬라이드 ${created.length}장 생성 완료 (1920×1080)${removedCount ? ` · 이전 ${removedCount}장 교체` : ''}
폰트: ${FAMILY} (bold=${S_BOLD} / semi=${S_SEMI} / regular=${S_REG})
${overflow.length ? '넘침 발생 — 아래 슬라이드는 폰트 크기나 문구를 줄여야 한다:\n' + overflow.join('\n') : '넘침 없음 — 모든 슬라이드가 1080 안에 들어감'}`
