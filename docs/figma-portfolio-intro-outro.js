/**
 * 포트폴리오 통합 표지 + 클로징 — Figma 1920×1080 슬라이드 생성 스크립트
 *
 * [왜 필요한가]
 *   Personal RAG·First Ticket 두 덱을 이어 붙이면 1페이지가 곧바로 프로젝트 표지라,
 *   "지원자가 누구인가"가 8페이지에 가서야 처음 나온다. 채용담당자가 신입 이력서 1건에
 *   쓰는 시간이 3~5초라는 점을 감안하면 그 앞에 정체성을 세우는 장이 필요하다.
 *
 * [실행 방법]
 *   1. Figma에서 대상 파일 열기 (Personal RAG·First Ticket 슬라이드가 이미 있는 페이지 권장)
 *   2. 우클릭 → Plugins → "Scripter" 실행
 *   3. 이 파일 내용 전체를 붙여넣고 실행(▶ 또는 Cmd/Ctrl+Enter)
 *
 * [배치] 기존 1920×1080 슬라이드를 찾아 표지는 그 왼쪽, 클로징은 오른쪽에 놓는다.
 *   슬라이드가 하나도 없으면 원점 근처에 나란히 만든다.
 *
 * [이름] "00 · COVER" / "99 · CLOSING" — 앞뒤로 정렬되고, Personal RAG("01 · ")·
 *   First Ticket("FT 01 · ") 스크립트의 교체 대상에도 걸리지 않는다.
 */

// ── 지원처마다 바꿀 문장 ──────────────────────────────────────
// 클로징의 마지막 한 줄. 지원 직무/회사에 맞춰 매번 고쳐 쓰는 자리다.
const CLOSING_LINE = '측정할 수 있는 것부터 고치고, 고친 것을 수치로 증명하는 개발자가 되겠습니다.';

// ── 팔레트 ────────────────────────────────────────────────────
// 표지는 두 프로젝트(보라 Personal RAG / 티얼 First Ticket) 어느 쪽에도 치우치지 않도록
// 다크 네이비를 바탕으로 두고, 두 프로젝트 색을 액센트로 함께 쓴다.
const hex = (h) => ({
  r: parseInt(h.slice(0, 2), 16) / 255,
  g: parseInt(h.slice(2, 4), 16) / 255,
  b: parseInt(h.slice(4, 6), 16) / 255,
});
const C = {
  bg:      hex('0F1523'), // 표지 배경 (다크 네이비)
  bgSoft:  hex('1A2332'), // 표지 위 카드
  line:    hex('2A3548'),
  white:   hex('FFFFFF'),
  mute:    hex('9BA6B8'), // 다크 배경 위 보조 텍스트
  purple:  hex('7C5CFF'), // Personal RAG 액센트 (다크 배경에서 읽히도록 원본보다 밝게)
  teal:    hex('19B99F'), // First Ticket 액센트 (동일 이유)
  ink:     hex('1A2332'), // 밝은 배경용
  gray:    hex('6B7684'),
  card:    hex('F9FAFB'),
  border:  hex('E5E7EB'),
};
const fill = (c) => [{ type: 'SOLID', color: c }];
const FS = 1.15;

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
function T(chars, { size = 16, style = S_REG, color = C.white, w = null, lh = 1.5, ls = 0 } = {}) {
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
function VStack(gap = 16, props = {}) { const f = AL('VERTICAL', Object.assign({ itemSpacing: gap }, props)); f.fills = []; return f; }
function HStack(gap = 16, props = {}) { const f = AL('HORIZONTAL', Object.assign({ itemSpacing: gap }, props)); f.fills = []; return f; }
function Spacer(h, w = 10) { const f = figma.createFrame(); f.fills = []; f.resize(w, h); return f; }

function Card(width, { bg = C.bgSoft, pad = 26, gap = 10, radius = 12, stroke = C.line } = {}) {
  const c = AL('VERTICAL', {
    itemSpacing: gap, paddingLeft: pad, paddingRight: pad, paddingTop: pad, paddingBottom: pad,
  });
  c.fills = fill(bg);
  c.cornerRadius = radius;
  if (stroke) { c.strokes = fill(stroke); c.strokeWeight = 1; }
  c.resize(width, c.height);
  c.layoutSizingHorizontal = 'FIXED';
  c.layoutSizingVertical = 'HUG';
  return c;
}

/** 가로로 늘어선 카드 높이를 가장 큰 것에 맞춘다 */
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

// ── 배치 계산 ─────────────────────────────────────────────────
const W = 1920, H = 1080, PAD_X = 120;
const created = [];

// 재실행 시 이전 표지/클로징만 교체 (프로젝트 슬라이드는 건드리지 않는다)
let removedCount = 0;
{
  const pat = /^(00 · COVER|99 · CLOSING)/;
  const stale = figma.currentPage.children.filter(
    (n) => n.type === 'FRAME' && pat.test(n.name) && n.width === W && n.height === H
  );
  for (const n of stale) { n.remove(); removedCount++; }
}

// 기존 프로젝트 슬라이드 범위를 찾아 앞/뒤에 붙인다
const existing = figma.currentPage.children.filter(
  (n) => n.type === 'FRAME' && n.width === W && n.height === H
);
let coverX, closingX, baseY;
if (existing.length) {
  const minX = Math.min.apply(null, existing.map((n) => n.x));
  const maxR = Math.max.apply(null, existing.map((n) => n.x + n.width));
  baseY = Math.min.apply(null, existing.map((n) => n.y));
  coverX = minX - (W + 100);
  closingX = maxR + 100;
} else {
  baseY = 0;
  coverX = 0;
  closingX = W + 100;
}

function Slide(name, x, bg) {
  const f = figma.createFrame();
  f.name = name;
  f.resize(W, H);
  f.x = x; f.y = baseY;
  f.fills = fill(bg);
  f.clipsContent = true;
  figma.currentPage.appendChild(f);
  created.push(f);
  return f;
}

// ══════════════════════════════════════════════════════════════
// 00 — 통합 표지
// ══════════════════════════════════════════════════════════════
{
  const s = Slide('00 · COVER', coverX, C.bg);
  const CW = W - PAD_X * 2;

  const col = VStack(0);
  col.x = PAD_X; col.y = 76;
  s.appendChild(col);
  col.resize(CW, col.height);
  col.layoutSizingHorizontal = 'FIXED';
  col.layoutSizingVertical = 'HUG';

  col.appendChild(T('BACKEND ENGINEER · PORTFOLIO · 2026',
    { size: 16, style: S_BOLD, color: C.teal, ls: 1.6 }));
  col.appendChild(Spacer(30));

  const nameRow = HStack(20);
  nameRow.counterAxisAlignItems = 'BASELINE';
  nameRow.appendChild(T('박동진', { size: 76, style: S_BOLD, color: C.white, lh: 1.1, ls: 2 }));
  nameRow.appendChild(T('Backend Engineer', { size: 24, style: S_SEMI, color: C.mute }));
  col.appendChild(nameRow);

  col.appendChild(Spacer(18));
  col.appendChild(T('고친 것을 수치로 증명하는 개발자',
    { size: 34, style: S_BOLD, color: C.white }));
  col.appendChild(Spacer(10));
  col.appendChild(T(
    '두 프로젝트 모두 "동작한다"에서 멈추지 않고, 부하 테스트와 자동 평가 하네스로 개선을 수치로 검증했습니다. 가설이 틀렸을 때는 틀렸다고 기록하고 원인을 다시 찾았습니다.',
    { size: 19, style: S_REG, color: C.mute, w: CW * 0.62 }));

  col.appendChild(Spacer(30));

  // 핵심 역량 4개
  const skills = HStack(16);
  const sw = (CW - 48) / 4;
  [['측정 기반 개선', '골든셋 자동 평가 · nGrinder/k6 부하 테스트로 모든 변경을 전후 비교', C.teal],
   ['인증 · 보안 설계', 'JWT 무효화 · SSRF 차단 · 소유권 경계 · 정적 진단 대응', C.purple],
   ['병목 진단', "Little's Law로 한계 계산 · EXPLAIN 실행계획으로 인덱스 선택 확인", C.teal],
   ['LLM 애플리케이션', 'RAG 파이프라인 · Tool Calling · GPU 자원 admission control', C.purple]
  ].forEach(([t, d, accent]) => {
    const c = Card(sw, { pad: 22, gap: 8 });
    const bar = figma.createRectangle();
    bar.resize(34, 3); bar.fills = fill(accent); bar.cornerRadius = 2;
    c.appendChild(bar);
    c.appendChild(T(t, { size: 18, style: S_BOLD, color: C.white, w: sw - 44 }));
    c.appendChild(T(d, { size: 14, style: S_REG, color: C.mute, w: sw - 44 }));
    skills.appendChild(c);
  });
  col.appendChild(equalize(skills));

  col.appendChild(Spacer(24));

  // 프로젝트 2건 요약
  col.appendChild(T('PROJECTS', { size: 14, style: S_BOLD, color: C.mute, ls: 1.6 }));
  col.appendChild(Spacer(12));

  const projects = HStack(20);
  const pw = (CW - 20) / 2;
  [{ name: 'Personal RAG', accent: C.purple, order: '01',
     desc: '8GB VRAM 개인 PC에서 동작하는 로컬 RAG 문서 비서',
     meta: '2026.02 ~ 2026.03 · 1인 개발',
     stats: [['0% → 50%', 'RAG 답변 통과율'], ['P0 4건', '보안 취약점 수정'], ['37.2s → 1.2s', '콜드스타트']] },
   { name: 'First Ticket', accent: C.teal, order: '02',
     desc: 'MSA 기반 공연 티켓 예매 플랫폼 — 인증 · Gateway 담당',
     meta: '2026.04~05 · 6인 백엔드 · User Service / Gateway / Eureka 담당',
     stats: [['175 → 1,568', '로그인 TPS'], ['-99%', 'Redis 조회 횟수'], ['4,917 → 0', '부하 시 에러']] }
  ].forEach((p) => {
    const c = Card(pw, { pad: 28, gap: 10 });
    const head = HStack(12);
    head.counterAxisAlignItems = 'CENTER';
    const badge = AL('HORIZONTAL', { paddingLeft: 10, paddingRight: 10, paddingTop: 4, paddingBottom: 4 });
    badge.fills = fill(p.accent); badge.cornerRadius = 6;
    badge.appendChild(T(p.order, { size: 13, style: S_BOLD, color: C.bg }));
    head.appendChild(badge);
    head.appendChild(T(p.name, { size: 26, style: S_BOLD, color: C.white }));
    c.appendChild(head);
    c.appendChild(T(p.desc, { size: 17, style: S_SEMI, color: C.white, w: pw - 56 }));
    c.appendChild(T(p.meta, { size: 14, style: S_REG, color: C.mute, w: pw - 56 }));

    const st = HStack(10);
    const stw = (pw - 56 - 20) / 3;
    p.stats.forEach(([v, l]) => {
      const b = Card(stw, { bg: C.bg, pad: 14, gap: 3, stroke: null });
      b.appendChild(T(v, { size: 19, style: S_BOLD, color: p.accent, w: stw - 28 }));
      b.appendChild(T(l, { size: 13, style: S_REG, color: C.mute, w: stw - 28 }));
      st.appendChild(b);
    });
    c.appendChild(equalize(st));
    projects.appendChild(c);
  });
  col.appendChild(equalize(projects));

  // 하단 — 기술 스택 + 연락처
  const line = figma.createRectangle();
  line.resize(CW, 1); line.x = PAD_X; line.y = H - 150;
  line.fills = fill(C.line);
  s.appendChild(line);

  const stack = VStack(5);
  stack.appendChild(T('TECH STACK', { size: 12, style: S_BOLD, color: C.mute, ls: 1.4 }));
  stack.appendChild(T('Java 17 · 21 · Spring Boot 3.5 · Spring Cloud Gateway · Spring AI · JPA / QueryDSL',
    { size: 15, style: S_REG, color: C.white }));
  stack.appendChild(T('PostgreSQL · Redis · pgVector · Kafka · Keycloak · Docker · AWS ECS Fargate · GitHub Actions',
    { size: 15, style: S_REG, color: C.white }));
  s.appendChild(stack);
  stack.x = PAD_X; stack.y = H - 122;

  const contact = VStack(5);
  contact.appendChild(T('straycat405@gmail.com', { size: 16, style: S_SEMI, color: C.white }));
  contact.appendChild(T('github.com/straycat405', { size: 15, style: S_REG, color: C.mute }));
  contact.appendChild(T('velog.io/@straycat405/posts', { size: 15, style: S_REG, color: C.mute }));
  s.appendChild(contact);
  contact.counterAxisAlignItems = 'MAX';
  contact.x = W - PAD_X - contact.width;
  contact.y = H - 122;
}

// ══════════════════════════════════════════════════════════════
// 99 — 클로징
// ══════════════════════════════════════════════════════════════
{
  const s = Slide('99 · CLOSING', closingX, C.bg);
  const CW = W - PAD_X * 2;

  const col = VStack(0);
  col.x = PAD_X; col.y = 150;
  s.appendChild(col);
  col.resize(CW, col.height);
  col.layoutSizingHorizontal = 'FIXED';
  col.layoutSizingVertical = 'HUG';

  col.appendChild(T('THANK YOU FOR READING', { size: 16, style: S_BOLD, color: C.teal, ls: 1.8 }));
  col.appendChild(Spacer(26));
  col.appendChild(T('읽어주셔서 감사합니다', { size: 54, style: S_BOLD, color: C.white, lh: 1.2 }));
  col.appendChild(Spacer(20));
  col.appendChild(T(CLOSING_LINE, { size: 22, style: S_REG, color: C.mute, w: CW * 0.66 }));

  col.appendChild(Spacer(46));

  // 두 프로젝트에서 남긴 것 — 마무리로 한 번 더 요약
  const sum = HStack(20);
  const sw2 = (CW - 40) / 3;
  [['측정 없이 개선을 주장하지 않았습니다',
    '단발 결과가 유리하게 나왔을 때도 3회 반복으로 다시 확인했고, 그 결과 처음 결론을 스스로 정정했습니다.', C.teal],
   ['틀린 가설도 그대로 남겼습니다',
    '지연 원인을 두 번 잘못 짚은 과정과, 그것을 어떻게 뒤집었는지를 포트폴리오에 함께 실었습니다.', C.purple],
   ['안 한 것에도 이유를 남겼습니다',
    'DNS rebinding 차단을 보류한 판단처럼, 하지 않기로 한 결정과 그 조건을 코드 주석에 기록했습니다.', C.teal]
  ].forEach(([t, d, accent]) => {
    const c = Card(sw2, { pad: 24, gap: 8 });
    const bar = figma.createRectangle();
    bar.resize(34, 3); bar.fills = fill(accent); bar.cornerRadius = 2;
    c.appendChild(bar);
    c.appendChild(T(t, { size: 18, style: S_BOLD, color: C.white, w: sw2 - 48 }));
    c.appendChild(T(d, { size: 15, style: S_REG, color: C.mute, w: sw2 - 48 }));
    sum.appendChild(c);
  });
  col.appendChild(equalize(sum));

  // 하단 — 연락처를 크게
  const line = figma.createRectangle();
  line.resize(CW, 1); line.x = PAD_X; line.y = H - 190;
  line.fills = fill(C.line);
  s.appendChild(line);

  const nm = T('박 동 진', { size: 40, style: S_BOLD, color: C.white, ls: 2 });
  nm.x = PAD_X; nm.y = H - 158;
  s.appendChild(nm);
  const role = T('Backend Engineer', { size: 17, style: S_REG, color: C.mute });
  role.x = PAD_X; role.y = H - 100;
  s.appendChild(role);

  const links = HStack(40);
  links.counterAxisAlignItems = 'CENTER';
  [['Email', 'straycat405@gmail.com'],
   ['GitHub', 'github.com/straycat405'],
   ['Blog', 'velog.io/@straycat405/posts']
  ].forEach(([k, v]) => {
    const b = VStack(4);
    b.appendChild(T(k, { size: 13, style: S_BOLD, color: C.teal, ls: 1.2 }));
    b.appendChild(T(v, { size: 17, style: S_REG, color: C.white }));
    links.appendChild(b);
  });
  s.appendChild(links);
  links.x = W - PAD_X - links.width;
  links.y = H - 152;
}

// ── 완료 ──────────────────────────────────────────────────────
figma.currentPage.selection = created;
figma.viewport.scrollAndZoomIntoView(created);

// 하단 구분선(H-150)과 기술 스택 블록이 있으므로 본문은 그 위에서 끝나야 한다
const LIMIT = H - 160;
const overflow = [];
for (const f of created) {
  for (const child of f.children) {
    if (child.type !== 'FRAME' || child.layoutMode !== 'VERTICAL') continue;
    const bottom = child.y + child.height;
    if (bottom > LIMIT) {
      overflow.push(`  ⚠ ${f.name} — 본문 ${Math.round(bottom)}px (한계 ${LIMIT}px)`);
    }
  }
}

`✅ 표지 · 클로징 ${created.length}장 생성 완료 (1920×1080)${removedCount ? ` · 이전 ${removedCount}장 교체` : ''}
배치: 표지 x=${Math.round(coverX)} (기존 슬라이드 왼쪽) · 클로징 x=${Math.round(closingX)} (오른쪽)
폰트: ${FAMILY}
${overflow.length ? '넘침 발생:\n' + overflow.join('\n') : '넘침 없음'}`
